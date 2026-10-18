package io.github.crmapache.amazingcodex.webview

/**
 * Which files are pictures - one list for the whole plugin.
 *
 * A picture is what the panel's browser shows: the chooser names such a file an `img` chip (see FilePicker), the
 * preview under the pointer reads it (see ImagePreviews), the paste folder writes one under its extension (see
 * PastedFiles). These used to be four lists, and they had drifted: a pasted BMP got its preview, while the chooser
 * and the page called `.bmp` a plain file, and a conversation read back drew it as one.
 *
 * The page keeps a copy of the extensions (PICTURE_EXTENSIONS in the webview's feed/reference.ts) - a file
 * dropped into the field and a path read back off the transcript are named there - and pictureFormats.test.ts
 * holds that copy to this map, both ways. A new format goes in here and there, and the test says if it did not.
 */
internal object PictureFormats {

    /** A picture's extension, and what it is called in a data URL. */
    private val MEDIA_TYPES = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
    )

    /** The media type of a picture with this extension - null for anything that is not a picture. */
    fun mediaTypeOf(extension: String?): String? = extension?.let { MEDIA_TYPES[it.lowercase()] }

    fun isPicture(extension: String?): Boolean = mediaTypeOf(extension) != null

    /** The extension a picture of this media type is written under - the first of its names, `jpg` for a JPEG. */
    val EXTENSIONS: Map<String, String> =
        MEDIA_TYPES.entries.groupBy({ it.value }, { it.key }).mapValues { (_, names) -> names.first() }
}
