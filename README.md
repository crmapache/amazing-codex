# Amazing Codex GUI

An OpenAI Codex panel for JetBrains IDEs: a real chat with an input field and parsed
output, instead of a terminal session. The button lives on the side bar and can be
moved to any edge of the window.

🌐 What the plugin is, in your language:
[English](docs/marketplace/en.md) |
[简体中文](docs/marketplace/zh.md) |
[Русский](docs/marketplace/ru.md) |
[Українська](docs/marketplace/uk.md) |
[Español](docs/marketplace/es.md) |
[Português (Brasil)](docs/marketplace/pt.md) |
[Deutsch](docs/marketplace/de.md) |
[Français](docs/marketplace/fr.md) |
[日本語](docs/marketplace/ja.md) |
[한국어](docs/marketplace/ko.md)

The rest of this file is for people working on the plugin rather than using it.

This plugin is a port of [Amazing Claude Code GUI](https://github.com/crmapache/amazing-claude-code)
to Codex. The interface - the React panel, the phone client, the harness - is the same code;
what changed is the engine underneath it, which now drives `codex app-server` instead of the
Claude Code CLI. How the two fit together is described in `CLAUDE.md`.

## What it does that a terminal doesn't

Same agent, same account, same config - but you point at files instead of typing paths,
see what it is doing instead of guessing, and answer it without leaving the IDE. Runs in
every JetBrains IDE from 2026.1 on, **Android Studio included**.

- **Point at files, don't type them.** Drag one in, type `@` to pick it, paste a
  screenshot - each lands as a chip you cannot mistype.
- **Send code with its address.** Select lines, "Send to Amazing Codex GUI", and the agent
  reads the real file around them: `@src/useSocket.js (L12:5-L18:30)`, not a snippet with no
  context.
- **Grab any part of an answer.** Quote it into your next message, or fork the
  conversation from that exact point - the original stays as it was.
- **A long paste folds into one chip** as you paste it, and opens back up in the message
  you sent.
- **See what it is doing.** Commands with their duration, patches as diffs with real line
  numbers, the plan checklist ticking off, web searches, MCP tool calls, and what the turn
  cost in tokens.
- **No unexplained silence.** A request Codex is retrying becomes a card with the reason and
  the attempt; a used-up limit says which window and when it resets.
- **Nothing answers for you.** An approval, a plan or a question waits as long as it takes -
  no timeout, no auto-continue.
- **A side panel, not an editor tab**, on any edge of the window.
- **Conversations outlive the panel.** Collapse it, switch projects, come back - the agent
  kept working, and queued messages are still queued.
- **Model, reasoning effort and approval mode change mid-conversation**, per tab, without
  restarting anything.
- **The keyboard answers**: numbers pick an option, Shift+Tab cycles the mode, Escape
  stops the turn.
- **Your unsaved buffers** are written before a turn, and files the agent changed are
  re-read at once.
- **`!` runs a command in your own shell**, chips and all; the output travels with your
  next message, costing no turn and no approval.
- **Answer it from your phone** - off by default, paired by QR code, end to end encrypted,
  revocable in one tap.

## Privacy and transparency

The short version, in full in [PRIVACY.md](PRIVACY.md):

- **The agent runs on your machine.** The plugin starts the Codex CLI and talks to it over
  its standard input and output. No proxy in between and no server of ours your conversation
  passes through. Your sign-in belongs to Codex: the plugin never sends it anywhere.
- **No telemetry.** No analytics, no usage reporting, no account. With remote access off,
  the only thing that ever leaves the machine is a feedback report you write and send
  yourself.
- **Your config stays yours.** The approval policy and the sandbox a thread runs with are
  exactly the mode shown under the input field, and everything else - models, MCP servers,
  skills, custom prompts, project docs - comes from your own `~/.codex`.
- **Questions have no deadline.** No timer, no auto-answer: the turn stands still until a
  human decides.
- **Remote access is off** until you turn it on, and even then the relay carries sealed
  envelopes it cannot read.
- **Source available** under the Elastic License 2.0: everything above can be checked
  rather than believed.

## How it's built

Three layers, each with its own responsibility:

- **Kotlin shell** (`src/main/kotlin`) - registers the panel, hosts the embedded browser,
  starts one `codex app-server` per conversation and speaks its JSON-RPC. The events Codex
  sends are translated into the stream the panel already understands (the one Claude Code
  used to write), so everything above the engine - the feed, the phone, statistics,
  scenarios - carried over unchanged. The translation lives in `codex/CodexDialect.kt` and
  `codex/CodexStream.kt`.
- **React interface** (`webview/`) - everything the user sees. Receives agent events and
  decides on its own what to turn them into.
- **Bridge** - `window.__accSend` from the web to the shell, `window.__accReceive` back.
  The message format is described in `webview/src/protocol.ts`.

The built static assets are bundled into the plugin archive and served to the browser
through a custom scheme handler: it needs a real address, otherwise module scripts don't
work. The fonts ship alongside it - the panel never goes to the network for typography.

## Modes

The MODE chip picks one of five presets of Codex's approval policy and sandbox:

| Mode | Approval policy | Sandbox |
|---|---|---|
| Ask every time | `untrusted` | workspace-write |
| Auto | `on-request` | workspace-write |
| Read only | `on-request` | read-only |
| Plan | `on-request` | read-only, in Codex's plan mode |
| Full access | `never` | danger-full-access |

A change applies from the next turn of the conversation on screen. Plan mode ends its turn
with a plan card; approving it switches the conversation to Auto and starts the work.

## Commands

The field suggests Codex's own commands - `/compact`, `/review`, `/init`, `/new` (and
`/clear`) - the panel's (`/resume`, `/fork`, `/model`, `/effort`, `/login`, `/logout`), your
custom prompts from `~/.codex/prompts` as `/prompts:name`, and your skills. The app-server
has no slash commands of its own, so the IDE carries each of these out itself:
`/compact` is `thread/compact/start`, `/review` is `review/start`, a prompt is expanded
with its arguments, and a skill travels as a skill item beside the message.

