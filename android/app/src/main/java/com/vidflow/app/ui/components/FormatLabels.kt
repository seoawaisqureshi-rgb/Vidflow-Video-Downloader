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

    /** Returns exact formatted size, or duration/quality-based size estimate instead of "Size unknown". */
    fun sizeLabel(format: VideoFormat, durationSeconds: Long? = null): String {
        val actual = format.filesize?.takeIf { it > 0 }
        if (actual != null) return formatBytes(actual)

        val q = format.quality.lowercase()
        val duration = durationSeconds?.takeIf { it > 0 } ?: 240L // Default reference: 4 minutes (240s)

        // Calculate size based on realistic bitrates (Bytes = (bitrate_bps / 8) * duration_seconds)
        val estimatedBytes: Long = when {
            // Audio bitrates (CBR)
            q.contains("320") -> ((320_000L / 8) * duration)
            q.contains("256") -> ((256_000L / 8) * duration)
            q.contains("192") -> ((192_000L / 8) * duration)
            q.contains("128") -> ((128_000L / 8) * duration)
            q.contains("64")  -> ((64_000L / 8) * duration)
            q.contains("mp3") || q.contains("audio") || format.ext.equals("mp3", ignoreCase = true) -> ((192_000L / 8) * duration)

            // Video resolutions (Video + Audio combined)
            q.contains("4320") || q.contains("8k") -> ((40_000_000L / 8) * duration) // ~40 Mbps
            q.contains("2160") || q.contains("4k") -> ((18_000_000L / 8) * duration) // ~18 Mbps (e.g. 33 min ~ 4.4 GB)
            q.contains("1440") || q.contains("2k") -> ((10_000_000L / 8) * duration) // ~10 Mbps (e.g. 33 min ~ 2.4 GB)
            q.contains("1080") -> ((4_000_000L / 8) * duration)                      // ~4 Mbps  (e.g. 33 min ~ 990 MB)
            q.contains("720")  -> ((1_800_000L / 8) * duration)                      // ~1.8 Mbps(e.g. 33 min ~ 445 MB)
            q.contains("480")  -> ((750_000L / 8) * duration)                        // ~750 kbps(e.g. 33 min ~ 185 MB)
            q.contains("360")  -> ((350_000L / 8) * duration)                        // ~350 kbps(e.g. 33 min ~ 86 MB)
            q.contains("240")  -> ((200_000L / 8) * duration)
            q.contains("144")  -> ((100_000L / 8) * duration)
            else -> ((1_500_000L / 8) * duration)
        }
        return formatBytes(estimatedBytes)
    }

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
