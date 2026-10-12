package com.rhazenz0.f1info

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rhazenz0.f1info.data.jolpica.JolpicaClient
import com.rhazenz0.f1info.data.net.Http
import com.rhazenz0.f1info.data.timing.ArchiveClient
import com.rhazenz0.f1info.data.timing.CircuitClient
import com.rhazenz0.f1info.data.timing.LiveTimingClient
import com.rhazenz0.f1info.data.timing.LoadedSession
import com.rhazenz0.f1info.data.timing.MeetingRef
import com.rhazenz0.f1info.data.timing.SessionRef
import com.rhazenz0.f1info.data.timing.TelemetryData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Simple service locator: one instance of each client for the whole app. */
object AppGraph {
    private lateinit var prefs: SharedPreferences

    val jolpica = JolpicaClient()
    val archive = ArchiveClient()
    val circuits = CircuitClient()
    val live by lazy { LiveTimingClient(tokenProvider = { f1TvToken }) }

    fun init(context: Context) {
        Http.init(context.cacheDir)
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** Optional F1 TV subscription token that unlocks live car telemetry and positions. */
    var f1TvToken: String?
        get() = if (::prefs.isInitialized) prefs.getString("f1tv_token", null) else null
        set(value) {
            prefs.edit { putString("f1tv_token", value?.trim()?.ifBlank { null }) }
        }

    // ---- Small in-memory caches so navigating back and forth is instant ----

    private val meetingsLock = Mutex()
    private val meetings = HashMap<Int, Pair<Long, List<MeetingRef>>>()

    suspend fun meetings(year: Int, force: Boolean = false): List<MeetingRef> = meetingsLock.withLock {
        val cached = meetings[year]
        val fresh = cached != null && System.currentTimeMillis() - cached.first < 10 * 60 * 1000
        if (!force && fresh) return cached!!.second
        val list = archive.meetings(year)
        meetings[year] = System.currentTimeMillis() to list
        list
    }

    suspend fun sessionRef(year: Int, path: String): SessionRef? =
        meetings(year).flatMap { it.sessions }.firstOrNull { it.path == path }

    private val sessionLock = Mutex()
    private val sessions = object : LinkedHashMap<String, LoadedSession>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedSession>?) = size > 3
    }

    suspend fun loadSession(ref: SessionRef, force: Boolean, onProgress: (String) -> Unit): LoadedSession {
        if (!force) sessionLock.withLock { sessions[ref.path] }?.let { return it }
        val loaded = archive.loadSession(ref, onProgress)
        sessionLock.withLock { sessions[ref.path] = loaded }
        return loaded
    }

    private val telemetryLock = Mutex()
    private var telemetryCache: Pair<String, TelemetryData>? = null

    fun cachedTelemetry(ref: SessionRef): TelemetryData? = telemetryCache?.takeIf { it.first == ref.path }?.second

    suspend fun telemetry(ref: SessionRef, onProgress: (String) -> Unit): TelemetryData = telemetryLock.withLock {
        cachedTelemetry(ref)?.let { return it }
        telemetryCache = null // free memory before decoding the next one
        val data = archive.telemetry(ref, onProgress)
        telemetryCache = ref.path to data
        data
    }
}
