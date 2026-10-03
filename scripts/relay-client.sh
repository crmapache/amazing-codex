#!/usr/bin/env bash
# The phone's client, built from this working tree and staged where the relay serves it from
# (relay/public), then checked to be this fork's client rather than the Claude project's.
#
#   ./scripts/relay-client.sh           # build, stage, check
#   ./scripts/relay-client.sh --check   # check what is already staged, build nothing
#
# Both relay scripts run it first (relay-dev.sh, relay-deploy.sh). The check is the reason it is a
# script of its own: the relay's image carries whatever relay/public holds, and the two projects build
# the same client under different names, so a dist-mobile copied in from the wrong checkout would put
# the other plugin's client on this fork's phones without a single error anywhere.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLIC="$HERE/relay/public"

fail() {
  echo "relay/public: $1" >&2
  exit 1
}

if [ "${1:-}" != "--check" ]; then
  echo "==> building the phone's client"
  (cd "$HERE" && pnpm build:mobile)

  # Emptied rather than copied over: a file that has left the build would otherwise stay and be served.
  # The .gitkeep is what keeps the empty folder in git (see the root .gitignore), so it comes back.
  rm -rf "$PUBLIC"
  mkdir -p "$PUBLIC"
  : >"$PUBLIC/.gitkeep"
  cp -R "$HERE/webview/dist-mobile/." "$PUBLIC/"
fi

[ -f "$PUBLIC/index.html" ] || fail "no client is staged - run this without --check"

grep -q '<title>Amazing Codex GUI</title>' "$PUBLIC/index.html" ||
  fail "the shell is not this fork's client (its <title> is not Amazing Codex GUI)"

grep -q '"name": "Amazing Codex"' "$PUBLIC/manifest.webmanifest" 2>/dev/null ||
  fail "the manifest is missing or is not this fork's (its name is not Amazing Codex)"

if grep -qs 'Amazing Claude Code' "$PUBLIC/index.html" "$PUBLIC/manifest.webmanifest" "$PUBLIC/sw.js"; then
  fail "the Claude project's name is in the shell, the manifest or the service worker"
fi

# The privacy page is built from PRIVACY.md beside the client (see vite.mobile.config.ts), and the
# plugin links to it by address: a relay without it answers that link with the phone's shell.
grep -qs 'Privacy - Amazing Codex GUI' "$PUBLIC/privacy/index.html" ||
  fail "no privacy page, or not this fork's (is PRIVACY.md at the root of the repository?)"

echo "==> relay/public holds this fork's client: $(grep -o 'assets/mobile-[A-Za-z0-9_-]*\.js' "$PUBLIC/index.html" | head -1)"