## Login

The panel checks the sign-in at startup and, until it's done, shows only a login button.
Login happens in the IDE's built-in terminal with `codex login` - a ChatGPT sign-in in the
browser, or an API key. The panel asks Codex itself and closes the login screen as soon as
it succeeds.

Several accounts can live side by side: each one gets a Codex home of its own under
`~/.amazing-codex/accounts/`, holding its `auth.json` and links to everything else in
`~/.codex`, so history, config and skills stay shared.

## Forks

Select a piece of a reply - "Fork from here" (or ⌥B) appears right above the selection. The
panel branches the conversation with Codex's `thread/fork`: the agent gets the whole thread
up to that point but continues in a new one, while the original stays exactly as it was.

## Conversation history

The "History" button in the header (and the `/resume` command) opens this project's past
Codex threads, terminal ones included. The list comes from `thread/list` filtered by the
project folder; a thread opens at its end and earlier turns are paged in on demand, read
with `thread/turns/list` and replayed into the feed.

## Shell commands

A message starting with `!` isn't a message at all - it's a command: `!git status`,
`!pnpm test`. The panel runs it itself, through your own login shell and in the project
directory. The output lands as a card in the feed and travels to the agent attached to your
next message - looking something up costs no turn and no approval, but the agent still sees
what you saw. There is no input, so anything that asks a question gets end-of-file, and
there is a two-minute limit.

## Running it

You'll need JDK 21, pnpm, and Codex installed (`npm install -g @openai/codex`, `codex` on
your PATH) and signed in.

```bash
# builds the plugin and opens a separate test copy of WebStorm on sandbox-project
./scripts/sandbox.sh

# the same by hand
./gradlew runIde -PopenProject=sandbox-project

# the interface from the dev server: edits show up without rebuilding the plugin
cd webview && pnpm dev          # in a separate terminal, port 5190
./gradlew runIde -PopenProject=sandbox-project -PwebviewDevUrl=http://localhost:5190/
```

The build log of the sandbox lives in `build/sandbox.log`. Browser dev tools are `⌘⇧D`
inside the panel.

## Testing

```bash
./gradlew test            # the engine, the translation, accounts, history, scenarios
cd webview && pnpm test   # the feed, the menus, the dictionaries
./gradlew buildPlugin     # archive in build/distributions
```

One test talks to a real Codex and spends real turns on the signed-in account, so it is off
unless asked for: put a cheap model's id into `/tmp/acx-live-codex` and run
`./gradlew test --tests '*CodexSessionLiveTest*'`. It raises a conversation, runs tools,
answers an approval, resumes the thread in a new process and reads it back from the history.

The harness (`cd webview && pnpm dev`, then `http://localhost:5190/harness.html`) plays
ready-made scenarios through the real panel without an agent or an IDE.

## Remote access from your phone

Off by default. When you turn it on, a paired phone can read your conversations and answer
them. Three steps: turn it on in the panel's menu (**Remote access**), scan the QR code with
your phone, confirm the pairing in the IDE. What a phone may and may not do is enforced on
your machine (`remote/RemoteCommands.kt`), and the relay carries sealed envelopes it cannot
read. See `PRIVACY.md`.

## Telling the author something

The speech bubble beside the heart opens a form: a bug, an idea, or nothing in particular,
with an optional address, files, and a technical debug report you can read in full before
sending. See `PRIVACY.md` for exactly what it contains.

## License

Source-available under the [Elastic License 2.0](LICENSE). You can read, use, modify, and
redistribute the code. You can't offer it to others as a hosted or managed service, and you
can't strip out the license, copyright, or authorship notices.

The name, the logo, and the plugin ID are not part of that license: a fork ships under its
own name and its own ID. See [TRADEMARK.md](TRADEMARK.md).
