package io.github.crmapache.amazingcodex.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * The disk half of [ChangeRevert]: one file read, its turn undone - or made again - and the result written
 * back.
 *
 * Through the file's document and as a command of the IDE, rather than straight onto the disk. That way an
 * editor showing the file changes in place, and the revert is one more step on that editor's undo - a person
 * who pressed the wrong row takes it back with the key they already know. A file the turn created is deleted
 * the same way, so the IDE's local history keeps it, and is written anew when the changes are brought back.
 *
 * A file the turn deleted is written anew the same way, and a file it moved is carried back to where it stood -
 * moved and rewritten in one command, so one Cmd+Z in its editor takes back both (the IDE's own undo of a file's
 * move and deletion rests on its local history, as it does for a file moved or deleted by hand).
 *
 * Runs off the event thread: the file is refreshed from the disk first (the agent wrote it from outside, and
 * the IDE's picture may be a step behind), and that is a walk of the disk. The edit itself goes to the event
 * thread, as every write to a document must.
 */
internal object FileReverts {

    private const val REVERT = "Revert Codex's Changes"
    private const val RESTORE = "Bring Back Codex's Changes"

    /**
     * [edits] undone, or with [restore] made again after an undo. [path] is where the turn left the file - for a file
     * it deleted, where it stood.
     */
    fun apply(project: Project, path: String, edits: List<ChangeRevert.Edit>, restore: Boolean): ChangeRevert.Outcome {
        val nio = absolute(path) ?: return ChangeRevert.Outcome.FAILED
        val done = if (restore) ChangeRevert.Outcome.RESTORED else ChangeRevert.Outcome.REVERTED

        val origin = ChangeRevert.originOf(edits)?.let { absolute(it) ?: return ChangeRevert.Outcome.FAILED }
        if (origin != null && origin != nio) return moved(project, nio, origin, edits, restore)

        val file = textFile(nio)
        if (file == null) {
            // Not there: a file the turn created and somebody has since removed is already what a revert makes it, one
            // the turn deleted is written back (or, brought forward, already gone as the turn left it).
            return when (val result = if (restore) ChangeRevert.bringBack(null, edits) else ChangeRevert.revert(null, edits)) {
                ChangeRevert.Result.Delete -> done
                is ChangeRevert.Result.Text -> onEventThread(project, done) { recreate(project, nio, result.text, restore) }
                ChangeRevert.Result.Changed -> ChangeRevert.Outcome.MISSING
            }
        }

        return onEventThread(project, done) {
            rewrite(project, file, edits, restore)
        }
    }

    /**
     * A file the turn moved from [origin] to [here]: put back at [origin] with its text undone, or brought forward to
     * [here] with the text made again - one command either way. Something standing at the place the file is to go is
     * somebody else's file, and the answer is "changed"; the file already where it is to go, as it is to be, is
     * already done.
     */
    private fun moved(project: Project, here: Path, origin: Path, edits: List<ChangeRevert.Edit>, restore: Boolean): ChangeRevert.Outcome {
        val done = if (restore) ChangeRevert.Outcome.RESTORED else ChangeRevert.Outcome.REVERTED
        val (from, to) = if (restore) origin to here else here to origin
        val source = textFile(from)
        val target = textFile(to)

        if (source == null) {
            val standing = target ?: return ChangeRevert.Outcome.MISSING
            val text = runCatching { readNow(standing) }.getOrNull() ?: return ChangeRevert.Outcome.FAILED
            val already = if (restore) ChangeRevert.isRedone(text, edits) else ChangeRevert.isUndone(text, edits)
            return if (already) done else ChangeRevert.Outcome.CHANGED
        }
        if (target != null) return ChangeRevert.Outcome.CHANGED

        return onEventThread(project, done) {
            val manager = FileDocumentManager.getInstance()
            val document = manager.getDocument(source) ?: return@onEventThread ChangeRevert.Outcome.FAILED
            val result = if (restore) ChangeRevert.bringBack(document.text, edits) else ChangeRevert.revert(document.text, edits)
            val text = (result as? ChangeRevert.Result.Text)?.text ?: return@onEventThread ChangeRevert.Outcome.CHANGED
            if (!document.isWritable) return@onEventThread ChangeRevert.Outcome.FAILED

            WriteCommandAction.writeCommandAction(project).withName(if (restore) RESTORE else REVERT).run<Throwable> {
                val folder = VfsUtil.createDirectoryIfMissing(to.parent.toString()) ?: error("no folder")
                if (source.parent != folder) source.move(this, folder)
                val name = to.fileName.toString()
                if (source.name != name) source.rename(this, name)
                replaceDiffering(document, text)
            }
            manager.saveDocument(document)
            done
        }
    }

    /** [work] on the event thread, with anything it throws said as a failure rather than lost. */
    private fun onEventThread(project: Project, done: ChangeRevert.Outcome, work: () -> ChangeRevert.Outcome): ChangeRevert.Outcome {
        var outcome = ChangeRevert.Outcome.FAILED
        ApplicationManager.getApplication().invokeAndWait(
            {
                if (project.isDisposed) return@invokeAndWait
                outcome = runCatching { work() }
                    .onFailure { thisLogger().warn("Couldn't put a file ${if (done == ChangeRevert.Outcome.RESTORED) "forward" else "back"}", it) }
                    .getOrDefault(ChangeRevert.Outcome.FAILED)
            },
            ModalityState.defaultModalityState(),
        )
        return outcome
    }

    private fun rewrite(project: Project, file: VirtualFile, edits: List<ChangeRevert.Edit>, restore: Boolean): ChangeRevert.Outcome {
        val manager = FileDocumentManager.getInstance()
        // No document means a file the IDE does not hold as text - nothing the CLI edited line by line.
        val document = manager.getDocument(file) ?: return ChangeRevert.Outcome.FAILED
        val result = if (restore) ChangeRevert.bringBack(document.text, edits) else ChangeRevert.revert(document.text, edits)
        val done = if (restore) ChangeRevert.Outcome.RESTORED else ChangeRevert.Outcome.REVERTED

        return when (result) {
            ChangeRevert.Result.Changed -> ChangeRevert.Outcome.CHANGED

            // Undone, a file the turn created; made again, one it deleted.
            ChangeRevert.Result.Delete -> {
                WriteCommandAction.writeCommandAction(project).withName(if (restore) RESTORE else REVERT).run<Throwable> { file.delete(this) }
                done
            }

            is ChangeRevert.Result.Text -> {
                if (!document.isWritable) return ChangeRevert.Outcome.FAILED
                if (document.text != result.text) {
                    WriteCommandAction.writeCommandAction(project).withName(if (restore) RESTORE else REVERT).run<Throwable> {
                        replaceDiffering(document, result.text)
                    }
                    manager.saveDocument(document)
                }
                done
            }
        }
    }

    /**
     * [document] made to read [after], by replacing only the stretch that differs: the caret, the folds and the scroll
     * of an editor showing the file stay where they were everywhere else. Inside a write command.
     */
    private fun replaceDiffering(document: Document, after: String) {
        val before = document.text
        val head = before.commonPrefixWith(after).length
        val tail = minOf(before.length, after.length).let { shorter ->
            var same = 0
            while (same < shorter - head && before[before.length - 1 - same] == after[after.length - 1 - same]) same++
            same
        }
        if (head < before.length - tail || head < after.length - tail) {
            document.replaceString(head, before.length - tail, after.substring(head, after.length - tail))
        }
    }

    /**
     * A file not there written as [text] - a created file brought back after its revert, a deleted one put back - its
     * folders too, if they went with it.
     */
    private fun recreate(project: Project, path: Path, text: String, restore: Boolean): ChangeRevert.Outcome {
        val parent = path.parent ?: return ChangeRevert.Outcome.FAILED

        WriteCommandAction.writeCommandAction(project).withName(if (restore) RESTORE else REVERT).run<Throwable> {
            val folder = VfsUtil.createDirectoryIfMissing(parent.toString()) ?: error("no folder")
            val file = folder.findChild(path.fileName.toString()) ?: folder.createChildData(this, path.fileName.toString())
            VfsUtil.saveText(file, text)
        }
        return if (restore) ChangeRevert.Outcome.RESTORED else ChangeRevert.Outcome.REVERTED
    }

    private fun absolute(path: String): Path? = runCatching { Path.of(path) }.getOrNull()?.takeIf { it.isAbsolute }

    /** The file at [path] as the disk has it now, or null when there is no file there. */
    private fun textFile(path: Path): VirtualFile? {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (file == null || !file.isValid || file.isDirectory) return null
        file.refresh(false, false)
        return file
    }

    /** What [file] holds, read through its document as the revert reads it - off the event thread, under a read. */
    private fun readNow(file: VirtualFile): String? = ApplicationManager.getApplication().runReadAction<String?> {
        FileDocumentManager.getInstance().getDocument(file)?.text
    }
}
