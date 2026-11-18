package com.example.tagtune2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

data class QItem(val uri: String, val title: String, val artist: String) : java.io.Serializable

object PlayerState {
    var uri: String? = null
    var title = ""
    var artist = ""
    var playing = false
    var durationMs = 0
    val listeners = mutableListOf<() -> Unit>()
    fun emit() { listeners.toList().forEach { it() } }
}

class PlayerService : Service() {

    companion object {
        var instance: PlayerService? = null
        const val CH = "tagtune2_play"
        const val ID = 7
        fun cmd(c: Context, action: String, queue: ArrayList<QItem>? = null, index: Int = 0, seekMs: Int = -1) {
            val i = Intent(c, PlayerService::class.java).setAction(action)
            if (queue != null) { i.putExtra("queue", queue); i.putExtra("index", index) }
            if (seekMs >= 0) i.putExtra("seek", seekMs)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }

    var player: MediaPlayer? = null
    private var queue = listOf<QItem>()
    private var index = 0
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Воспроизведение", NotificationManager.IMPORTANCE_LOW))
        }
        session = MediaSessionCompat(this, "TagTune2")
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { resume() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { prev() }
            override fun onSeekTo(pos: Long) { seek(pos.toInt()) }
        })
        session.isActive = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "play" -> {
                queue = (intent.getSerializableExtra("queue") as? ArrayList<*>)?.filterIsInstance<QItem>() ?: queue
                index = intent.getIntExtra("index", 0)
                playCurrent()
            }
            "toggle" -> if (player?.isPlaying == true) pause() else resume()
            "next" -> next()
            "prev" -> prev()
            "seek" -> seek(intent.getIntExtra("seek", 0))
            "stop" -> stopAll()
        }
        return START_NOT_STICKY
    }

    fun currentPosition(): Int = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }

    private fun playCurrent() {
        val item = queue.getOrNull(index) ?: return
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(this@PlayerService, Uri.parse(item.uri))
            setOnPreparedListener {
                start()
                PlayerState.durationMs = duration
                updateMetadata(item)
                updateState()
                startFg()
                PlayerState.emit()
            }
            setOnCompletionListener { next() }
            prepareAsync()
        }
        PlayerState.uri = item.uri
        PlayerState.title = item.title
        PlayerState.artist = item.artist
        PlayerState.playing = true
        updateMetadata(item)
        updateState()
        startFg()
        PlayerState.emit()
    }

    private fun pause() {
        player?.pause()
        PlayerState.playing = false
        updateState()
        stopForeground(false)
        try { NotificationManagerCompat.from(this).notify(ID, buildNotif()) } catch (e: Exception) { }
        PlayerState.emit()
    }

    private fun resume() {
        player?.start()
        PlayerState.playing = true
        updateState()
        startFg()
        PlayerState.emit()
    }

    private fun next() { if (queue.isNotEmpty()) { index = (index + 1) % queue.size; playCurrent() } }
    private fun prev() { if (queue.isNotEmpty()) { index = (index - 1 + queue.size) % queue.size; playCurrent() } }
    private fun seek(ms: Int) { try { player?.seekTo(ms); updateState() } catch (e: Exception) { } }

    private fun stopAll() {
        player?.release(); player = null
        PlayerState.playing = false; PlayerState.uri = null; PlayerState.durationMs = 0
        session.isActive = false
        stopForeground(true)
        stopSelf()
        PlayerState.emit()
    }

    private fun startFg() {
        ServiceCompat.startForeground(this, ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun updateState() {
        val st = if (PlayerState.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(st, currentPosition().toLong(), 1f)
                .build()
        )
    }

    private fun updateMetadata(item: QItem) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (player?.duration ?: 0).toLong())
                .build()
        )
    }

    private fun pi(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotif(): android.app.Notification =
        NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(PlayerState.title)
            .setContentText(PlayerState.artist)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(android.R.drawable.ic_media_previous, "Назад", pi("prev"))
            .addAction(
                if (PlayerState.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (PlayerState.playing) "Пауза" else "Играть",
                pi("toggle")
            )
            .addAction(android.R.drawable.ic_media_next, "Вперёд", pi("next"))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setOngoing(PlayerState.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    override fun onDestroy() {
        instance = null
        player?.release()
        session.release()
        super.onDestroy()
    }
}
