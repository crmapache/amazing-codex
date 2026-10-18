package io.github.crmapache.amazingcodex.webview

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picture behind a chip: off disk, out of a page's data URL, and shrunk when it is large. Out of a Codex thread's
 * line - see ThreadPicturesTest.
 */
class ImagePreviewsTest {

    private val dot = Base64.getEncoder().encodeToString(png(2, 2))

    private fun png(width: Int, height: Int, noise: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        if (noise) {
            // Noise does not compress: the only way to get a PNG past the shrinking threshold without a huge one.
            val random = Random(7)
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt())
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun ImagePreviews.Picture.base64(): String = Base64.getEncoder().encodeToString(bytes)

    /* Which file the path means is the editor's to say (see OpenInEditor.written); this reads only a picture. */
    @Test
    fun `a file is read only when it is a picture`() {
        val folder = Files.createTempDirectory("previews").toFile()
        try {
            File(folder, "shot.png").writeBytes(png(2, 2))
            File(folder, "notes.txt").writeText("not a picture")

            assertEquals("image/png", ImagePreviews.ofFile(File(folder, "shot.png"))?.mediaType)
            assertNull(ImagePreviews.ofFile(File(folder, "notes.txt")))
            assertNull(ImagePreviews.ofFile(File(folder, "missing.png")))
            assertNull(ImagePreviews.ofFile(folder))
        } finally {
            folder.deleteRecursively()
        }
    }

    /* The bytes a page holds of a pasted picture the IDE never wrote out - written now, to be opened. */
    @Test
    fun `a page's data URL is read back as the picture it carries, and only a picture`() {
        val picture = ImagePreviews.ofDataUrl("data:image/png;base64,$dot")

        assertEquals("image/png", picture?.mediaType)
        assertEquals(dot, picture?.base64())
        assertNull(ImagePreviews.ofDataUrl("data:text/html;base64,$dot"))
        assertNull(ImagePreviews.ofDataUrl("data:image/png,$dot"))
        assertNull(ImagePreviews.ofDataUrl("/Users/me/shot.png"))
        assertNull(ImagePreviews.ofDataUrl("data:image/png;base64,***"))
    }

    @Test
    fun `a small picture travels as it is`() {
        val bytes = png(4, 4)

        val fitted = ImagePreviews.fitted(ImagePreviews.Picture(bytes, "image/png"))!!

        assertEquals("data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}", fitted.url)
        // The page reads the size off the picture itself: nothing to say.
        assertEquals(0 to 0, fitted.width to fitted.height)
    }

    /* What Java cannot read the browser often can - a CMYK JPEG, a PNG cut short: it travels whole, as promised. */
    @Test
    fun `a large picture Java fails to read travels whole rather than not at all`() {
        val whole = png(1600, 1000, noise = true)
        val broken = whole.copyOf(whole.size - 4096)
        assertTrue(broken.size > ImagePreviews.SHRINK_ABOVE_BYTES, "the fixture has to be past the threshold")

        assertEquals(
            "data:image/png;base64,${Base64.getEncoder().encodeToString(broken)}",
            ImagePreviews.fitted(ImagePreviews.Picture(broken, "image/png"))?.url,
        )
    }

    /*
     * Only the header is read before decoding: a picture of 20000 by 20000 is a megabyte or two on disk and
     * gigabytes once decoded, on a pool the whole IDE shares. Past the budget every n-th pixel is read.
     */
    @Test
    fun `a picture too big to decode whole is read at a fraction of its size`() {
        assertEquals(1, ImagePreviews.subsampling(6016, 3384))
        assertEquals(1, ImagePreviews.subsampling(5000, 5000))
        assertEquals(4, ImagePreviews.subsampling(20_000, 20_000))
        assertEquals(2, ImagePreviews.subsampling(8064, 6048))

        val (width, height) = ImagePreviews.decodedSize(20_000, 20_000)
        assertTrue(width.toLong() * height <= ImagePreviews.MAX_DECODED_PIXELS)
    }

    /** A JPEG with the camera's note on how it was held - EXIF orientation, the one tag a browser obeys. */
    private fun exifJpeg(width: Int, height: Int, orientation: Int, littleEndian: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val random = Random(11)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt())
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()

        fun u16(value: Int) = if (littleEndian) byteArrayOf(value.toByte(), (value shr 8).toByte()) else byteArrayOf((value shr 8).toByte(), value.toByte())
        fun u32(value: Int) = if (littleEndian) u16(value) + u16(value shr 16) else u16(value shr 16) + u16(value)
        val tiff = (if (littleEndian) "II".toByteArray() else "MM".toByteArray()) + u16(42) + u32(8) +
            u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun `the camera's note on how it was held is read in either byte order`() {
        assertEquals(6, ImagePreviews.orientationOf(exifJpeg(8, 4, 6)))
        assertEquals(8, ImagePreviews.orientationOf(exifJpeg(8, 4, 8, littleEndian = true)))
        assertEquals(1, ImagePreviews.orientationOf(png(4, 4)))
        assertEquals(1, ImagePreviews.orientationOf(ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()))
        assertEquals(1, ImagePreviews.orientationOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 2)))
    }

    /* Which way, not only that the sides swap: the top row of the picture goes to the right for 6, to the left for 8. */
    @Test
    fun `a turn goes the way the camera's note says`() {
        val red = 0xFF0000
        val blue = 0x0000FF
        val row = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, red)
            setRGB(1, 0, blue)
        }
        val colours = { image: BufferedImage -> List(image.height) { y -> List(image.width) { x -> image.getRGB(x, y) and 0xFFFFFF } } }

        assertEquals(listOf(listOf(red), listOf(blue)), colours(ImagePreviews.upright(row, 6, BufferedImage.TYPE_INT_RGB)))
        assertEquals(listOf(listOf(blue), listOf(red)), colours(ImagePreviews.upright(row, 8, BufferedImage.TYPE_INT_RGB)))
        assertEquals(listOf(listOf(blue, red)), colours(ImagePreviews.upright(row, 3, BufferedImage.TYPE_INT_RGB)))
        assertEquals(listOf(listOf(blue, red)), colours(ImagePreviews.upright(row, 2, BufferedImage.TYPE_INT_RGB)))
    }

    /* A phone held upright writes a landscape picture and a note to turn it: the browser turns the whole one, and the copy has to match. */
    @Test
    fun `a photo held upright is shrunk the way it was held`() {
        val bytes = exifJpeg(2000, 1500, orientation = 6)
        assertTrue(bytes.size > ImagePreviews.SHRINK_ABOVE_BYTES, "the fixture has to be past the threshold")

        val fitted = ImagePreviews.fitted(ImagePreviews.Picture(bytes, "image/jpeg"))!!
        val back = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(fitted.url.substringAfter(","))))

        assertEquals(900 to ImagePreviews.LONGEST_SIDE, back.width to back.height)
        assertEquals(1500 to 2000, fitted.width to fitted.height)
    }

    @Test
    fun `a large picture comes back shrunk to the preview's size`() {
        val bytes = png(1600, 1000, noise = true)
        assertTrue(bytes.size > ImagePreviews.SHRINK_ABOVE_BYTES, "the fixture has to be past the threshold")

        val fitted = ImagePreviews.fitted(ImagePreviews.Picture(bytes, "image/png"))!!
        assertTrue(fitted.url.startsWith("data:image/jpeg;base64,"), "no transparency, so a JPEG")

        val back = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(fitted.url.substringAfter(","))))
        assertEquals(ImagePreviews.LONGEST_SIDE, back.width)
        assertEquals(750, back.height)
        // The caption under the preview says how large the picture is, not its copy.
        assertEquals(1600 to 1000, fitted.width to fitted.height)
    }
}
