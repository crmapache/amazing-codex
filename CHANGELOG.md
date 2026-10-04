# Changelog

All notable changes to Amazing Codex GUI. The section for the version being built is what the
Marketplace and the IDE's update dialog show, so every release lists only its own changes.

## [Unreleased]

## [0.2.1] - 2026-10-04

- Added: a context window selector beside the model and effort menus, for models whose provider offers a larger window. Switching to "Long" trades a bigger window for faster use of plan limits; a model with no larger window says so instead of offering it.
- Added: switching a conversation to full access answers a command, file or extra-permission request that was already waiting, instead of leaving it stuck on the mode that asked it.
- Added: Codex now reads this project's own rules, agents, skills and other AI configuration the same way Claude Code does, instead of working from its defaults alone.

## [0.2.0] - 2026-10-02

- Added: side questions with `/btw` or `/side`, the way Codex's own terminal asks them. Type one at any moment, even while Codex is working, and the answer comes in a card above the input field without interrupting the turn. The question goes to a temporary copy of the conversation that knows everything the conversation does, may read and search files but changes nothing, and is thrown away afterwards: the agent never sees the question and nothing is saved. Follow-up questions keep the thread.
- Added: the file open in the editor and the lines selected in it go with each message, in the form Codex's own `/ide` uses. A chip beside the paperclip shows what will go along and leaves it out of one message when clicked; a line under a sent message says what went with it. Settings - "Send the editor along" switches it off.
- Added: a screen for Codex's own settings, opened by `/config` or from Settings. It reads Codex's config.toml the way Codex does, shows where each value comes from, locks what the project or your organization sets, and writes changes into the same file a terminal reads. A value written but overruled by the project says so. `/config key=value` writes one setting from the input field.
- Added: trusting a project from the panel. Codex reads a project's own `.codex/config.toml`, hooks and exec policies only once the project is trusted, and until now the panel never asked. The settings screen shows whether this project has Codex settings of its own and whether they are read, with a button to trust it; a conversation in an untrusted project with settings of its own says so once, in its feed.
- Added: when a project's own settings demand another kind of sign-in than the chosen account has, the feed and the sign-in screen say so by name, instead of offering a sign-in that would change nothing.
- Added: tabs and what was typed in them come back after the IDE restarts, in their order and with their model, effort and mode. Nothing starts until a tab is shown or written into. Settings - "Tabs on start" switches it off.
- Added: renaming a tab by double-clicking its name or with `/rename`. The name goes into Codex's own record, so the history, the search and `codex resume` show it too, and a name you gave is never replaced by a generated one.
- Added: a light theme that follows the IDE by default, and a text size of the panel's own.
- Added: switches for each indicator around the input field, including the ring of a business seat's spending limit.
- Added: rings for Codex's extra limits beside the plan's five-hour and weekly ones, on plans that have them.
- Added: scenarios are reordered by dragging, can let the main thread finish a card its own session could not, and show their cards' text as formatted text. Conversations raised by scenario runs no longer fill the history and the search.
- Added: a tab that called you with a sound glows until you look at it.
- Added: a message waiting in the queue can be edited.
- Added: anonymous usage statistics, off until you press Allow on the card that asks once. Settings - "Usage statistics" shows the whole report and switches it off, which also deletes what was sent. The privacy policy lists every field.
- Added: an opening splash with the plugin's new mark.
- Changed: the plugin has its own mark (ACX) and its own relay for remote access. Phones paired before this version have to be paired again.
- Changed: per-turn settings the panel sends now follow your config.toml: network access and extra writable folders of the workspace sandbox, and the reasoning summary.
- Changed: "Default" in the model menu names the model your config.toml sets, when it sets one, and "auto" effort names its effort.
- Changed: on the phone, answering a plan shows the plan itself rather than the last thing the agent said before it.
- Fixed: a plan arrived without its Approve & run and Keep planning buttons, so plan mode could not be approved from the panel or the phone.
- Fixed: Stop wiped the part of the answer that had already been written.
- Fixed: the findings of `/review` appeared twice.
- Fixed: a fork took a generated name over the one it inherited from its conversation.
- Fixed: `/compact` was captioned as an automatic compaction.
- Fixed: a turn the tab or the phone only saw the end of was captioned with the time it had watched rather than the time it took.
- Fixed: on the phone, a business seat's account showed two empty limits instead of its spending limit.
- Fixed: on the phone, "load earlier messages" did nothing in long conversations written in Russian or another non-Latin script.
- Fixed: a usage update about an extra limit could overwrite the five-hour and weekly rings.
- Fixed: "the model picked is not the one answering" could appear on a tab left on Default.
- Fixed: a question with options and its answer were missing from a conversation opened from the history, and a conversation that ended on a question lost it. It now comes back as a card to answer.
- Fixed: signing in to an account in a drawer that held another account could later delete that drawer. A drawer is now replaced only when it confirms it holds the same account, and an API key is told apart from another key ending in the same characters.
- Fixed: the same account could stand on the accounts screen twice. Duplicates merge once the remaining sign-in has proved it works, so a revoked sign-in never wins over a working one.
- Fixed: the limit rings could stand still in one project while another one was working; every open project now shows the same picture of an account.
- Fixed: a new tab's mode was read from a project's settings even when the project was not trusted, so the mode shown was not the one Codex used.

## [0.1.0] - 2026-09-19

- Added: the panel drives the Codex CLI through `codex app-server` - one process per conversation,
  speaking Codex's own JSON-RPC. Messages, reasoning summaries, commands, patches, MCP tool calls, web
  searches, image views, plans and the plan checklist all land as the panel's cards.
- Added: edits are drawn from the patch Codex applied, hunk by hunk, with the real line numbers.
- Added: approvals for commands, patches and extra permissions, questions from the agent, and MCP
  server questions - each waits for a person, with "allow for this session" where Codex offers it.
- Added: five modes under the input field, each one a preset of Codex's approval policy and sandbox:
  ask every time, auto, read-only, plan, and full access. Plan mode ends with a plan to approve, and
  approving it switches the conversation to auto and starts the work.
- Added: the model and reasoning-effort menus come from Codex's own catalogue, and the effort menu
  offers exactly the levels of the chosen model.
- Added: a message written while a turn is running is steered into that turn.
- Added: Codex's commands in the field - `/compact`, `/review`, `/init`, `/new` and `/clear` - along with
  custom prompts (`/prompts:name`) and skills.
- Added: history, forks and resuming read Codex's own threads, terminal ones included, opened from the
  end and paged back on demand.
- Added: several Codex accounts on one machine, each with its own sign-in, sharing history, config and
  skills; the rings show each account's limits.
- Added: search, prompt improvement, conversation titles and scenario writing run as short-lived,
  read-only Codex threads that leave nothing in the history.

[Unreleased]: https://github.com/crmapache/amazing-codex/compare/0.2.1...HEAD
[0.2.1]: https://github.com/crmapache/amazing-codex/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/crmapache/amazing-codex/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/crmapache/amazing-codex/commits/0.1.0
