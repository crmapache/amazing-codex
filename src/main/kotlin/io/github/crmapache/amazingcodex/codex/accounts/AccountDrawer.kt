package io.github.crmapache.amazingcodex.codex.accounts

import com.intellij.openapi.diagnostic.thisLogger
import io.github.crmapache.amazingcodex.feedback.DiagnosticsLog
import java.io.File
import java.nio.file.Files

/**
 * An account's own Codex home: its credential, and a link to everything else.
 *
 * Codex has no switch that moves only the credential, so an account gets a home of its own (see
 * AccountStore.STORE_VARIABLE) - and a home of its own would mean a history of its own, a config of its
 * own, skills of its own. That is the one thing switching accounts must not do: choosing another account
 * is choosing who pays, not moving to another machine. So every entry of the ordinary home is linked into
 * the account's folder, and only the credential is not: Codex reads and writes the conversations, the
 * config and the skills through the links, into the one place they have always been.
 *
 * Synced every time an account's environment is handed out, because the ordinary home grows: a folder
 * Codex creates there after the account was added would otherwise be missing from the account's view of
 * it. Only missing links are made; nothing that is already there is touched.
 */
internal object AccountDrawer {

    /** What stays the account's own: the credential and the lock Codex takes on it. */
    private val OWN = setOf("auth.json", "auth.json.lock")

    /**
     * Codex's state databases are reached through CODEX_SQLITE_HOME rather than through links: a link to
     * a database would put its write-ahead log beside the link instead of beside the database.
     */
    private fun isDatabase(name: String): Boolean = name.contains(".sqlite")

    /** Shared entries Codex may create lazily - made in the ordinary home first, so they are linked, not forked. */
    private val SHARED_FOLDERS = listOf("sessions", "skills", "prompts", "rules")
    private val SHARED_FILES = listOf("config.toml", "session_index.jsonl", "history.jsonl")

    fun sync(drawer: File, home: File) {
        runCatching {
            if (!drawer.isDirectory) return
            home.mkdirs()
            SHARED_FOLDERS.forEach { File(home, it).mkdirs() }
            SHARED_FILES.forEach { name -> File(home, name).takeIf { !it.exists() }?.createNewFile() }

            val entries = home.listFiles() ?: return
            for (entry in entries) {
                val name = entry.name
                if (name in OWN || isDatabase(name)) continue

                val link = File(drawer, name).toPath()
                if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue

                runCatching { Files.createSymbolicLink(link, entry.toPath()) }.onFailure {
                    // Windows without developer mode refuses symbolic links. A folder can still be a
                    // junction; a file cannot, and is left out - which the isolation probe then notices
                    // and turns accounts off rather than let a history split (see IsolationProof).
                    if (!entry.isDirectory || !junction(link.toFile(), entry)) {
                        thisLogger().info("Could not link $name into an account's home: ${it.message}")
                    }
                }
            }
        }.onFailure {
            DiagnosticsLog.note(DiagnosticsLog.ACCOUNTS, "an account's home could not be linked to the ordinary one")
        }
    }

    private fun junction(link: File, target: File): Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return false
        return runCatching {
            ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
    }

    /**
     * Remove an account's home - its credential and its links - without ever following a link.
     *
     * Everything but the credential in there is a link into the ordinary home, and a recursive delete
     * that follows links (Kotlin's `File.deleteRecursively` does: it asks `isDirectory`, which a link to a
     * folder answers yes) would walk through `sessions` and delete every conversation the person has. The
     * walk here is NIO's, which does not follow links: a link is deleted as the link it is.
     */
    fun delete(drawer: File): Boolean {
        val root = drawer.toPath()
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                root,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        Files.deleteIfExists(file)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult {
                        Files.deleteIfExists(file)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                        Files.deleteIfExists(dir)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
            true
        }.getOrDefault(false)
    }
}
