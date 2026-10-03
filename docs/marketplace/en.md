# Amazing Codex GUI

**OpenAI Codex as a chat panel in your JetBrains IDE.** Cards instead of terminal scrollback,
files you point at instead of paths you type, and your code right next to it.

It drives the Codex CLI already on your machine, so your ChatGPT sign-in or API key, models,
config, MCP servers, skills and custom prompts all come with it. No proxy, no account of ours.

🌐 **English** | [简体中文](zh.md) | [Русский](ru.md) | [Українська](uk.md) | [Español](es.md) | [Português (Brasil)](pt.md) | [Deutsch](de.md) | [Français](fr.md) | [日本語](ja.md) | [한국어](ko.md)

## Why this one

- **A round of work, written once and run for you.** Scenarios: a few cards, each a Codex thread
  of its own - implement, review, fix, run the tests - in stages that can go round more than once,
  with a main thread walking them and judging what each one found. Run one by button, three at once
  against three tickets, or on a clock at nine every weekday with its questions answered in advance.
  Describe the round in a sentence and Codex reads the project and writes the form.
- **The whole panel from your phone, not just a "yes" button.** Answer an approval or a plan, open
  a project that is closed, read yesterday's chat, fork, change the model and the effort, switch
  the account, watch a scenario run and unblock it. Off by default, paired by QR code, end-to-end
  encrypted through a relay that cannot read a word, revoked in one tap.
- **Several Codex accounts, switched in one click.** Work and personal on one machine without
  signing out of either - each keeps its own sign-in, while history, config and skills stay
  shared. Every row shows what is left of that account's limits, and Select moves every open chat
  onto it.
- **Search across every conversation of the project.** Prefixes, typos, word stems, phrases in
  quotes; this chat or all of them, with a jump straight to the message in its chat. When words are
  not enough, describe what you are looking for and Codex reads the conversations for you.
- **Everything it does is on screen.** Every command with its duration, every patch as an open
  diff with real line numbers, the plan ticking off, web searches, MCP tool calls, and what the
  turn cost in tokens. A retried request or a used-up limit is a card with the reason and the
  countdown, not silence.
- **Nothing answers for you, and nothing is lost.** An approval, a plan or a question waits as
  long as it takes - no timeout, no auto-continue. Conversations keep going with the panel
  collapsed or the project switched, and messages written during a turn are steered into it or
  wait in a queue the IDE keeps.
- **Android Studio included**, along with every JetBrains IDE from 2026.1 on.

## Also in the panel

- **Point at files, do not type them.** Drag one in, type `@` to pick it, paste a screenshot or a
  long log - each lands as a chip you cannot mistype.
- **Send code with its address.** Select lines, "Send to Amazing Codex GUI", and the agent reads
  the real file around them instead of a snippet with no context.
- **Paths open files.** A path anywhere in the conversation - the head of a card, an answer, an
  error, your own message - opens the file in the editor at the line it names; an edit opens on
  the edit itself.
- **Grab any part of an answer.** Quote it into your next message, fork the conversation from
  that exact point, pin up to three messages above the chat, or take a sent message back into the
  field to fix and resend.
- **Model, reasoning effort and approval mode change mid-conversation**, per tab, without
  restarting anything: ask every time, auto, read-only, plan, or full access. The effort menu
  offers exactly the levels the chosen model has.
- **MCP servers and plugins** on screens of their own: which server is up, which wants a sign-in,
  which fell over and why.
- **History** of this project's past Codex threads, terminal ones included, opened from the end
  and paged back on demand.
- **Codex's own commands** - `/compact`, `/review`, `/init`, `/new`, your custom prompts and
  skills - in the field's suggestions.
- **Side questions** with `/side` or `/btw`: ask while Codex is working, and the answer comes in
  a card above the field - the conversation never sees it.
- **Codex's own settings** on a screen of their own (`/config`): what your config.toml says and
  where each value comes from, and trusting a project in one click.
- **`!` runs a command in your own shell**, and the output travels with your next message,
  costing no turn and no approval.
- **Improve prompt** - the sparkle rewrites your draft in a run of its own, costing your
  conversation no context, and one button puts your words back.
- **Voice input** with a Deepgram key of your own: hold a hotkey, even from the editor.
- **Sound alerts** for the moments worth one, and only when you are not already looking.
- **Statistics** of hours, habits and achievements, shareable as a picture.
- **Ten languages**, following your IDE by default.
- **Your unsaved buffers** are written before a turn, and files the agent changed are re-read at
  once.
- **A side panel, not an editor tab**, on any edge of the window; numbers pick an option,
  Shift+Tab cycles the mode, Escape stops the turn.

## Privacy and transparency

- **Everything runs on your machine.** No proxy, no server of ours in the middle. Your Codex
  sign-in belongs to the CLI - the plugin never sends it anywhere and never hunts for API keys on
  your disk.
- **Nothing leaves without your say.** No account and no analytics behind your back. Anonymous
  usage statistics stay off until you press Allow on a card that asks once - counts only, never
  code, messages or file names. With them off and remote access off, the only thing that ever
  leaves is a feedback report you write and send yourself - and one button shows its exact text
  first.
- **Your rules stay yours.** Codex applies your config, its sandbox and its approval policy; the
  mode on screen is exactly the policy the thread runs with, and the plugin never starts a thread
  in a laxer one.
- **Source available** on GitHub under the Elastic License 2.0, and the
  [privacy policy](https://github.com/crmapache/amazing-codex/blob/main/PRIVACY.md) lists
  everything that can leave the machine.

## Requirements

Codex CLI installed (`npm install -g @openai/codex`) and signed in, and any JetBrains IDE from
2026.1 on, Android Studio included. Android Studio has no embedded browser of its own, so the IDE
offers to install JetBrains' browser plugin alongside this one.

## Links

- [Source code](https://github.com/crmapache/amazing-codex)
- [Report a bug or ask for a feature](https://github.com/crmapache/amazing-codex/issues), or use
  the form in the panel
- [Privacy policy](https://github.com/crmapache/amazing-codex/blob/main/PRIVACY.md)
