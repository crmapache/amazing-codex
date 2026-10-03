#!/usr/bin/env bash
# Put what is in this working tree onto this fork's relay - wss://relay-codex.mzpizote.com - together
# with the phone's client built from the same tree.
#
#   ./scripts/relay-deploy.sh            # the phone's client is rebuilt, then the server
#   ./scripts/relay-deploy.sh --no-web   # server only, when only relay/src has moved
#   ./scripts/relay-deploy.sh --yes      # without the question, for a run nobody is watching
#
# Phones are paired with this relay. A deploy drops every live socket for a moment (both ends reconnect
# and catch up from the IDE's journal), and the client it carries reaches every phone on its next load -
# so the script asks before it does anything on the server.
#
# This relay lives on the same server as the Claude project's two (relay.mzpizote.com and
# relay-dev.mzpizote.com) and is deployed the same way: the sources are copied there, the image is built
# there and pushed to the registry on that machine, and Coolify pulls it. What keeps this script off
# the other two is that nothing it uses is theirs - its own directory on the server, its own image name -
# and a check, made before anything is built, that the Coolify application it is about to redeploy pulls
# that image and answers on this fork's domain. A uuid copied from one of the Claude relays is refused,
# not redeployed.
set -euo pipefail

DOMAIN=relay-codex.mzpizote.com
SERVER=root@40.160.85.25
# The Coolify application, made once by hand - see "First deploy" in relay/README.md. Empty until it
# exists, and the script will not run on an empty one.
APP_UUID=""
REMOTE_DIR=/root/apps/acx-relay
IMAGE_NAME=127.0.0.1:5000/acx-relay
IMAGE_TAG=local
COOL_DIR="$HOME/Documents/railway-migration/scripts"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WEB=1
ASK=1
for arg in "$@"; do
  case "$arg" in
    --no-web) WEB=0 ;;
    --yes) ASK=0 ;;
    *)
      echo "unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

if [ -z "$APP_UUID" ]; then
  echo "APP_UUID is empty: the Coolify application for $DOMAIN has not been made yet." >&2
  echo "See \"First deploy\" in relay/README.md, then put its uuid at the top of this script." >&2
  exit 1
fi

echo "==> checking that $APP_UUID is this fork's relay"
python3 - "$COOL_DIR" "$APP_UUID" "https://$DOMAIN" "$IMAGE_NAME" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from cool import call

uuid, url, image = sys.argv[2], sys.argv[3], sys.argv[4]
app = call('GET', f'/applications/{uuid}') or {}
domains = [d.strip().rstrip('/') for d in str(app.get('fqdn') or '').split(',') if d.strip()]
pulls = str(app.get('docker_registry_image_name') or '')

if url not in domains or pulls != image:
    print(f'refusing: application {uuid} answers on {domains or "nothing"} and pulls {pulls or "nothing"},', file=sys.stderr)
    print(f'while this script deploys {image} to {url}.', file=sys.stderr)
    sys.exit(1)
PY

if [ "$ASK" = 1 ]; then
  if [ ! -t 0 ]; then
    echo "Nobody to ask on this terminal - run with --yes to deploy without the question." >&2
    exit 1
  fi
  read -r -p "Deploy this working tree to $DOMAIN, where real phones are paired? [y/N] " answer
  [ "$answer" = "y" ] || [ "$answer" = "Y" ] || exit 1
fi

if [ "$WEB" = 1 ]; then
  "$HERE/scripts/relay-client.sh"
else
  "$HERE/scripts/relay-client.sh" --check
fi
BUNDLE="$(grep -o 'assets/mobile-[A-Za-z0-9_-]*\.js' "$HERE/relay/public/index.html" | head -1 || true)"

echo "==> packing the sources"
# COPYFILE_DISABLE keeps macOS from packing its own metadata beside every file - those turn into
# "._name" files inside the image and are served as if they were the client's.
(cd "$HERE/relay" && COPYFILE_DISABLE=1 tar czf /tmp/acx-relay.tgz --exclude=node_modules --exclude=dist .)
scp -q /tmp/acx-relay.tgz "$SERVER:/root/apps/acx-relay.tgz"
rm -f /tmp/acx-relay.tgz

echo "==> building the image on the server"
# The directory is emptied before unpacking, and that is not tidiness: the archive lands on top of what
# is already there, so a file that has left the build would otherwise stay in the image for good. It
# holds nothing but what this script puts there - the relay's settings live in Coolify, not in a file.
ssh "$SERVER" "rm -rf $REMOTE_DIR && mkdir -p $REMOTE_DIR && cd $REMOTE_DIR && \
  tar xzf ../acx-relay.tgz 2>/dev/null && rm -f ../acx-relay.tgz && \
  docker build -q -t $IMAGE_NAME:$IMAGE_TAG . && docker push -q $IMAGE_NAME:$IMAGE_TAG" >/dev/null

echo "==> deploying"
# Waits for Coolify to say the deployment has finished, so the checks below meet the new container
# rather than the old one still answering. When the answer has no deployment id to follow, the checks
# below are what is left - they still name the bundle that answered.
python3 - "$COOL_DIR" "$APP_UUID" <<'PY'
import sys, time
sys.path.insert(0, sys.argv[1])
from cool import call

answer = call('POST', f'/deploy?uuid={sys.argv[2]}&force=true') or {}
try:
    deployment = answer['deployments'][0]['deployment_uuid']
except (KeyError, IndexError, TypeError):
    print('Coolify did not name the deployment - not waiting for it', file=sys.stderr)
    sys.exit(0)

for _ in range(72):
    time.sleep(5)
    try:
        status = str((call('GET', f'/deployments/{deployment}') or {}).get('status', ''))
    except SystemExit:
        continue
    if status == 'finished':
        sys.exit(0)
    if status.startswith('failed') or status.startswith('cancelled'):
        print(f'deployment {deployment}: {status} - see its log in Coolify', file=sys.stderr)
        sys.exit(1)

print(f'deployment {deployment} has not finished in six minutes - see its log in Coolify', file=sys.stderr)
sys.exit(1)
PY

for _ in $(seq 1 30); do
  if [ "$(curl -sS -m 10 "https://$DOMAIN/healthz" 2>/dev/null || true)" = "ok" ] &&
    curl -sS -m 10 "https://$DOMAIN/manifest.webmanifest" 2>/dev/null | grep -q '"name": "Amazing Codex"'; then
    SERVED="$(curl -sS -m 10 "https://$DOMAIN/" 2>/dev/null | grep -o 'assets/mobile-[A-Za-z0-9_-]*\.js' | head -1 || true)"
    if [ "$SERVED" = "$BUNDLE" ]; then
      echo "==> up: $DOMAIN serves $SERVED"
      exit 0
    fi
  fi
  sleep 6
done

echo "$DOMAIN did not come up with $BUNDLE in three minutes - look at the deploy log" >&2
exit 1
