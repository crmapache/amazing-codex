# Changelog

All notable changes to Amazing Codex GUI. The section for the version being built is what the
Marketplace and the IDE's update dialog show, so every release lists only its own changes.

This plugin began as a port of Amazing Claude Code GUI to OpenAI Codex; the history before 0.1.0
belongs to that plugin and lives in its own repository.

## [Unreleased]

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
