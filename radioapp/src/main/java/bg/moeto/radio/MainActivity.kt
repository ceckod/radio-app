package bg.moeto.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject

/**
 * Показва сайта на цял екран. Звукът не го пуска браузърът, а плейърът в RadioService,
 * така че колата (Android Auto), бутоните на волана и фоновото свирене работят.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null
    private var readyOnce = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stations.load(this)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#090D16"))
        web.keepScreenOn = true // екранът да не заспива, като в сайта

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#090D16"))
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                syncAll()
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidRadio")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Първо затваря скрийнсейвъра / връща към началото; иначе скрива приложението
                web.evaluateJavascript(
                    "(function(){try{if(ssOn){closeSS();return 1}if(view!=='home'){setView('home');return 1}}catch(e){}return 0})()"
                ) { r ->
                    if (r != "1") moveTaskToBack(true)
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        if (controller == null && controllerFuture == null) connect()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        web.destroy()
        super.onDestroy()
    }

    // ===== Връзка с плейъра =====

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(playerListener)
                pending?.invoke()
                pending = null
                syncAll()
            } catch (e: Exception) {
                controllerFuture = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) readyOnce = true
            pushState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pushState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            readyOnce = false
            if (mediaItem != null) emit("station", mediaItem.mediaId)
            pushState()
            pushSong()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            pushSong()
        }

        override fun onPlayerError(error: PlaybackException) {
            // след първо успешно свирене плейърът сам се свързва пак
            if (!readyOnce) emit("error")
        }
    }

    private fun playStation(name: String, url: String, sub: String) {
        val c = controller
        if (c == null) {
            pending = { playStation(name, url, sub) }
            if (controllerFuture == null) connect()
            return
        }
        val item = MediaItem.Builder()
            .setMediaId(name)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(sub.ifEmpty { "Радио на живо" })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    // ===== Съобщения към сайта =====

    private fun emit(type: String, value: String? = null) {
        val o = JSONObject()
        o.put("t", type)
        if (value != null) o.put(if (type == "song") "title" else "name", value)
        web.evaluateJavascript("window.__native&&window.__native(" + o.toString() + ")", null)
    }

    private fun pushState() {
        val c = controller ?: return
        val t = when {
            !c.playWhenReady -> "pause"
            c.playbackState == Player.STATE_READY -> "playing"
            c.playbackState == Player.STATE_BUFFERING -> "waiting"
            else -> "pause"
        }
        emit(t)
    }

    private fun pushSong() {
        val c = controller ?: return
        val live = c.mediaMetadata.title?.toString()?.trim().orEmpty()
        val station = c.currentMediaItem?.mediaMetadata?.title?.toString()?.trim().orEmpty()
        emit("song", if (live.isEmpty() || live.equals(station, ignoreCase = true)) "" else live)
    }

    private fun syncAll() {
        val c = controller ?: return
        c.currentMediaItem?.let { emit("station", it.mediaId) }
        pushState()
        pushSong()
    }

    private fun setFullscreen(on: Boolean) {
        val ctl = WindowCompat.getInsetsController(window, web)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
    }

    // ===== Мост JavaScript -> приложение (window.AndroidRadio) =====

    inner class Bridge {
        @JavascriptInterface
        fun play(name: String, url: String, sub: String) {
            main.post { playStation(name, url, sub) }
        }

        @JavascriptInterface
        fun pause() {
            main.post { controller?.pause() }
        }

        @JavascriptInterface
        fun setVolume(v: Double) {
            main.post { controller?.volume = v.toFloat().coerceIn(0f, 1f) }
        }

        @JavascriptInterface
        fun setStations(json: String) {
            Stations.update(applicationContext, json)
        }

        @JavascriptInterface
        fun fullscreen(on: Boolean) {
            main.post { setFullscreen(on) }
        }
    }
}
