#!/usr/bin/env bash
# This fork's relay on this machine, at http://localhost:4450, serving the phone client built from this
# working tree - the same two things the image carries, with no server anywhere in the loop.
#
#   ./scripts/relay-dev.sh            # the phone's client is rebuilt, then the relay, then it runs
#   ./scripts/relay-dev.sh --no-web   # relay only, when only relay/src has moved
#
# It stays in the foreground; Ctrl-C stops it. Point a sandbox at it:
#
#   ./gradlew runIde -PopenProject=sandbox-project -PremoteRelay=ws://localhost:4450
#
# and open http://localhost:4450 in a browser on this machine for the phone's side. A browser counts
# localhost as a secure context, so pairing and the encryption work there exactly as on a phone. A real
# phone cannot reach this machine's loopback; for that the relay has to be deployed
# (scripts/relay-deploy.sh, which is the only script here that touches a server).
#
# Push notifications are off here - no VAPID pair in the environment - and the relay says so in
# /v1/push/key rather than failing. The port is this project's own in ~/.claude/ports.md and is taken
# strictly: something already listening there is a stale run to stop, not a reason to move.
set -euo pipefail

PORT=4450
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $PORT is already taken - see: lsof -nP -iTCP:$PORT -sTCP:LISTEN" >&2
  exit 1
fi

if [ "${1:-}" = "--no-web" ]; then
  "$HERE/scripts/relay-client.sh" --check
else
  "$HERE/scripts/relay-client.sh"
fi

echo "==> building the relay"
(cd "$HERE/relay" && pnpm build)

echo "==> http://localhost:$PORT - Ctrl-C stops it"
cd "$HERE/relay"
PORT="$PORT" RELAY_STATIC_DIR=./public exec node dist/index.js
