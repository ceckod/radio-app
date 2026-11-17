package bg.moeto.radio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Плейърът и "менюто" за Android Auto. Радиата идват от сайта (Stations).
 */
@OptIn(UnstableApi::class)
class RadioService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var readyOnce = false

    override fun onCreate() {
        super.onCreate()
        Stations.load(this)

        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                readyOnce = false
                retries = 0
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    readyOnce = true
                    retries = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                val item = player.currentMediaItem ?: return
                if (!readyOnce) {
                    // Радио от менюто на колата: пробва следващия адрес на същата станция
                    val urls = item.mediaMetadata.extras?.getStringArray("urls")
                    val now = item.localConfiguration?.uri?.toString()
                    if (urls != null && now != null) {
                        val i = urls.indexOf(now)
                        if (i >= 0 && i + 1 < urls.size) {
                            player.setMediaItem(item.buildUpon().setUri(urls[i + 1]).build())
                            player.prepare()
                            player.play()
                        }
                    }
                    return
                }
                // Връзката пада по време на слушане (тунел, смяна на мрежа): връзва се пак
                if (isNetworkError(error) && retries < 20) {
                    retries++
                    val delayMs = (retries * 1000L).coerceAtMost(10_000L)
                    handler.postDelayed({
                        if (player.playerError != null && player.playWhenReady) {
                            player.seekToDefaultPosition()
                            player.prepare()
                        }
                    }, delayMs)
                }
            }
        })

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(open)
            .build()

        Stations.onChanged = { handler.post { notifyTree() } }
    }

    private fun isNetworkError(error: PlaybackException): Boolean {
        return error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
    }

    private fun notifyTree() {
        val s = session ?: return
        s.notifyChildrenChanged("root", 2 + Stations.groups.size, null)
        s.notifyChildrenChanged("fav", Stations.fav.size, null)
        s.notifyChildrenChanged("rec", Stations.rec.size, null)
        for ((k, v) in Stations.groups) s.notifyChildrenChanged("cat:$k", v.size, null)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // Ако не свири, спира; ако свири, продължава на фонов режим
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        Stations.onChanged = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ===== Дърво за Android Auto =====

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun stationItem(s: Station): MediaItem {
        val extras = Bundle()
        extras.putStringArray("urls", s.urls.toTypedArray())
        return MediaItem.Builder()
            .setMediaId(s.name)
            .setUri(s.urls[0])
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.name)
                    .setArtist(s.sub.ifEmpty { "Радио на живо" })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun named(names: List<String>): List<MediaItem> =
        names.mapNotNull { Stations.byName(it) }.map { stationItem(it) }

    private fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == "root" -> {
            val list = ArrayList<MediaItem>()
            list.add(folder("fav", "★ Любими"))
            list.add(folder("rec", "Последни"))
            for ((k, _) in Stations.groups) list.add(folder("cat:$k", k))
            list
        }
        parentId == "fav" -> named(Stations.fav)
        parentId == "rec" -> named(Stations.rec)
        parentId.startsWith("cat:") -> {
            val key = parentId.removePrefix("cat:")
            named(Stations.groups.firstOrNull { it.first == key }?.second ?: emptyList())
        }
        else -> emptyList()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Радио"), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = childrenOf(parentId)
            val from = minOf(page.toLong() * pageSize, all.size.toLong()).toInt()
            val to = minOf(from.toLong() + pageSize, all.size.toLong()).toInt()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = Stations.byName(mediaId)?.let { stationItem(it) } ?: folder(mediaId, mediaId)
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        // Изборът от колата идва само с име; тук му даваме адреса
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = mediaItems.map { mi ->
                if (mi.localConfiguration != null) {
                    mi
                } else {
                    Stations.byName(mi.mediaId)?.let { stationItem(it) } ?: mi
                }
            }.toMutableList()
            return Futures.immediateFuture(out)
        }
    }
}
