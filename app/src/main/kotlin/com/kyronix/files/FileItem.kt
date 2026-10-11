package com.kyronix.files

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Immutable model for a single file or directory entry.
 * All display strings are computed at construction time so the adapter
 * never touches the filesystem on the main thread.
 */
data class FileItem(val file: File) {

    val name: String         = file.name
    val isDirectory: Boolean = file.isDirectory
    val size: Long           = if (isDirectory) 0L else file.length()
    val lastModified: Long   = file.lastModified()
    val fileType: FileType   = if (isDirectory) FileType.FOLDER else detectType(name)

    val displaySize: String = if (isDirectory) "" else formatSize(size)
    val displayDate: String = formatDate(lastModified)

    fun iconResId(): Int = fileType.iconRes

    // ── Type detection ────────────────────────────────────────────────────────

    companion object {

        private val DATE_FMT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

        fun detectType(filename: String): FileType {
            val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                // Images
                "jpg", "jpeg", "png", "gif", "webp",
                "bmp", "heic", "heif", "svg", "ico"          -> FileType.IMAGE

                // Video
                "mp4", "mkv", "avi", "mov", "wmv",
                "flv", "webm", "m4v", "3gp", "ts"            -> FileType.VIDEO

                // Audio
                "mp3", "aac", "wav", "flac", "ogg",
                "m4a", "wma", "opus", "mid", "midi"           -> FileType.AUDIO

                // Documents
                "pdf", "doc", "docx", "xls", "xlsx",
                "ppt", "pptx", "odt", "ods", "odp", "rtf"   -> FileType.DOCUMENT

                // APK
                "apk", "apks", "xapk"                        -> FileType.APK

                // Archives
                "zip", "rar", "7z", "tar", "gz",
                "bz2", "xz", "tgz", "cab", "iso"             -> FileType.ARCHIVE

                // Text / code
                "txt", "md", "json", "xml", "html", "htm",
                "css", "js", "java", "py", "kt", "cpp",
                "c", "h", "log", "csv", "yaml", "yml",
                "ini", "cfg", "sh", "bash", "rs", "go"       -> FileType.TEXT

                else                                          -> FileType.UNKNOWN
            }
        }

        fun formatSize(bytes: Long): String = when {
            bytes < 1_024L        -> "$bytes B"
            bytes < 1_048_576L    -> "%.1f KB".format(bytes / 1_024.0)
            bytes < 1_073_741_824L -> "%.1f MB".format(bytes / 1_048_576.0)
            else                  -> "%.2f GB".format(bytes / 1_073_741_824.0)
        }

        fun formatDate(epochMs: Long): String {
            if (epochMs == 0L) return ""
            return synchronized(DATE_FMT) { DATE_FMT.format(Date(epochMs)) }
        }
    }
}

/**
 * File category enum — owns the drawable resource mapping.
 * Using an enum keeps [FileItem] and [FileAdapter] decoupled from R.drawable.
 */
enum class FileType(val iconRes: Int) {
    FOLDER   (R.drawable.ic_file_folder),
    IMAGE    (R.drawable.ic_file_image),
    VIDEO    (R.drawable.ic_file_video),
    AUDIO    (R.drawable.ic_file_audio),
    DOCUMENT (R.drawable.ic_file_document),
    APK      (R.drawable.ic_file_apk),
    ARCHIVE  (R.drawable.ic_file_archive),
    TEXT     (R.drawable.ic_file_text),
    UNKNOWN  (R.drawable.ic_file_unknown),
}
