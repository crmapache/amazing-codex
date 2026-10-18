package io.github.crmapache.amazingcodex.webview

import com.intellij.openapi.diagnostic.thisLogger
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The picture behind an attachment chip - for the preview the panel shows under the pointer (see
 * imagePreview.ts in the webview), and for the editor a click on the chip opens it in.
 *
 * The page holds a pasted picture's bytes itself. Everything else it has to ask for: a picture picked in
 * the IDE's dialog is a path, a queued message reaches the page without its bytes, and a conversation read
 * back off the disk keeps `[Image #2]` and nothing else - the picture lives on in its message in Codex's
 * thread. The readers of a file and of bytes are here, the thread's in ThreadPictures; which one a request
 * means is the hub's to say (see CodexSessionHub.previewImage).
 *
 * Every answer is a data URL or nothing, and a large picture is shrunk on the way: the preview stands a few
 * hundred pixels across, and a screenshot of a 6K display is ten megabytes to push through the bridge and
 * decode in the page for the sake of that.
 */
internal object ImagePreviews {

    /** A picture's bytes as read, before anything is made of them - a preview, a file to open. */
    class Picture(val bytes: ByteArray, val mediaType: String)

    /** A file past this is not read at all - a picture that size is a camera's raw dump, not a screenshot. */
    internal const val MAX_FILE_BYTES = 30L * 1024 * 1024

    /** Below this a picture travels as it is: it is already about the size its shrunk copy would be. */
    internal const val SHRINK_ABOVE_BYTES = 1_500_000

    /**
     * A picture Java cannot decode (WebP among them) cannot be shrunk; up to this it still travels whole,
     * past it there is no preview rather than a page stalled on a string of tens of megabytes.
     */
    internal const val MAX_WHOLE_BYTES = 12 * 1024 * 1024

    /** The longest side of a shrunk picture: twice the preview's own, for a Retina screen. */
    internal const val LONGEST_SIDE = 1200

    /**
     * The most pixels a picture is decoded at whole - a 6K screenshot, with room to spare, and about a hundred
     * megabytes once decoded. Past it every n-th pixel is read (see [subsampling]): the decode runs on a pool
     * the whole IDE shares, and a picture of 20000 by 20000 pixels is a few megabytes on disk and gigabytes in
     * memory.
     */
    internal const val MAX_DECODED_PIXELS = 25_000_000L

    /** Every how many pixels a picture of this size is read at - 1 for all of them. */
    internal fun subsampling(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        if (pixels <= MAX_DECODED_PIXELS) return 1
        return ceil(sqrt(pixels.toDouble() / MAX_DECODED_PIXELS)).toInt()
    }

    /** The size a picture of this size is decoded at (see [subsampling]). */
    internal fun decodedSize(width: Int, height: Int): Pair<Int, Int> {
        val step = subsampling(width, height)
        return (width + step - 1) / step to (height + step - 1) / step
    }

    /**
     * A picture on disk, the file a chip's path came to (see OpenInEditor.resolve - the same file a click on
     * the chip opens, found the same way: from the project's root, from the home, by name).
     *
     * Only a picture, by its extension: the path comes from a chip, and a chip is a message like any other,
     * one that could name any file on the disk.
     */
    fun ofFile(file: File): Picture? = runCatching {
        val mediaType = PictureFormats.mediaTypeOf(file.extension) ?: return null
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null

        Picture(file.readBytes(), mediaType)
    }.getOrNull()

    /** Bytes the IDE already holds as base64 - a queued message's picture (see SessionQueue.Entry.images). */
    fun ofBase64(mediaType: String, data: String): Picture? = runCatching {
        Picture(Base64.getDecoder().decode(data), mediaType)
    }.getOrNull()

    /**
     * The picture a page's data URL carries - the bytes of a pasted picture the IDE never wrote out (an old
     * IDE, a failed write), sent with a click to be opened (see CodexSessionHub.openPicture).
     *
     * A picture only: the media type decides the file's extension, and the page is a client like any other.
     * Taken apart by hand rather than by a pattern - the string is megabytes long.
     */
    fun ofDataUrl(url: String): Picture? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null

