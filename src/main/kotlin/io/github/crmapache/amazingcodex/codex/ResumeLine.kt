package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.codex.accounts.AccountStore

/**
 * The line `/status` offers for continuing a conversation in a terminal (see SessionInfoRow): `codex resume <id>`,
 * and for a tab on an added account, that account's drawer in front of it.
 *
 * Why the drawer has to be said at all. An added account is a Codex home of its own,
 * `~/.amazing-codex/accounts/<id>/`: its credential, and links to the ordinary home for everything else (see
 * AccountDrawer). `codex resume <id>` in a terminal of one's own finds the thread - the conversations are the
 * ordinary home's - and carries it on as whoever the ordinary home's credential names: the default sign-in, not the
 * account the tab is on. Silently: the conversation answers, and the turns are billed to the other subscription.
 *
 * On macOS and Linux the line names the drawer the way the panel's own processes are given it (see
 * AccountStore.environmentFor): `CODEX_HOME` and `CODEX_SQLITE_HOME` before the command, and the keys that would
 * outrank the drawer cleared for that one command - those the shell this IDE reads carries (see [of]); the others
 * have nothing to outrank it with. `NAME=value command` is one form for every shell a terminal opens there - sh,
 * bash, zsh, fish since 3.1 - and it sets the variables for that command alone: nothing stays behind in the terminal.
 * A shell that cannot read the form (csh, an old fish, PowerShell) refuses the whole line out loud rather than run
 * Codex on the default sign-in. That is the difference from the sign-in terminal, which types its environment
 * instead of writing it (see AccountTerminal): there a wrong guess is a sign-in into the wrong drawer, here it is an
 * error on the screen.
 *
 * On Windows there is no such form. PowerShell's `$env:` and cmd's `set` stay set for the rest of the terminal - the
 * next plain `codex` typed there would be billed to the account, as silently as the other way round - and each is an
 * error in the other. The IDE cannot know which one the line will be pasted into, so it does not guess: the line stays
 * plain, and the row says plainly that it continues on the default sign-in rather than on the tab's account. The same
 * for a phone, which is not handed the machine's paths (see RemoteFeed), and for an account whose drawer would not
 * resolve - a tab on it cannot even run.
 */
internal object ResumeLine {

    /** What the row says about the terminal. */
    data class Line(
        /** The whole line, when it is more than the plain `codex resume <id>` the panel writes by itself. */
        val command: String?,
        /** The added account the tab runs on - its name, for the row's note. Null for the default sign-in. */
        val account: String?,
        /** The line continues on the default sign-in rather than on [account] - see the file's note. */
        val onDefault: Boolean,
    )

    /**
     * The line for [conversationId] on [account] (its name; null for the default sign-in, and empty when it has
     * none). [homes]: the account's own Codex home and the ordinary one, as a process of it gets them - null when
     * they are not to be named (a phone asked) or would not resolve. [carried]: which of the keys that outrank a
     * drawer the environment carries - those the line clears.
     */
    fun of(conversationId: String, account: String?, homes: Pair<String, String>?, posix: Boolean, carried: Collection<String>): Line {
        if (account == null) return Line(command = null, account = null, onDefault = false)

        val named = account.ifBlank { null }
        if (homes == null || !posix) return Line(command = null, account = named, onDefault = true)

        val (drawer, home) = homes
        val prefix = buildList {
            add("${AccountStore.STORE_VARIABLE}=${word(drawer)}")
            add("${AccountStore.SQLITE_VARIABLE}=${word(home)}")
            AccountStore.OUTRANKING_VARIABLES.filter { it in carried }.forEach { add("$it=") }
        }
        return Line(command = (prefix + plain(conversationId)).joinToString(" "), account = named, onDefault = false)
    }

    /** `codex resume <id>` - what the panel writes when the IDE names no line of its own (see feed/sessionInfo.ts). */
    fun plain(conversationId: String): String = "codex resume ${word(conversationId)}"

    /**
     * One word for a POSIX shell: as it is when nothing in it means anything to the shell, single-quoted otherwise.
     * A thread's id and a drawer's path are almost always the first kind, and a line with quotes nobody needed reads
     * as something to be careful of.
     */
    internal fun word(text: String): String =
        if (text.isNotEmpty() && text.all { it.isLetterOrDigit() && it.code < 128 || it in PLAIN }) text
        else "'" + text.replace("'", "'\\''") + "'"

    private const val PLAIN = "-_./:@%+,"
}
