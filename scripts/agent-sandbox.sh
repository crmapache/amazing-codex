#!/bin/sh
#
# The sandbox IDE for an agent's live checks - on the display the person's mouse is not on, the laptop's
# built-in screen first, and without taking the keyboard from them.
#
#   ./scripts/agent-sandbox.sh start [extra Gradle -P... flags]   e.g. -PwebviewDevUrl=http://localhost:5190
#   ./scripts/agent-sandbox.sh hold      the sandbox in front for real key presses, focus guard paused
#   ./scripts/agent-sandbox.sh release   focus back to the person, focus guard on again
#   ./scripts/agent-sandbox.sh stop
#
# ACX_SANDBOX_PROJECT=<folder> opens another project instead of sandbox-project (the listing's demo project,
# scripts/listing/shoot.mjs).
#
# The panel is open to CDP on 4452 (ports.md): Playwright connects to http://127.0.0.1:4452 and takes the
# page whose address has acx-webview. start returns once that page answers; the log is
# build/sandbox-agent.log. stop takes down this run and only it, by the PIDs start wrote down - the
# original's sandboxes and the person's own sandbox.sh run live beside it.
#
# Why it is not just `./gradlew runIde`: the person works on the main display while the agent checks, and a
# WebStorm window opening on top of their editor and taking their keystrokes is exactly what they asked
# not to happen. The window is put aside by writing its frame into the sandbox's own settings before the
# start (the IDE restores frames from there), and the guard below hands the keyboard back whenever the
# sandbox makes itself the front app while the mouse is not on its display - a person who clicked into the
# sandbox to look is left alone. With one display there is nowhere aside to go: the window opens where it
# always did, behind nothing, and the guard still returns the keyboard.

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SANDBOX_PROJECT="${ACX_SANDBOX_PROJECT:-$PROJECT_DIR/sandbox-project}"
BUILD_DIR="$PROJECT_DIR/build"
LOG="$BUILD_DIR/sandbox-agent.log"
STATE="$BUILD_DIR/sandbox-agent"
CDP_PORT=4452
ASIDE="$PROJECT_DIR/scripts/aside-display.js"
MARKER="idea.required.plugins.id=io.github.crmapache.amazingcodex"

read_prop() {
  sed -n "s/^$1 *= *//p" "$PROJECT_DIR/gradle.properties" | head -1
}

ide_pid() {
  # The IDE itself, not the Gradle client: only its command line carries the plugin marker.
  pgrep -f "$MARKER" 2>/dev/null | head -1
}

# Writes the aside display's frame into the sandbox's settings: the project's own frame in
# recentProjects.xml and the default for new frames in window.state.xml. The IDE checks the bounds fit a
# display that is there, so a frame on a laptop screen that is gone later only falls back to the default.
place_frame() {
  bounds="$(osascript -l JavaScript "$ASIDE" 2>/dev/null)" || return 0
  options="$PROJECT_DIR/.intellijPlatform/sandbox/amazing-codex/$(read_prop platformType)-$(read_prop platformVersion)/config/options"
  mkdir -p "$options"

  python3 -I - "$options" "$SANDBOX_PROJECT" "$HOME" $bounds <<'PY'
import os, re, sys

options, project, home, x, y, w, h = sys.argv[1:8]
frame = f'<frame x="{x}" y="{y}" width="{w}" height="{h}" />'

state = os.path.join(options, "window.state.xml")
if os.path.exists(state):
    text = open(state, encoding="utf-8").read()
    manager = re.search(r'<component name="WindowManager">.*?</component>', text, re.S)
    if manager:
        block = re.sub(r'<frame\b[^>]*/>', frame, manager.group(0)) if "<frame" in manager.group(0) \
            else manager.group(0).replace("</component>", f"  {frame}\n  </component>")
        text = text[:manager.start()] + block + text[manager.end():]
    else:
        text = text.replace("</application>", f'  <component name="WindowManager">\n    {frame}\n  </component>\n</application>')
else:
    text = f'<application>\n  <component name="WindowManager">\n    {frame}\n  </component>\n</application>\n'
open(state, "w", encoding="utf-8").write(text)

recent = os.path.join(options, "recentProjects.xml")
if os.path.exists(recent):
    text = open(recent, encoding="utf-8").read()
    key = project.replace(home, "$USER_HOME$", 1)
    entry = re.search(r'<entry key="' + re.escape(key) + r'">.*?</entry>', text, re.S)
    if entry:
        block = re.sub(r'<frame\b[^>]*/>', frame, entry.group(0))
        text = text[:entry.start()] + block + text[entry.end():]
        open(recent, "w", encoding="utf-8").write(text)
PY
}

# "pid bundle-id mouse-inside" of the front app; the bounds say where "inside" is.
probe() {
  osascript -l JavaScript "$ASIDE" front ${1:-0 0 0 0} 2>/dev/null
}

front_pid() {
  probe | cut -d' ' -f1
}

