# Changelog

All notable changes to Amazing Codex GUI. The section for the version being built is what the
Marketplace and the IDE's update dialog show, so every release lists only its own changes.

## [Unreleased]

## [0.3.0] - 2026-10-09

- Added: rewind a conversation to one of your own messages, from the panel or a paired phone. "Rewind to here" removes that message and everything after it from Codex's memory and puts its text back in the input field. Choose the conversation, the code or both, or open the earlier point in a new tab and leave this one alone. Codex keeps no copies of files, so the code is put back by undoing the edits Codex itself made since that message: the dialog first shows which files and how many lines, and a file that something else changed since is named instead of being overwritten. A turn that is still running stops, queued messages from the discarded part are dropped, and the panel, the phone, the history and the search all show the same shortened conversation.
- Added: every conversation tab has its own fork button, so a complete copy opens without typing a command or selecting text.
- Added: a fork shows what it carries. The earlier conversation appears in the new tab with a "new branch from" marker under it, and the original's name in the marker opens it. Before, the new tab opened empty although Codex remembered everything. The same shows after an IDE restart, in a fork opened from the history and on the phone.
- Fixed: "Fork from here" copied the whole conversation, including the later turns it promised to leave behind. A fork now starts at the chosen point, and a fork of the whole conversation ends where it stood when you pressed the button, even if the original goes on working.
- Fixed: a conversation rewound in Codex's own terminal appeared in the history once for every rewind, and opened with the turns that had been rewound away. The history and the search now read a conversation the way Codex resumes it.
- Fixed: with two conversations waiting for a permission at once, Allow pressed on one card could answer the other conversation's request instead, at the desk and on the phone alike. Each conversation numbered its requests from the start, so the first request of every new conversation had the same name; requests are now named apart across the whole IDE.
- Fixed: writing a message while Codex waited on a question, a plan or a permission left the conversation stuck at "Waiting for you". A message written over a card is now Codex's answer to it: the card closes, Codex reads the message in the same step, and a command that was waiting for permission does not run.
- Fixed: on a paired phone, a question from Codex could only be answered with one of its options. You can now write your own answer, tick several options when the question allows it, or close the question and answer in the conversation, as at the desk.
- Fixed: a new conversation could not be started from the phone on a model added by hand, and a chat started from the phone showed its model, effort and mode only after the first message. The phone now offers the models added on that computer for every project, and a new conversation shows what it was started with from the first second.
- Fixed: the permission mode on the phone kept showing the old mode after it was changed at the desk.
- Fixed: text copied on an iPhone and pasted into Gmail could turn into a link full of "%20". Every copy button now puts plain text on the clipboard.
- Fixed: after reloading the panel, or opening a conversation on the phone, a long answer came back cut to its first eight thousand characters. Only what the tools returned is shortened now; everything you and Codex wrote comes back whole. On the phone, a message too large to send in one piece now says so and points to the IDE for the full text.
- Fixed: a permission request with a long command could grow taller than the panel and push its buttons off the screen. The command now scrolls inside the card.
- Fixed: with many conversations open, the open tab was hard to tell from the others. It now lights up in the colour of its group, on the dark and the light theme alike.
- Added: right-click a file or a folder in the project tree, or a changed file in the Commit tool window, and "Send to Amazing Codex GUI" puts it into the input field with its full path. Several selected files go in at once.
- Changed: removing an attachment from the input field takes the space after it along, so an emptied field shows its hint again.
- Added: the card of a running scenario shows its stage and a row of numbered circles, one per card of every pass, with the current one marked. The same road replaces the progress bar on the run's screen and on the phone.
- Fixed: the time of a scenario run included pauses, questions waiting for you and the hours an IDE stayed closed. The run's, each card's and each stage's time now count only the time the run actually worked.
- Changed: the table of past scenario runs shows when each run finished, and on a narrow panel each row takes two lines instead of squeezing the name away.
- Added: you can write to the main thread of a running scenario from a field at the bottom of the run's tab, on the phone too. Your words and the answer appear in the timeline under the card the run is on.
- Added: a star on a past scenario run, to mark it for yourself. The star is stored with the run, so a second window and the phone see it too.
- Changed: opening a scenario run starts where the run is, the card it is on and what the main thread last said to it, and keeps up with the run until you scroll away. The stage a run is in is shaded more brightly.
- Fixed: a scenario card that handed work to Codex's sub-agents was judged the moment it had started them. The run now waits until every such helper is done, and then asks the card for its report, since Codex does not hand a sub-agent's answer back by itself. Pausing a run stops the card's helpers too.
- Fixed: a scenario run kept working on the account it was started on after you chose another one. A run now follows your choice the way open chats do, and carries on where it stood.
- Fixed: when an account's usage limit refused a scenario run, the card was judged on the refusal and the run ended without a verdict while your other accounts had room. Now the run pauses and goes on on another account with room, or waits and carries on by itself when the first limit resets. An account the run moved to by itself that cannot take it is set aside for an hour.
- Fixed: going back in the side menu always started the screen from the top; each screen now comes back where you left it.
- Fixed: the Codex settings screen showed no experimental switches with Codex 0.160, which refused the panel's request for them. They are listed again.
- Fixed: on a Mac, pairing a second phone or tablet silently unpaired the first one. It stayed in the list of paired devices but could no longer connect, and pairing it again unpaired the other. Each device now keeps its own key. A device unpaired this way is told on its screen to pair again.
- Fixed: a phone paired within five minutes of starting the IDE could be forgotten if the IDE was then closed abnormally. It kept connecting and showing conversations, but its history, past scenario runs and search results never loaded, and it was missing from the list of paired devices, so it could not be revoked. Pairings and revocations are now saved immediately, and a device that is not on the list is told to pair again instead of being let in.
- Remote access: reload the phone client after updating the plugin to load this release's client.

