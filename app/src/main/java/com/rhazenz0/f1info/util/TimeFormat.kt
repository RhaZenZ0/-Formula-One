package com.rhazenz0.f1info.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object TimeFormat {
    private fun pattern(p: String): DateTimeFormatter = DateTimeFormatter.ofPattern(p, Locale.getDefault())

    /** Parses feed timestamps such as "2026-10-11T11:07:37.8131343Z" or "2026-10-11T11:11:21" (UTC). */
    fun parseUtc(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** Parses "08:00:00" or "-04:00:00" into an offset. */
    fun parseGmtOffset(text: String?): ZoneOffset {
        if (text.isNullOrBlank()) return ZoneOffset.UTC
        val negative = text.startsWith("-")
        val parts = text.trimStart('-', '+').split(":").mapNotNull { it.toIntOrNull() }
        val seconds = (parts.getOrElse(0) { 0 } * 3600) + (parts.getOrElse(1) { 0 } * 60) + parts.getOrElse(2) { 0 }
        return runCatching { ZoneOffset.ofTotalSeconds(if (negative) -seconds else seconds) }.getOrDefault(ZoneOffset.UTC)
    }

    /** Converts a track-local "2026-10-11T20:00:00" plus GMT offset to an instant. */
    fun trackLocalToInstant(local: String?, gmtOffset: String?): Instant? {
        if (local.isNullOrBlank()) return null
        val ldt = runCatching { LocalDateTime.parse(local) }.getOrNull() ?: return parseUtc(local)
        return ldt.toInstant(parseGmtOffset(gmtOffset))
    }

    /** Parses a stream offset such as "01:02:45.042" into milliseconds. */
    fun parseStreamOffset(text: String): Long? {
        val parts = text.split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).roundToLong()
    }

    /** Parses "1:38.977", "28.988" or "+1.234" into seconds. */
    fun parseLapTime(text: String?): Double? {
        val t = text?.trim()?.trimStart('+') ?: return null
        if (t.isEmpty()) return null
        val parts = t.split(":")
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                m * 60 + s
            }
            3 -> {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                h * 3600 + m * 60 + s
            }
            else -> null
        }
    }

    /** 98.466 -> "1:38.466"; 28.9 -> "28.900". */
    fun formatLapTime(seconds: Double?): String {
        if (seconds == null || seconds.isNaN()) return "—"
        val totalMillis = (seconds * 1000).roundToLong()
        val minutes = totalMillis / 60_000
        val secs = (totalMillis % 60_000) / 1000.0
        return if (minutes > 0) String.format(Locale.US, "%d:%06.3f", minutes, secs)
        else String.format(Locale.US, "%.3f", secs)
    }

    fun formatDelta(seconds: Double?): String {
        if (seconds == null) return ""
        val sign = if (seconds >= 0) "+" else "-"
        return sign + String.format(Locale.US, "%.3f", abs(seconds))
    }

    fun localDate(instant: Instant?, withYear: Boolean = false): String =
        instant?.atZone(ZoneId.systemDefault())?.format(pattern(if (withYear) "EEE d MMM yyyy" else "EEE d MMM")) ?: "TBA"

    fun localTime(instant: Instant?, seconds: Boolean = false): String =
        instant?.atZone(ZoneId.systemDefault())?.format(pattern(if (seconds) "HH:mm:ss" else "HH:mm")) ?: ""

    /** "3d 04h 12m" style countdown. */
    fun countdown(until: Instant, now: Instant = Instant.now()): String {
        val d = Duration.between(now, until)
        if (d.isNegative) return "Now"
        val days = d.toDays()
        val hours = d.toHours() % 24
        val minutes = d.toMinutes() % 60
        val secs = d.seconds % 60
        return when {
            days > 0 -> String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes)
            hours > 0 -> String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, secs)
            else -> String.format(Locale.US, "%dm %02ds", minutes, secs)
        }
    }
}