# Makes the app with this bundle id the front one again. Through LaunchServices (`open -b`): since macOS 14
# an app activated from a background process takes the front only if the active app yields it, and the
# sandbox never would.
give_back() {
  [ -n "$1" ] && [ "$1" != "-" ] && open -b "$1" 2>/dev/null || true
}

# Hands the keyboard back each time the sandbox becomes the front app on its own: at the start, when a
# dialog opens, when the IDE asks for attention. A person who clicked into the sandbox to look has the
# mouse on its display, and is left alone. Runs until the IDE is gone.
guard_focus() {
  ide="$1"
  person="$2"
  bounds="$(osascript -l JavaScript "$ASIDE" 2>/dev/null || echo "0 0 0 0")"
  was=""
  while kill -0 "$ide" 2>/dev/null; do
    set -- $(probe "$bounds")
    now="${1:-}"
    if [ -n "$now" ] && [ "$now" != "$ide" ]; then
      person="${2:-}"
    elif [ "$now" = "$ide" ] && [ "$was" != "$ide" ] && [ ! -e "$STATE.hold" ] && [ "${3:-}" != "true" ]; then
      give_back "$person"
      echo "$(date '+%H:%M:%S') the sandbox took the front, gave it back to $person" >>"$STATE.guard.log"
      sleep 0.5
      now="$(front_pid)"
    fi
    was="$now"
    sleep 0.3
  done
}

start() {
  if [ -n "$(ide_pid)" ]; then
    echo "A sandbox of this plugin is already running (PID $(ide_pid)). Stop it first: $0 stop" >&2
    exit 1
  fi
  if lsof -nP -iTCP:"$CDP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $CDP_PORT is taken by something else." >&2
    exit 1
  fi

  mkdir -p "$BUILD_DIR"
  place_frame
  person="$(probe | cut -d' ' -f2)"

  cd "$PROJECT_DIR"
  nohup ./gradlew runIde -PopenProject="$SANDBOX_PROJECT" -PjcefDebugPort="$CDP_PORT" "$@" >"$LOG" 2>&1 &
  echo $! >"$STATE.gradle"

  # The panel's page: from the plugin's archive, or from the dev server when one is given.
  panel="acx-webview"
  for flag in "$@"; do
    case "$flag" in -PwebviewDevUrl=?*) panel="\"url\": \"${flag#-PwebviewDevUrl=}" ;; esac
  done

  ide=""
  waited=0
  while [ "$waited" -lt 600 ]; do
    if [ -z "$ide" ]; then
      ide="$(ide_pid)"
      if [ -n "$ide" ]; then
        echo "$ide" >"$STATE.ide"
        guard_focus "$ide" "$person" >/dev/null 2>&1 &
        echo $! >"$STATE.guard"
      fi
    fi
    if curl -s "http://127.0.0.1:$CDP_PORT/json" 2>/dev/null | grep -q -F "$panel"; then
      echo "Sandbox ready: IDE PID $ide, CDP http://127.0.0.1:$CDP_PORT, log $LOG"
      return 0
    fi
    if ! kill -0 "$(cat "$STATE.gradle")" 2>/dev/null; then
      echo "The run ended before the panel came up - see $LOG" >&2
      exit 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "The panel did not come up in 10 minutes - see $LOG" >&2
  exit 1
}

stop() {
  rm -f "$STATE.hold"
  ide="$(cat "$STATE.ide" 2>/dev/null || true)"
  if [ -n "$ide" ] && kill -0 "$ide" 2>/dev/null; then
    # Politely first: an IDE killed in the middle of writing its indexes reports "Storage corrupted" on the
    # next start (see sandbox.sh).
    kill "$ide" 2>/dev/null || true
    waited=0
    while kill -0 "$ide" 2>/dev/null && [ "$waited" -lt 20 ]; do
      sleep 1
      waited=$((waited + 1))
    done
    kill -0 "$ide" 2>/dev/null && kill -9 "$ide" 2>/dev/null || true
  fi
  for file in "$STATE.guard" "$STATE.gradle"; do
    pid="$(cat "$file" 2>/dev/null || true)"
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
  rm -f "$STATE.ide" "$STATE.guard" "$STATE.gradle" "$STATE.guard.log"
}

hold() {
  ide="$(cat "$STATE.ide" 2>/dev/null || true)"
  [ -n "$ide" ] || { echo "No sandbox of the agent is running." >&2; exit 1; }
  # Who gets the keyboard back on release.
  probe | cut -d' ' -f2 >"$STATE.hold"
  osascript -e "tell application \"System Events\" to set frontmost of (first process whose unix id is $ide) to true"
}

release() {
  person="$(cat "$STATE.hold" 2>/dev/null || true)"
  rm -f "$STATE.hold"
  ide="$(cat "$STATE.ide" 2>/dev/null || true)"
  if [ -n "$person" ] && [ "$(front_pid)" = "$ide" ]; then
    give_back "$person"
  fi
}

case "${1:-}" in
  start) shift; start "$@" ;;
  stop) stop ;;
  hold) hold ;;
  release) release ;;
  *) echo "usage: $0 start [-P...] | hold | release | stop" >&2; exit 2 ;;
esac
