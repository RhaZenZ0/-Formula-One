package com.rhazenz0.f1info.data.update

import com.rhazenz0.f1info.data.timing.FeedCodec
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.elements
import com.rhazenz0.f1info.util.text
import com.rhazenz0.f1info.util.textOrNull
import java.time.Instant

/** A published release of the app on GitHub. */
data class AppRelease(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 of the APK, when GitHub publishes one. */
    val sha256: String?,
    val pageUrl: String,
    val publishedAt: Instant?,
)

object AppUpdates {
    /** GitHub follows renames with a redirect, so this keeps working if the repository moves. */
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/RhaZenZ0/Formula-One/releases/latest"
    const val RELEASES_PAGE = "https://github.com/RhaZenZ0/Formula-One/releases"

    /** Parses GitHub's "latest release" JSON. Returns null for drafts or releases without an APK. */
    fun parseRelease(json: String): AppRelease? {
        val root = FeedCodec.parseJson(json)
        if (root.at("draft").text() == "true" || root.at("prerelease").text() == "true") return null
        val tag = root.at("tag_name").textOrNull() ?: return null
        val version = normalise(tag) ?: return null
        val apk = root.at("assets").elements().firstOrNull { a ->
            a.at("name").text().orEmpty().endsWith(".apk", ignoreCase = true) &&
                a.at("state").text().let { it == null || it == "uploaded" }
        } ?: return null
        val digest = apk.at("digest").textOrNull()
        return AppRelease(
            version = version,
            tag = tag,
            notes = cleanNotes(root.at("body").text().orEmpty()),
            apkUrl = apk.at("browser_download_url").textOrNull() ?: return null,
            apkName = apk.at("name").text().orEmpty(),
            apkSize = apk.at("size").text()?.toLongOrNull() ?: -1L,
            sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            pageUrl = root.at("html_url").textOrNull() ?: RELEASES_PAGE,
            publishedAt = com.rhazenz0.f1info.util.TimeFormat.parseUtc(root.at("published_at").text()),
        )
    }

    /** "v1.2.0" or "1.2.0-debug" -> "1.2.0". */
    fun normalise(version: String): String? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        return core
    }

    /** True when [candidate] is a higher version than [current]. Unparseable versions are never newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = normalise(candidate)?.split('.')?.map { it.toInt() } ?: return false
        val b = normalise(current)?.split('.')?.map { it.toInt() } ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Release notes as GitHub writes them, minus links and the contributor boilerplate. */
    internal fun cleanNotes(body: String): String =
        body.lines()
            .filterNot { it.startsWith("**Full Changelog**") || it.startsWith("## New Contributors") || it.contains("made their first contribution") }
            .map { line ->
                line.replace(Regex("""\s+by @\S+ in https://\S+"""), "")
                    .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                    .replace(Regex("""^#+\s*"""), "")
                    .replace(Regex("""^\*\s+"""), "• ")
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
