package com.rhazenz0.f1info.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.rhazenz0.f1info.data.net.Http
import com.rhazenz0.f1info.data.net.Http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Downloads a release APK, checks it, and hands it to Android's package installer. */
class UpdateInstaller(context: Context) {
    private val context: Context = context.applicationContext

    private val dir = File(context.cacheDir, "updates")

    /** Downloads [release] to the cache, reporting progress from 0 to 1. Verifies size and SHA-256. */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // keep only one update around
        val target = File(dir, "F1-Info-${release.version}.apk")
        val request = Request.Builder().url(release.apkUrl).build()
        Http.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (${response.code}).")
            val body = response.body ?: throw IOException("Download was empty.")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    copy(input, output, digest, total) { done ->
                        ensureActive()
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            verify(target, release, digest.digest())
        }
        target
    }

    private inline fun copy(input: InputStream, output: OutputStream, digest: MessageDigest, total: Long, progress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            done += n
            progress(done)
        }
        if (total > 0 && done != total) throw IOException("Download was incomplete.")
    }

    private fun verify(file: File, release: AppRelease, sha: ByteArray) {
        if (release.apkSize > 0 && file.length() != release.apkSize) {
            file.delete()
            throw IOException("The downloaded file has the wrong size.")
        }
        val expected = release.sha256
        if (expected != null && hex(sha) != expected) {
            file.delete()
            throw IOException("The downloaded file failed its security check (SHA-256 mismatch).")
        }
    }

    /** Starts an install session; Android then asks the user to confirm. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: once this app has installed itself, later updates may skip the prompt.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
