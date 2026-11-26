# The feedback service

It used to take what somebody wrote on the panel's feedback screen and put it in the author's Telegram.
It no longer forwards anything. The plugin's feedback screen stopped taking messages and now points to
the two ways a change gets in: a tip on Ko-fi with the wish written in the message, or a pull request.
This service stays up for the copies of the plugin that still have the old form, so that the people
using them read the same answer instead of a connection error.

Licensed Elastic-2.0, like the plugin.

## What it does

Every `POST /v1/feedback` from the old form is answered `400` with one line of text:

> Messages here are closed. Want a change? Tip on ko-fi.com/mzpizote with what you'd like and I'll build
> it, or open a pull request on GitHub.

A `400` because it is the only answer whose body the old form prints as it is (`FeedbackSender.kt`
takes the first 160 characters of it), so the line has to stay under that and on one line. The test holds
both.

The body is read to its end and thrown away before the answer goes out. The old form writes its whole
request, attachments included, before it reads anything, and an answer sent mid-body would reach it as a
broken pipe and show "no answer from the feedback service" instead. Past `FEEDBACK_MAX_BODY_BYTES` the
request is dropped: nothing the old form can send is that big.

Nothing is kept and nothing is forwarded. The log says that one request was turned away and how many
bytes it carried, never a word of what was in it.

## Endpoints

| | |
|---|---|
| `POST /v1/feedback` | `400` with the line above; `403` when the shared secret does not match |
| `GET /healthz` | `ok`, for whatever is watching |
| `GET /v1/info` | Version, and `"accepting": false` |

## Configuration

| Variable | Default | What it is |
|---|---|---|
| `PORT` | `4451` | |
| `FEEDBACK_KEY` | — | The shared secret the old plugin sends. Empty means every caller is answered |
| `FEEDBACK_MAX_BODY_BYTES` | 24 MB | A little over what the old form could send; anything bigger is dropped |
| `FEEDBACK_LOG_LEVEL` | `info` | `silent` for nothing at all |

`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `FEEDBACK_PER_IP_PER_HOUR`, `FEEDBACK_PER_HOUR`,
`FEEDBACK_MAX_FILES`, `FEEDBACK_MAX_CONCURRENT` and `FEEDBACK_TRUSTED_PROXIES` are no longer read. They
can be removed from the deployment, and the bot itself can be deleted in @BotFather.

## Run it

```
pnpm build && node dist/index.js
```

## How the public one is deployed

It runs beside the relay, on the same server under Coolify, at `feedback.mzpizote.com`. As with the
relay there is no build from git to hook up: the sources are copied to the server, the image is built
there, and Coolify pulls it from a registry running on the same machine.

```
# 1. The sources, as they are. COPYFILE_DISABLE keeps macOS from packing its own metadata beside every
#    file - those turn into "._name" files inside the image.
cd feedback-service
COPYFILE_DISABLE=1 tar czf /tmp/feedback.tgz --exclude=node_modules --exclude=dist .
scp /tmp/feedback.tgz root@<server>:/root/apps/

# 2. The image, built on the server and pushed to the registry running there.
ssh root@<server> 'mkdir -p /root/apps/acc-feedback && cd /root/apps/acc-feedback && rm -rf dist src && \
  tar xzf ../feedback.tgz && docker build -t 127.0.0.1:5000/acc-feedback:local . && \
  docker push 127.0.0.1:5000/acc-feedback:local'

# 3. The deploy itself, through Coolify's API.
python3 cool.py POST '/deploy?uuid=<uuid of acc-feedback>&force=true'
```

After a deploy, `curl https://feedback.mzpizote.com/v1/info` should say `"accepting": false`.

When no copy of the plugin with the old form is in use any more, the service can simply be removed.
