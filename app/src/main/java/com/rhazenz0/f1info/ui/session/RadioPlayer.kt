package com.rhazenz0.f1info.ui.session

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Plays team radio clips (MP3 files streamed from the timing archive). One clip at a time. */
class RadioPlayer {
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing
    private val _loading = MutableStateFlow<String?>(null)
    val loading: StateFlow<String?> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private var player: MediaPlayer? = null

    fun toggle(url: String) {
        if (_playing.value == url || _loading.value == url) stop() else play(url)
    }

    private fun play(url: String) {
        stop()
        _error.value = null
        _loading.value = url
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (player === it) {
                    it.start()
                    _loading.value = null
                    _playing.value = url
                }
            }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) {
                    stop()
                    _error.value = "Could not play this clip."
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            stop()
            _error.value = "Could not play this clip."
        }
    }

    fun stop() {
        player?.let { runCatching { it.reset() }; it.release() }
        player = null
        _playing.value = null
        _loading.value = null
    }
}
