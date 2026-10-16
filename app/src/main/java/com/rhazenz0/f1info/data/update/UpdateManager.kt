package com.rhazenz0.f1info.data.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rhazenz0.f1info.BuildConfig
import com.rhazenz0.f1info.data.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    /** The APK is with Android's installer, waiting for the user to confirm. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val release: AppRelease?, val message: String) : UpdateState
}

/** Checks GitHub Releases for a newer version and installs it from inside the app. */
object UpdateManager {
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val PREF_AUTO = "update_auto_check"
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val PREF_DISMISSED = "update_dismissed_version"
    private const val PREF_AUTO_INSTALL = "update_auto_install"
    private const val PREF_NO_AUTO_RETRY = "update_no_auto_retry_version"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: SharedPreferences
    // Holds only the application context, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    private lateinit var installer: UpdateInstaller
    private var checkJob: Job? = null
    private var installJob: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _dismissed = MutableStateFlow<String?>(null)
    /** Version the user chose to skip on the Home banner. */
    val dismissedVersion: StateFlow<String?> = _dismissed

    private val _launchPrompt = MutableStateFlow<AppRelease?>(null)
    /** An update found at launch that the user should be asked about (when not installing automatically). */
    val launchPrompt: StateFlow<AppRelease?> = _launchPrompt

    /** Test seams: where the latest release comes from, and what "install" does. */
    internal var fetchLatest: suspend () -> String? = { Http.getString(AppUpdates.LATEST_RELEASE_URL, missingIsNull = true) }
    internal var installOverride: ((AppRelease) -> Unit)? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        reset(context)
    }

    internal fun reset(context: Context) {
        checkJob?.cancel()
        installJob?.cancel()
        prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        installer = UpdateInstaller(context.applicationContext)
        _dismissed.value = prefs.getString(PREF_DISMISSED, null)
        _state.value = UpdateState.Idle
        _launchPrompt.value = null
    }

    var autoCheck: Boolean
        get() = !::prefs.isInitialized || prefs.getBoolean(PREF_AUTO, true)
        set(value) { prefs.edit { putBoolean(PREF_AUTO, value) } }

    /** Download and install updates found at launch without asking first. */
    var autoInstall: Boolean
        get() = !::prefs.isInitialized || prefs.getBoolean(PREF_AUTO_INSTALL, true)
        set(value) { prefs.edit { putBoolean(PREF_AUTO_INSTALL, value) } }

    val lastCheckMs: Long get() = if (::prefs.isInitialized) prefs.getLong(PREF_LAST_CHECK, 0L) else 0L

    /** When the app returns to the foreground after a long time, behaves like a fresh launch. */
    fun checkIfDue() {
        if (!::prefs.isInitialized || !autoCheck) return
        if (System.currentTimeMillis() - lastCheckMs < CHECK_INTERVAL_MS) return
        onLaunch()
    }

    /**
     * Called each time the app starts. Checks for an update and, when one is found, either installs
     * it straight away or asks, depending on [autoInstall]. A version the user skipped is left alone.
     */
    fun onLaunch() {
        if (!::prefs.isInitialized || !autoCheck) return
        check { release ->
            if (release.version == _dismissed.value) return@check
            // A version whose automatic install failed or was cancelled is only offered, not retried.
            val noRetry = prefs.getString(PREF_NO_AUTO_RETRY, null) == release.version
            if (autoInstall && !noRetry) install(release) else _launchPrompt.value = release
        }
    }

    fun clearLaunchPrompt() {
        _launchPrompt.value = null
    }

    fun check(onFound: ((AppRelease) -> Unit)? = null) {
        if (checkJob?.isActive == true || installJob?.isActive == true) return
        _state.value = UpdateState.Checking
        checkJob = scope.launch {
            var found: AppRelease? = null
            _state.value = try {
                val text = fetchLatest()
                prefs.edit { putLong(PREF_LAST_CHECK, System.currentTimeMillis()) }
                val release = text?.let { AppUpdates.parseRelease(it) }
                if (release != null && AppUpdates.isNewer(release.version, currentVersion)) {
                    found = release
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate(System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(null, "Could not check for updates. ${e.message.orEmpty()}".trim())
            }
            found?.let { release -> onFound?.invoke(release) }
        }
    }

    fun install(release: AppRelease) {
        _launchPrompt.value = null
        installOverride?.let { it(release); return }
        if (installJob?.isActive == true) return
        _state.value = UpdateState.Downloading(release, 0f)
        installJob = scope.launch {
            try {
                val apk = installer.download(release) { p -> _state.value = UpdateState.Downloading(release, p) }
                _state.value = UpdateState.Installing(release)
                installer.install(apk)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                prefs.edit { putString(PREF_NO_AUTO_RETRY, release.version) }
                _state.value = UpdateState.Failed(release, e.message ?: "The update failed.")
            }
        }
    }

    fun cancel() {
        installJob?.cancel()
    }

    fun dismiss(release: AppRelease) {
        _dismissed.value = release.version
        prefs.edit { putString(PREF_DISMISSED, release.version) }
    }

    // ---- Called by InstallResultReceiver ----

    internal fun onInstallerWaiting() {
        val current = _state.value
        if (current is UpdateState.Downloading) _state.value = UpdateState.Installing(current.release)
    }

    internal fun onInstalled() {
        // The system restarts the app after replacing it; this only shows if it does not.
        _state.value = UpdateState.UpToDate(System.currentTimeMillis())
    }

    internal fun setStateForTest(state: UpdateState) {
        _state.value = state
    }

    /** Waits for a running check to finish (tests). */
    internal suspend fun awaitCheck() {
        checkJob?.join()
    }

    internal fun onInstallFailed(message: String?) {
        state.value.let { s -> (s as? UpdateState.Installing)?.release ?: (s as? UpdateState.Downloading)?.release }
            ?.let { prefs.edit { putString(PREF_NO_AUTO_RETRY, it.version) } }
        val release = when (val s = _state.value) {
            is UpdateState.Installing -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        }
        _state.value = if (message == null && release != null) UpdateState.Available(release)
        else UpdateState.Failed(release, message ?: "The update was cancelled.")
    }
}
