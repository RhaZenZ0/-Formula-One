package com.rhazenz0.f1info.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AppUpdatesTest {
    private fun fixture() = javaClass.classLoader!!.getResource("github_latest_release.json")!!.readText()

    @Test
    fun parsesRealReleaseResponse() {
        val r = AppUpdates.parseRelease(fixture())!!
        assertEquals("1.1.0", r.version)
        assertEquals("v1.1.0", r.tag)
        assertEquals("F1-Info-1.1.0.apk", r.apkName)
        assertEquals(1_535_994L, r.apkSize)
        assertEquals("bef4abd317bf4c07a48f7e7266894e1a6389eabb2dc088deef37e8c3741e5acf", r.sha256)
        assertEquals("https://github.com/RhaZenZ0/Formula-One/releases/download/v1.1.0/F1-Info-1.1.0.apk", r.apkUrl)
        assertEquals(Instant.parse("2026-10-13T06:46:26Z"), r.publishedAt)
        assertEquals(
            "What's Changed\n• Add F1 Info Android app (Kotlin, Jetpack Compose)\n• Add live track map, race replay and GitHub release builds",
            r.notes,
        )
    }

    @Test
    fun ignoresDraftsAndReleasesWithoutApk() {
        assertNull(AppUpdates.parseRelease("""{"tag_name":"v2.0.0","draft":true,"assets":[{"name":"a.apk","browser_download_url":"x"}]}"""))
        assertNull(AppUpdates.parseRelease("""{"tag_name":"v2.0.0","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"x"}]}"""))
        assertNull(AppUpdates.parseRelease("""{"tag_name":"v2.0.0","assets":[{"name":"notes.txt","browser_download_url":"x"}]}"""))
        assertNull(AppUpdates.parseRelease("""{"tag_name":"latest","assets":[{"name":"a.apk","browser_download_url":"x"}]}"""))
    }

    @Test
    fun comparesVersions() {
        assertTrue(AppUpdates.isNewer("v1.2.0", "1.1.0"))
        assertTrue(AppUpdates.isNewer("1.10.0", "1.9.3"))
        assertTrue(AppUpdates.isNewer("2.0", "1.99.99"))
        assertTrue(AppUpdates.isNewer("1.1.1", "1.1"))
        assertFalse(AppUpdates.isNewer("1.1.0", "1.1.0"))
        assertFalse(AppUpdates.isNewer("1.1.0", "1.1.0-debug"))
        assertFalse(AppUpdates.isNewer("1.0.9", "1.1.0"))
        assertFalse(AppUpdates.isNewer("nightly", "1.1.0"))
        assertTrue(AppUpdates.isNewer("1.0.0", "unknown"))
    }

    @Test
    fun hexEncodesDigests() {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateInstaller.hex(sha))
    }
}
