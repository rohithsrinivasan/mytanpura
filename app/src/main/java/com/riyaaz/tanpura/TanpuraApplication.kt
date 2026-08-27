package com.riyaaz.tanpura

import android.app.Application
import com.riyaaz.tanpura.data.SettingsStore
import com.riyaaz.tanpura.playback.PlaybackController
import com.riyaaz.tanpura.service.TanpuraService

/**
 * Holds the audio engine for the lifetime of the process.
 *
 * Keeping the controller here (rather than in the Activity or the Service) is
 * what makes rotation, back-press and notification control all act on the same
 * running drone.
 */
class TanpuraApplication : Application() {

    val store: SettingsStore by lazy { SettingsStore(this) }

    val controller: PlaybackController by lazy {
        PlaybackController(this, store).also { c ->
            c.onPlaybackStateChanged = { playing ->
                if (playing) TanpuraService.notifyPlaying(this) else TanpuraService.notifyPaused(this)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Touch the controller so settings start loading before the first frame.
        controller
    }
}
