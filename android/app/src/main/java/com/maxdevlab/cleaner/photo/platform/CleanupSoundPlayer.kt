package com.maxdevlab.cleaner.photo.platform

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.maxdevlab.cleaner.photo.R

/** Preloaded completion cue, foreground only. Never changes volume or interrupts other audio. */
class CleanupSoundPlayer(context: Context) : DefaultLifecycleObserver {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val loaded = mutableSetOf<Int>()
    private var resumed = false
    private var closed = false
    private var stream = 0
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build().apply { setOnLoadCompleteListener { _, sample, status -> if (status == 0) loaded += sample } }
    private val complete = pool.load(context, R.raw.cleanup_complete, 1)

    fun play() {
        if (closed || !resumed) return
        if (complete !in loaded) return // Late loading must not replay old actions.
        // These reads are cheap platform calls; audio failure must never affect deletion.
        runCatching {
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.isMusicActive ||
                audio.getStreamVolume(AudioManager.STREAM_SYSTEM) == 0 ||
                notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
            stop()
            stream = pool.play(complete, .65f, .65f, 1, 0, 1f)
        }
    }
    private fun stop() { if (stream != 0) pool.stop(stream); stream = 0 }
    override fun onResume(owner: LifecycleOwner) { resumed = true }
    override fun onPause(owner: LifecycleOwner) { resumed = false; stop() }
    override fun onDestroy(owner: LifecycleOwner) { closed = true; pool.release(); loaded.clear() }
}