## [0.2.6] - 2026-10-06

- Fixed: an installed plugin could appear ready while its bundled apps still needed sign-in, with no way to connect them. Plugin cards now show each app's account access and tool availability, with a Connect button that opens the address supplied by Codex. Sign-in stays marked as unconfirmed when Codex cannot verify it.
- Fixed: connecting an app in the browser could leave its tools stale in an open conversation. Returning to the IDE now refreshes app access and tools in that conversation. Cancelling stops waiting, and changing Codex accounts discards results from the previous account.
- Added: the phone's Plugins screen shows app states and directs account connection to the IDE.
- Remote access: reload the phone client after updating the plugin to load this release's client.

## [0.2.5] - 2026-10-05

- Fixed: links to local files appeared with their Markdown brackets and could not be clicked. Only web addresses were recognized; file links now open in the IDE, including paths with spaces, parentheses and line numbers. On the phone, the label and path stay readable text.
- Fixed: web links with parentheses could open an incomplete address. Parentheses inside a link are now kept as part of the address.
- Remote access: reload the phone client after updating the plugin to load this release's client.

## [0.2.4] - 2026-10-04

- Fixed: new chats and forks could silently switch to Auto instead of keeping the selected mode. A missing mode selection was treated as Auto; new chats now use the saved default and forks keep their parent's mode.
- Fixed: closing a project could report "Already disposed" while the panel was sending an update or repainting. Pending callbacks now stop before the embedded browser and its timers are released.
- Fixed: opening the panel just after starting the IDE could add an internal error about creating a proxy service. The IDE's proxy configuration is now read before the embedded browser starts.
- Remote access: reload the phone client after updating the plugin to load this release's client.

## [0.2.3] - 2026-10-04

- Added: a default context length in Settings - New chats, beside the model, effort and mode. Choose Standard or Long where the model supports it, or follow the last choice; new tabs show the same choice before the first message.
- Fixed: the plugin version scrolled out of view in the side menu. The footer now stays visible while the menu items scroll.
- Remote access: reload the phone client after updating the plugin to load this release's client.

## [0.2.2] - 2026-10-04

- Fixed: the panel showed "The panel hit an error" right after the IDE started, and reloading did not help. The button that offers another model when the chosen one is at capacity was wired in one step too late: the panel drew itself before the sign-in was known, and the moment it was, React counted one step more than the time before and refused to draw anything. The panel now opens as usual, and a check that runs with every build catches this kind of mistake before it can ship.

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

[Unreleased]: https://github.com/crmapache/amazing-codex/compare/0.3.0...HEAD
[0.3.0]: https://github.com/crmapache/amazing-codex/compare/0.2.6...0.3.0
[0.2.6]: https://github.com/crmapache/amazing-codex/compare/0.2.5...0.2.6
[0.2.5]: https://github.com/crmapache/amazing-codex/compare/0.2.4...0.2.5
[0.2.4]: https://github.com/crmapache/amazing-codex/compare/0.2.3...0.2.4
[0.2.3]: https://github.com/crmapache/amazing-codex/compare/0.2.2...0.2.3
[0.2.2]: https://github.com/crmapache/amazing-codex/compare/0.2.1...0.2.2
[0.2.1]: https://github.com/crmapache/amazing-codex/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/crmapache/amazing-codex/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/crmapache/amazing-codex/commits/0.1.0
