# Amazing Codex GUI - for agents working on this repository

Read `CLAUDE.md` first: it is the project's working notes (in Russian) - the rules of the project, how
the plugin drives `codex app-server`, how Codex's events are translated for the panel, and how to check a
change. `docs/panel-notes.md` holds the inherited notes about the panel itself.

Two rules that matter before anything else:

- Do not create git commits without explicit permission for each one.
- Stop only the processes you started, by PID - never `pkill -f <word>`.
