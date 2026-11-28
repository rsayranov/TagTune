package com.example.tagtune2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QItem(val uri: String, val title: String, val artist: String) : java.io.Serializable

object PlayerState {
    var uri: String? = null
    var title = ""
    var artist = ""
    var playing = false
    var durationMs = 0
    var error: String? = null
    var queueSize = 0
    var queuePos = 0
    var queueItems: List<QItem> = emptyList()
    var shuffleOn = true
    var repeatMode = 0
    var coverPath: String? = null
    val listeners = mutableListOf<() -> Unit>()
    fun emit() { listeners.toList().forEach { it() } }
}

object Stats {
    fun record(ctx: Context, uri: String) {
        Thread {
            try {
                val f = File(ctx.filesDir, "stats.json")
                val jo = if (f.exists()) {
                    val s = f.readText()
                    if (s.isBlank()) JSONObject() else JSONObject(s)
                } else JSONObject()
                val entry = if (jo.has(uri)) jo.getJSONObject(uri) else JSONObject()
                entry.put("count", (entry.optInt("count", 0) + 1))
                entry.put("last", System.currentTimeMillis())
                jo.put(uri, entry)
                f.writeText(jo.toString())
            } catch (_: Exception) { }
        }.start()
    }

    fun load(ctx: Context): Map<String, Pair<Int, Long>> {
        val f = File(ctx.filesDir, "stats.json")
        if (!f.exists()) return emptyMap()
        return try {
            val jo = JSONObject(f.readText())
            val out = mutableMapOf<String, Pair<Int, Long>>()
            jo.keys().forEach { k ->
                val e = jo.getJSONObject(k)
                out[k] = Pair(e.optInt("count", 0), e.optLong("last", 0L))
            }
            out
        } catch (_: Exception) { emptyMap() }
    }
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

