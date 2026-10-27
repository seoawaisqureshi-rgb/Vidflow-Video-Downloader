package com.vidflow.app.ui.components

import com.vidflow.app.data.model.VideoFormat

/**
 * One-line, honest format labels for the quality picker.
 * Rules: never invent a quality or a size. A size is shown only when the
 * source actually returned one; otherwise the row says "Size unknown"
 * instead of guessing.
 */
object FormatLabels {

    /** "1080p HD" stays as-is; bare heights become "720p". */
    fun qualityLabel(format: VideoFormat): String {
        val q = format.quality.trim()
        if (q.isEmpty()) return "Best available"
        return q
    }

    /** "MP4 • Video+Audio • 84.2 MB" / "MP3 • Audio only • Size unknown". */
    fun metaLine(format: VideoFormat): String {
        val parts = mutableListOf<String>()
        parts.add(format.ext.uppercase().ifEmpty { "MP4" })
        when {
            format.hasVideo && format.hasAudio -> parts.add("Video+Audio")
            format.hasVideo -> parts.add("Video only")
            format.hasAudio -> parts.add("Audio only")
        }
        format.fps?.takeIf { it > 0 }?.let { parts.add("${it}fps") }
        if (format.needsYtDlp) parts.add("Merged")
        return parts.joinToString(" • ")
    }

    /** "84.2 MB" or "Size unknown" — never a fabricated number. */
    fun sizeLabel(format: VideoFormat): String =
        format.filesize?.takeIf { it > 0 }?.let { formatBytes(it) } ?: "Size unknown"

    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024
        return when {
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            kb >= 1 -> "${kb} KB"
            else -> "${bytes} B"
        }
    }

    /** "2.4 MB/s" / "842 KB/s" / "" when there is no speed to show. */
    fun speedLabel(speedBps: Long): String {
        if (speedBps <= 0) return ""
        val kbps = speedBps / 1024
        return if (kbps >= 1024) "%.1f MB/s".format(kbps / 1024.0) else "$kbps KB/s"
    }
}
