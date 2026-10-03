package io.github.crmapache.amazingcodex.codex

/**
 * Where to look for the Claude Code executable - the whole search, with not a single touch of the real
 * file system, environment or current OS.
 *
 * Kept apart for exactly that reason: the search breaks where a developer cannot reach - someone
 * else's Windows, an unusual install location, a PATH the IDE sees differently from the terminal.
 * Checking a hunch like "would claude.cmd from npm be found" without owning that machine is only
 * possible this way: feed its environment in here and look at it through a test.
 */
internal object CodexLookup {

    /** File names: on Windows there are several, and which one is there depends on the installer. */
    fun executableNames(windows: Boolean): List<String> =
        if (windows) listOf("codex.exe", "codex.cmd", "codex.bat", "codex") else listOf("codex")

    /**
     * Where Codex lands when it was installed by one of the usual roads but the IDE's PATH does not say
     * so - an IDE started from the Dock does not read the shell's profile.
     *
     * npm (the official install) puts it beside node; Homebrew's cask under /opt/homebrew or /usr/local;
     * the standalone installer and Cargo into the home directory.
     */
    fun fallbackPaths(windows: Boolean, home: String, env: Map<String, String>): List<String> {
        if (windows) {
            val appData = env["APPDATA"].orEmpty()
            val localAppData = env["LOCALAPPDATA"].orEmpty()

            return listOfNotNull(
                appData.takeIf { it.isNotBlank() }?.let { "$it\\npm\\codex.cmd" },
                localAppData.takeIf { it.isNotBlank() }?.let { "$it\\Programs\\codex\\codex.exe" },
                localAppData.takeIf { it.isNotBlank() }?.let { "$it\\codex\\codex.exe" },
                "$home\\AppData\\Roaming\\npm\\codex.cmd",
                "$home\\.local\\bin\\codex.exe",
                "$home\\.cargo\\bin\\codex.exe",
                "$home\\scoop\\shims\\codex.exe",
                "$home\\.bun\\bin\\codex.exe",
            )
        }

        return listOf(
            "/opt/homebrew/bin/codex",
            "/usr/local/bin/codex",
            "$home/.local/bin/codex",
            "$home/.codex/bin/codex",
            "$home/.cargo/bin/codex",
            "$home/.npm-global/bin/codex",
            "$home/.volta/bin/codex",
            "$home/.bun/bin/codex",
            "/usr/bin/codex",
        )
    }

    fun pathValue(env: Map<String, String>): String? =
        env["PATH"] ?: env["Path"] ?: env["path"]

    /**
     * Where to look, in order: first what the person pointed at, then PATH, then the usual locations.
     * Returns candidate paths - whether they exist is for the caller to decide (see
     * [CodexExecutable]).
     */
    fun candidates(
        windows: Boolean,
        home: String,
        env: Map<String, String>,
        configured: String,
        separator: Char,
    ): List<String> {
        val names = executableNames(windows)
        val slash = if (windows) '\\' else '/'
        val result = mutableListOf<String>()

        val manual = expandHome(configured.trim(), home)
        if (manual.isNotEmpty()) {
            // Both the file itself and the folder holding it are accepted: a person is just as likely
            // to copy one as the other.
            result += manual
            result += names.map { "$manual$slash$it" }
        }

        pathValue(env)
            ?.split(separator)
            ?.filter { it.isNotBlank() }
            ?.forEach { directory -> names.forEach { result += "${directory.trimEnd(slash)}$slash$it" } }

        result += fallbackPaths(windows, home, env)

        return result.distinct()
    }

    fun expandHome(path: String, home: String): String =
        if (path.startsWith("~/") || path.startsWith("~\\")) home + path.drop(1) else path
}