    /* все мутации плеера — только на главном потоке */
    @Volatile
    private var player: MediaPlayer? = null
    private var queue = listOf<QItem>()
    private var index = 0
    private var currentIndex = -1
    private var savedPos = 0
    private val history = ArrayDeque<Int>()
    private var startedAtMs = 0L
    private var lastPlayAtMs = 0L
    private lateinit var session: MediaSessionCompat
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var iconPath: String? = null
    private var iconBmp: Bitmap? = null
    private val prefs by lazy { getSharedPreferences("player_state", MODE_PRIVATE) }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        val p = player ?: return@OnAudioFocusChangeListener
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { p.setVolume(0.3f, 0.3f) }
            AudioManager.AUDIOFOCUS_GAIN -> runCatching { p.setVolume(1f, 1f) }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
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
        /* обязательство перед системой: startForeground сразу, иначе краш через 5 секунд */
        startFg()
        when (intent?.action) {
            "play" -> {
                val newQ = (intent.getSerializableExtra("queue") as? ArrayList<*>)?.filterIsInstance<QItem>() ?: queue
                if (newQ.map { it.uri } != queue.map { it.uri }) history.clear()
                queue = newQ
                index = intent.getIntExtra("index", 0)
                savedPos = 0
                playCurrent()
            }
            "toggle" -> if (player?.isPlaying == true) pause() else resume()
            "toggleShuffle" -> {
                PlayerState.shuffleOn = !PlayerState.shuffleOn
                persist(currentPosition())
                updateState(); PlayerState.emit()
            }
            "toggleRepeat" -> {
                PlayerState.repeatMode = (PlayerState.repeatMode + 1) % 3
                persist(currentPosition())
                updateState(); PlayerState.emit()
            }
            "next" -> next()
            "prev" -> prev()
            "seek" -> seek(intent.getIntExtra("seek", 0))
            "stop" -> stopAll()
            null -> {
                if (player == null && !restore()) {
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    fun currentPosition(): Int = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    /* ---------- переживание смерти сервиса: очередь и позиция в prefs ---------- */

    private fun persist(posMs: Int) {
        try {
            val o = JSONObject()
            val arr = JSONArray()
            for (q in queue) {
                val e = JSONObject()
                e.put("uri", q.uri); e.put("title", q.title); e.put("artist", q.artist)
                arr.put(e)
            }
            o.put("queue", arr)
            o.put("index", index)
            o.put("pos", posMs)
            o.put("shuffle", PlayerState.shuffleOn)
            o.put("repeat", PlayerState.repeatMode)
            prefs.edit().putString("state", o.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun restore(): Boolean {
        if (queue.isNotEmpty()) return true
        return try {
            val s = prefs.getString("state", null) ?: return false
            val o = JSONObject(s)
            val arr = o.getJSONArray("queue")
            val q = mutableListOf<QItem>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                q.add(QItem(e.getString("uri"), e.getString("title"), e.getString("artist")))
            }
            if (q.isEmpty()) return false
            queue = q
            index = o.optInt("index", 0).coerceIn(0, q.size - 1)
            savedPos = o.optInt("pos", 0)
            PlayerState.shuffleOn = o.optBoolean("shuffle", true)
            PlayerState.repeatMode = o.optInt("repeat", 0)
            PlayerState.queueItems = queue
            PlayerState.queueSize = queue.size
            PlayerState.queuePos = index + 1
            true
        } catch (_: Exception) { false }
    }

    private fun gainFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
    }

    private fun playCurrent(pushHistory: Boolean = true, startPosMs: Int = 0) {
        val item = queue.getOrNull(index) ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayAtMs < 250 && PlayerState.uri == item.uri && startPosMs == 0) return
        lastPlayAtMs = now

        if (pushHistory && currentIndex >= 0 && currentIndex != index) {
            history.addLast(currentIndex)
            while (history.size > 10) history.removeFirst()
        }
        currentIndex = index

        Stats.record(this, item.uri)
        gainFocus()
        releasePlayer()
        PlayerState.uri = item.uri
        PlayerState.title = item.title
        PlayerState.artist = item.artist
        PlayerState.playing = true
        PlayerState.error = null
        PlayerState.queueItems = queue
        PlayerState.queueSize = queue.size
        PlayerState.queuePos = index + 1
        updateState(); startFg(); refreshNotifIcon(); PlayerState.emit()

        Thread {
            var mp: MediaPlayer? = null
            try {
                val m = MediaPlayer()
                mp = m
                m.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                m.setDataSource(this, Uri.parse(item.uri))
                m.setOnCompletionListener {
                    val played = SystemClock.uptimeMillis() - startedAtMs
                    if (played > 1000) mainHandler.post { autoNext() }
                    else mainHandler.post { fail("Трек завершился мгновенно — поток пустой или недоступный. Перебор остановлен.") }
                }
                m.setOnErrorListener { _, what, extra ->
                    mainHandler.post { fail("Ошибка воспроизведения: what=$what, extra=$extra") }
                    true
                }
                m.prepare()
                if (startPosMs > 0) runCatching { m.seekTo(startPosMs) }
                /* присваивание и старт — на главном, чтобы не гоняться с освобождением */
                mainHandler.post {
                    if (player != null) releasePlayer()
                    player = m
                    runCatching { m.start() }
                    startedAtMs = SystemClock.uptimeMillis()
                    PlayerState.durationMs = runCatching { m.duration }.getOrDefault(0)
                    updateMetadata(item); updateState(); startFg(); PlayerState.emit()
                }
            } catch (e: Exception) {
                mp?.let { runCatching { it.release() } }
                mainHandler.post { fail("Не удалось открыть трек: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    private fun refreshNotifIcon() {
        val p = PlayerState.coverPath
        if (p == iconPath) return
        iconPath = p
        if (p == null) {
            iconBmp = null
            return
        }
        Thread {
            val b = decodeSampled(p, 300)
            mainHandler.post {
                iconBmp = b
                runCatching { NotificationManagerCompat.from(this).notify(ID, buildNotif()) }
            }
        }.start()
    }

    private fun decodeSampled(path: String, target: Int): Bitmap? = try {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, opts)
        var s = 1
        while (opts.outWidth / (s * 2) >= target) s *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = s
        BitmapFactory.decodeFile(path, opts)
    } catch (e: Exception) { null }

    private fun autoNext() {
        if (queue.isEmpty()) return
        if (PlayerState.repeatMode == 2) { playCurrent(); return }
        if (PlayerState.shuffleOn && queue.size > 1) {
            var r = index
            while (r == index) r = (Math.random() * queue.size).toInt()
            index = r
            playCurrent()
            return
        }
        if (index + 1 < queue.size) { index++; playCurrent() }
        else if (PlayerState.repeatMode == 1) { index = 0; playCurrent() }
        else pause()
    }

    private fun next() {
        if (queue.isEmpty() && !restore()) return
        if (PlayerState.shuffleOn && queue.size > 1) {
            var r = index
            while (r == index) r = (Math.random() * queue.size).toInt()
            index = r
        } else index = (index + 1) % queue.size
        playCurrent()
    }

    private fun prev() {
        if (queue.isEmpty() && !restore()) return
        if (history.isNotEmpty()) {
            index = history.removeLast()
        } else {
            index = (index - 1 + queue.size) % queue.size
        }
        playCurrent(false)
    }

    private fun fail(msg: String) {
        PlayerState.playing = false
        PlayerState.error = msg
        releasePlayer()
        persist(0)
        updateState()
        stopForeground(false)
        runCatching { NotificationManagerCompat.from(this).notify(ID, buildNotif()) }
        PlayerState.emit()
    }

    private fun pause() {
        val pos = currentPosition()
        runCatching { player?.pause() }
        PlayerState.playing = false
        persist(pos)
        updateState()
        stopForeground(false)
        runCatching { NotificationManagerCompat.from(this).notify(ID, buildNotif()) }
        PlayerState.emit()
    }

    private fun resume() {
        if (player == null) {
            /* сервис пережил смерть процесса или плеер был отпущен: поднимаем очередь и позицию */
            if (!restore()) {
                stopForeground(true)
                stopSelf()
                return
            }
            val pos = savedPos
            savedPos = 0
            playCurrent(false, pos)
            return
        }
        gainFocus()
        runCatching { player?.start() }
        PlayerState.playing = true
        PlayerState.error = null
        updateState(); startFg(); PlayerState.emit()
    }

    private fun seek(ms: Int) {
        runCatching { player?.seekTo(ms) }
        persist(ms)
        updateState()
    }

    private fun stopAll() {
        persist(currentPosition())
        releasePlayer()
        abandonFocus()
        PlayerState.playing = false; PlayerState.uri = null; PlayerState.durationMs = 0; PlayerState.error = null
        PlayerState.coverPath = null
        iconPath = null; iconBmp = null
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
        runCatching {
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
    }

    private fun updateMetadata(item: QItem) {
        runCatching {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.artist)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, PlayerState.durationMs.toLong())
                    .build()
            )
        }
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
            .setLargeIcon(iconBmp)
            .setContentTitle(PlayerState.title)
            .setContentText(PlayerState.error ?: PlayerState.artist)
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
        persist(currentPosition())
        instance = null
        /* снимаем слушатель фокуса ДО освобождения плеера, иначе позднее событие тронет мёртвый объект */
        abandonFocus()
        runCatching { unregisterReceiver(noisyReceiver) }
        releasePlayer()
        session.release()
        super.onDestroy()
    }
}
