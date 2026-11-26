package io.github.crmapache.amazingcodex.editor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The project's files at one moment, written down by git into a store of the plugin's own - so that a turn can be
 * held against the moment it began, and every file it changed found, whatever changed it (see [TurnFootprint]).
 *
 * The person's repository is only read. Git is pointed at an index of ours (`GIT_INDEX_FILE`) and an object store of
 * ours (`GIT_OBJECT_DIRECTORY`), with the repository's own objects behind it as an alternate: `git add -A` into that
 * index writes a blob for every file that is not in the repository already - the dirty and the untracked ones - into
 * our store, and `git write-tree` names the whole moment with one id. The person's index, stash, refs and objects are
 * never written: nothing here shows up in `git status`, and nothing is left behind for `git gc` either.
 *
 * Cheap after the first time. The index keeps the size and time of every file, so a second snapshot hashes only what
 * changed since the first (measured on a monorepo of 2000 files: 0.27 s for the first, 0.1 s after).
 *
 * Only what git would see counts: an ignored file - `node_modules`, a build, `.env` - is in no snapshot.
 */
internal class WorkTreeSnapshots(
    /** Where the stores live, one folder per project root. */
    private val storage: File,
    private val git: String = "git",
    private val timeoutMs: Long = TIMEOUT_MS,
) {

    /** A project root inside a repository, and the store its snapshots are kept in. */
    class Repo internal constructor(
        /** The project's folder, as the IDE names it - every path handed out is under it. */
        val root: File,
        /** The repository's top, where git's own paths start. */
        internal val top: File,
        /** [root] from [top], with a slash at its end - or empty when the project is the whole repository. */
        internal val prefix: String,
        internal val store: File,
        internal val index: File,
        internal val objects: File,
    ) {
        /** The repository's own index and objects, which a fresh store starts from. */
        internal lateinit var realIndex: File
        internal lateinit var realObjects: File
    }

    /** One moment of the project: git's name for the whole tree at it. */
    class Snapshot internal constructor(internal val repo: Repo, val tree: String)

    /**
     * A file that differs between two moments: where it is, and its content's id at each - null where it was not there.
     */
    data class Change(val file: File, val before: String?, val after: String?)

    /** What a file held at a moment, when it could be read: the bytes, or why not. */
    sealed interface Content {
        class Bytes(val bytes: ByteArray) : Content

        /** Larger than anybody would read a diff of. */
        data object TooBig : Content

        data object Unreadable : Content
    }

    /** The stores that have been started afresh in this run of the IDE - see [take]. */
    private val started = ConcurrentHashMap.newKeySet<String>()

    private val locks = ConcurrentHashMap<String, Any>()

    @Volatile
    private var swept = false

    /** The repository [root] lies in, or null: no repository there, no git to ask, a project in WSL. */
    fun repoOf(root: File): Repo? {
        if (root.path.startsWith("\\\\")) return null
        if (!root.isDirectory) return null
        val lines = run(root, null, null, "rev-parse", "--show-prefix", "--path-format=absolute", "--show-toplevel", "--git-common-dir", "--git-path", "index")
            ?.toString(Charsets.UTF_8)?.lines()
            // A git older than 2.31 does not know the option and says it back; its paths are then from the root.
            ?.filter { it != "--path-format=absolute" } ?: return null
        if (lines.size < 4) return null
        val (prefix, top, commonPath, indexPath) = lines
        if (top.isBlank() || commonPath.isBlank() || indexPath.isBlank()) return null
        val common = File(commonPath).let { if (it.isAbsolute) it else File(root, commonPath) }
        val index = File(indexPath).let { if (it.isAbsolute) it else File(root, indexPath) }

        val store = File(storage, sha256(root.absolutePath).take(16))
        return Repo(
            root = root,
            top = File(top),
            prefix = prefix,
            store = store,
            index = File(store, "index"),
            objects = File(store, "objects"),
        ).also {
            it.realIndex = index
            it.realObjects = File(common, "objects")
        }
    }

    /**
     * The project as it stands now, or null when git could not say: no repository, a git that failed or took too
     * long. [fresh] starts the store over first - the caller knows when no earlier snapshot of it is still wanted.
     *
     * The first snapshot of a store in a run of the IDE starts it over too. A store is the sum of every file that has
     * changed while it was kept, and starting each run from the repository's own index keeps that bounded by a run.
     */
    fun take(root: File, fresh: Boolean = false): Snapshot? {
        sweep()
        val repo = repoOf(root) ?: return null
        val key = repo.store.path
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            val first = started.add(key)
            if (fresh || first || !repo.index.isFile) restart(repo)
            // A lock of our own index is never anybody else's: one left by a git that was stopped half way.
            File(repo.store, "index.lock").delete()

            val env = envOf(repo)
            // The project's own folder only: a repository holding several projects is not this project's business.
            run(repo.root, env, null, "add", "-A", "--", ".") ?: return null
            val tree = run(repo.root, env, null, "write-tree")?.toString(Charsets.UTF_8)?.trim() ?: return null
            if (!OBJECT_ID.matches(tree)) return null
            repo.store.setLastModified(System.currentTimeMillis())
            return Snapshot(repo, tree)
        }
    }

    /** Whether the store [root]'s snapshots go into has grown past what is worth keeping. */
    fun outgrown(root: File): Boolean {
        val objects = repoOf(root)?.objects ?: return false
        return objects.walkTopDown().filter { it.isFile }.sumOf { it.length() } > MAX_STORE_BYTES
    }

    /**
     * Every file under the project that differs between [from] and [to], in the order of their paths - or null when
     * git could not say. A renamed file is the old one gone and the new one come: that is what a revert undoes. Links
     * and submodules are left out; they are not files a line can be counted in.
     */
    fun changes(from: Snapshot, to: Snapshot): List<Change>? {
        val repo = to.repo
        if (from.repo.store != repo.store) return null
        if (from.tree == to.tree) return emptyList()
        val pathspec = repo.prefix.ifEmpty { "." }
        val raw = run(repo.top, envOf(repo), null, "diff-tree", "-r", "-z", "--raw", "--no-renames", "--no-abbrev", from.tree, to.tree, "--", pathspec)
            ?: return null

        // ":<mode> <mode> <id> <id> <status>\0<path>\0" for each file.
        val fields = raw.toString(Charsets.UTF_8).split('\u0000')
        val changes = mutableListOf<Change>()
        var index = 0
        while (index + 1 < fields.size) {
            val head = fields[index]
            val path = fields[index + 1]
            index += 2
            if (!head.startsWith(":")) continue
            val (oldMode, newMode, oldId, newId) = head.removePrefix(":").split(' ').takeIf { it.size >= 5 } ?: continue
            if (!regular(oldMode) || !regular(newMode)) continue
            if (!path.startsWith(repo.prefix)) continue
            changes += Change(
                file = File(repo.root, path.removePrefix(repo.prefix)),
                before = oldId.takeUnless { oldMode == ABSENT_MODE },
                after = newId.takeUnless { newMode == ABSENT_MODE },
            )
        }
        return changes.sortedBy { it.file.path }
    }

    /**
     * What each of [ids] holds, as git stored it - read in one go. A file git filters (line endings, LFS) is held in its
     * stored form on both sides of a change, which is what makes the two comparable.
     */
    fun contents(snapshot: Snapshot, ids: Collection<String>): Map<String, Content>? {
        if (ids.isEmpty()) return emptyMap()
        val repo = snapshot.repo
        val input = ids.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
        val read = HashMap<String, Content>()
        val ok = stream(repo.top, envOf(repo), input, "cat-file", "--batch") { output -> readBatch(output, read) }
        return if (ok) read else null
    }

    /** The answers of `cat-file --batch`: a head line, then that many bytes and a line break - or a line saying missing. */
    private fun readBatch(output: InputStream, into: MutableMap<String, Content>) {
        val input = output.buffered()
        while (true) {
            val head = readLine(input) ?: return
            val parts = head.split(' ')
            if (parts.size < 3) {
                parts.firstOrNull()?.let { into[it] = Content.Unreadable }
                continue
            }
            val id = parts[0]
            val size = parts[2].toLongOrNull() ?: return
            if (size > MAX_FILE_BYTES) {
                input.skipNBytes(size)
                into[id] = Content.TooBig
            } else {
                into[id] = Content.Bytes(input.readNBytes(size.toInt()))
            }
            input.read()
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
            if (byte == '\n'.code) return line.toString(Charsets.UTF_8)
            line.write(byte)
        }
    }

    /** The store emptied and begun again from the repository's own index - its file times included, so nothing is rehashed. */
    private fun restart(repo: Repo) {
        repo.store.deleteRecursively()
        repo.objects.mkdirs()
        if (repo.realIndex.isFile) repo.realIndex.copyTo(repo.index, overwrite = true)
    }

    /** Stores of projects not opened for a long while, gone - once a run. */
    private fun sweep() {
        if (swept) return
        swept = true
        val cutoff = System.currentTimeMillis() - STALE_STORE_MS
        storage.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private fun envOf(repo: Repo): Map<String, String> = mapOf(
        "GIT_INDEX_FILE" to repo.index.path,
        "GIT_OBJECT_DIRECTORY" to repo.objects.path,
        "GIT_ALTERNATE_OBJECT_DIRECTORIES" to repo.realObjects.path,
        // Never stop to ask anybody anything.
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_OPTIONAL_LOCKS" to "0",
    )

    private fun run(cwd: File, env: Map<String, String>?, input: ByteArray?, vararg args: String): ByteArray? {
        var out: ByteArray? = null
        val ok = stream(cwd, env, input, *args) { out = it.readAllBytes() }
        return if (ok) out else null
    }

    /**
     * Git, run in [cwd] with [env] over whatever the IDE's own environment says - its `GIT_` settings left out, so a
     * variable the IDE was started with cannot point this at another repository. [read] takes the output while the input
     * is still being written: a long list one way and long contents the other would otherwise fill both pipes and stop.
     * Stopped after [timeoutMs], and then said as a failure.
     */
    private fun stream(cwd: File, env: Map<String, String>?, input: ByteArray?, vararg args: String, read: (InputStream) -> Unit): Boolean {
        val process = runCatching {
            ProcessBuilder(listOf(git, "-c", "core.quotepath=off", *args))
                .directory(cwd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { builder ->
                    builder.environment().keys.removeIf { it.startsWith("GIT_") }
                    env?.let { builder.environment().putAll(it) }
                }
                .start()
        }.getOrNull() ?: return false

        val writer = Thread {
            runCatching { process.outputStream.use { stdin -> input?.let(stdin::write) } }
        }.apply {
            isDaemon = true
            start()
        }
        val watchdog = Thread {
            runCatching { if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
        }.apply {
            isDaemon = true
            start()
        }

        val readOk = runCatching { process.inputStream.use(read) }.isSuccess
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        writer.join(1_000)
        watchdog.interrupt()
        return readOk && finished && process.exitValue() == 0
    }

    private fun regular(mode: String): Boolean = mode == ABSENT_MODE || mode.startsWith("100")

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        /** A git call longer than this is given up: the turn's list is better late by a moment than never. */
        const val TIMEOUT_MS = 15_000L

        /** A file bigger than this is not read for a diff. */
        const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /** A store bigger than this starts over at the next turn nobody else is in the middle of (see [outgrown]). */
        private const val MAX_STORE_BYTES = 512L * 1024 * 1024

        private const val STALE_STORE_MS = 14L * 24 * 60 * 60 * 1000

        private const val ABSENT_MODE = "000000"

        private val OBJECT_ID = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    }
}