        val head = url.substring("data:".length, comma)
        if (!head.endsWith(";base64")) return null
        val mediaType = head.removeSuffix(";base64").lowercase()
        if (!mediaType.startsWith("image/")) return null

        return ofBase64(mediaType, url.substring(comma + 1))
    }

    /**
     * A picture as it travels to the page: the data URL, and - when the URL holds a smaller copy - the size of
     * the picture itself, which the caption under the preview gives (see ImagePreview.tsx). Zero when the URL
     * is the picture: the page reads its size off it.
     */
    class Fitted(val url: String, val width: Int = 0, val height: Int = 0)

    /**
     * The picture as a data URL, shrunk when it is large - or null when it cannot be shown at all.
     *
     * A drawing (SVG) is never shrunk: it is text, it scales by itself, and Java does not read it.
     */
    fun fitted(picture: Picture): Fitted? {
        val bytes = picture.bytes
        val mediaType = picture.mediaType
        if (bytes.isEmpty()) return null
        val whole = { if (bytes.size <= MAX_WHOLE_BYTES) Fitted(dataUrl(bytes, mediaType)) else null }
        if (bytes.size <= SHRINK_ABOVE_BYTES || mediaType == "image/svg+xml") return whole()

        // Whatever goes wrong in the shrinking - a format Java has no reader for, a CMYK JPEG, a PNG cut short -
        // the picture travels whole, as promised: the browser reads more than Java does.
        return runCatching { shrunk(bytes) }
            .onFailure { thisLogger().debug("A picture could not be shrunk for its preview", it) }
            .getOrNull()
            ?: whole()
    }

    /**
     * A smaller copy, no longer than [LONGEST_SIDE] on its longest side - a PNG when the picture has
     * transparency (a JPEG would turn it black), a JPEG otherwise (a photo as a PNG stays megabytes).
     *
     * Halved step by step rather than in one jump: one jump from four thousand pixels to twelve hundred
     * skips most of the pixels and leaves text in a screenshot torn - and text is most of what is in one.
     */
    private fun shrunk(bytes: ByteArray): Fitted? {
        val decoded = decoded(bytes) ?: return null
        val original = decoded.image
        val alpha = original.colorModel.hasAlpha()
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB

        val longest = max(original.width, original.height)
        val scale = minOf(1.0, LONGEST_SIDE.toDouble() / longest)
        val targetWidth = max(1, (original.width * scale).roundToInt())
        val targetHeight = max(1, (original.height * scale).roundToInt())

        var image: BufferedImage = original
        do {
            val width = max(targetWidth, image.width / 2)
            val height = max(targetHeight, image.height / 2)
            image = redraw(image, width, height, type)
        } while (image.width > targetWidth || image.height > targetHeight)

        // Turned the way the camera was held, as the browser turns the whole picture: the copy loses the note.
        val orientation = orientationOf(bytes)
        image = upright(image, orientation, type)
        val sideways = orientation in 5..8

        val out = ByteArrayOutputStream()
        val written = ImageIO.write(image, if (alpha) "png" else "jpg", out)
        if (!written) return null
        val url = dataUrl(out.toByteArray(), if (alpha) "image/png" else "image/jpeg")
        return if (sideways) Fitted(url, decoded.height, decoded.width) else Fitted(url, decoded.width, decoded.height)
    }

    /**
     * How the camera says the picture is to be turned - the EXIF orientation of a JPEG, 1 to 8, and 1 for upright
     * or for anything that is not a JPEG with the note.
     *
     * A phone held upright writes a landscape picture and this note, and the browser obeys it: the whole picture
     * shows as it was held. The copy made here is drawn from the pixels and written without the note, so it lay on
     * its side. Read by hand: Java's JPEG reader keeps the EXIF block as bytes it does not take apart.
     */
    internal fun orientationOf(bytes: ByteArray): Int = runCatching {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return 1

        var at = 2
        while (at + 4 <= bytes.size) {
            if (u8(bytes, at) != 0xFF) return 1
            val marker = u8(bytes, at + 1)
            // Markers that stand alone, and the padding between segments.
            if (marker == 0xFF) { at += 1; continue }
            if (marker == 0x01 || marker in 0xD0..0xD8) { at += 2; continue }
            // The picture itself begins: no header after this.
            if (marker == 0xDA || marker == 0xD9) return 1

            val length = (u8(bytes, at + 2) shl 8) or u8(bytes, at + 3)
            if (length < 2) return 1
            val body = at + 4
            val end = minOf(bytes.size, at + 2 + length)
            if (marker == 0xE1 && end - body >= 6 && EXIF.indices.all { bytes[body + it] == EXIF[it] }) {
                return tiffOrientation(bytes, body + EXIF.size, end)
            }
            at += 2 + length
        }
        1
    }.getOrDefault(1)

    /** The orientation tag of the first directory of an EXIF block's TIFF structure, [start] to [end]. */
    private fun tiffOrientation(bytes: ByteArray, start: Int, end: Int): Int {
        if (start + 8 > end) return 1
        val little = when {
            bytes[start] == 'I'.code.toByte() && bytes[start + 1] == 'I'.code.toByte() -> true
            bytes[start] == 'M'.code.toByte() && bytes[start + 1] == 'M'.code.toByte() -> false
            else -> return 1
        }
        val u16 = { at: Int ->
            if (little) u8(bytes, at) or (u8(bytes, at + 1) shl 8) else (u8(bytes, at) shl 8) or u8(bytes, at + 1)
        }
        val u32 = { at: Int ->
            if (little) u16(at).toLong() or (u16(at + 2).toLong() shl 16) else (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }
        if (u16(start + 2) != 42) return 1

        val directory = start + u32(start + 4)
        if (directory + 2 > end) return 1
        val first = directory.toInt()
        for (index in 0 until u16(first)) {
            val entry = first + 2 + index * 12
            if (entry + 12 > end) return 1
            if (u16(entry) == ORIENTATION_TAG) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    /** The picture turned and flipped the way [orientation] says (see [orientationOf]). */
    internal fun upright(image: BufferedImage, orientation: Int, type: Int): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        // x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12, given as m00, m10, m01, m11, m02, m12 - the
        // order AffineTransform takes them in.
        val turn = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            else -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
        }
        val sideways = orientation >= 5
        val next = BufferedImage(if (sideways) image.height else image.width, if (sideways) image.width else image.height, type)
        val graphics = next.createGraphics()
        try {
            graphics.drawImage(image, turn, null)
        } finally {
            graphics.dispose()
        }
        return next
    }

    private val EXIF = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private const val ORIENTATION_TAG = 0x0112

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    /** A picture decoded for shrinking, and the size it has on disk - the decoded one may be a fraction of it. */
    private class Decoded(val image: BufferedImage, val width: Int, val height: Int)

    /**
     * The picture decoded at a size memory can hold: its header is read first, and a picture past
     * [MAX_DECODED_PIXELS] is read every n-th pixel (see [subsampling]) rather than whole. Null when Java has
     * no reader for it.
     */
    private fun decoded(bytes: ByteArray): Decoded? =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(stream, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val step = subsampling(width, height)
                val param = reader.defaultReadParam.apply { if (step > 1) setSourceSubsampling(step, step, 0, 0) }
                Decoded(reader.read(0, param), width, height)
            } finally {
                reader.dispose()
            }
        }

    private fun redraw(image: BufferedImage, width: Int, height: Int, type: Int): BufferedImage {
        val next = BufferedImage(width, height, type)
        val graphics = next.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return next
    }

    private fun dataUrl(bytes: ByteArray, mediaType: String): String =
        "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}"
}
