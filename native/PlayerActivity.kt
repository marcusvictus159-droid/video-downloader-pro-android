package com.videodownloader.pro

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Reproductor de video y audio con lista de reproducción (ExoPlayer / Media3). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var barTitle: TextView
    private var audioTitle: TextView? = null
    private var audioArtist: TextView? = null
    private var kind = "video"

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kind = intent.getStringExtra("kind") ?: "video"
        val video = kind == "video"
        val uris = ArrayList(SharedStore.queueUris)
        val titles = ArrayList(SharedStore.queueTitles)
        val artists = ArrayList(SharedStore.queueArtists)
        if (uris.isEmpty()) {
            finish()
            return
        }
        val index = intent.getIntExtra("index", 0).coerceIn(0, uris.size - 1)

        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = when {
            video -> Color.BLACK
            dark -> Color.parseColor("#0E1218")
            else -> Color.parseColor("#F2F4F8")
        }
        val ink = if (video || dark) Color.parseColor("#E8ECF2") else Color.parseColor("#12161C")
        val muted = if (video || dark) Color.parseColor("#98A2B3") else Color.parseColor("#5B6575")
        val accent = if (dark) Color.parseColor("#7C9BFF") else Color.parseColor("#2B54F0")
        val tintBg = if (dark) Color.parseColor("#2E7C9BFF") else Color.parseColor("#212B54F0")

        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !(video || dark)
        if (video) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        // ---- pantalla de audio (portada genérica + título)
        if (!video) {
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val iconBox = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(44).toFloat()
                    setColor(tintBg)
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_vdp_music)
                drawable.mutate().setTint(accent)
            }
            iconBox.addView(icon, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
            info.addView(iconBox, LinearLayout.LayoutParams(dp(176), dp(176)))

            audioTitle = TextView(this).apply {
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(32), dp(28), dp(32), 0)
            }
            info.addView(audioTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            audioArtist = TextView(this).apply {
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(32), dp(6), dp(32), 0)
            }
            info.addView(audioArtist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.topMargin = dp(56)
            lp.bottomMargin = dp(190)
            root.addView(info, lp)
        }

        // ---- reproductor
        playerView = PlayerView(this).apply {
            setShutterBackgroundColor(Color.TRANSPARENT)
            if (!video) {
                setControllerShowTimeoutMs(0)
                setControllerHideOnTouch(false)
                setShowShuffleButton(true)
                setRepeatToggleModes(androidx.media3.common.util.RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or androidx.media3.common.util.RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL)
            } else {
                setControllerShowTimeoutMs(3000)
                setFullscreenButtonClickListener { fullscreen ->
                    requestedOrientation = if (fullscreen) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- barra superior
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (video) {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#B3000000"), Color.TRANSPARENT)
                )
            }
        }
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_vdp_close)
            drawable.mutate().setTint(ink)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Cerrar"
            setOnClickListener { finish() }
        }
        topBar.addView(close, LinearLayout.LayoutParams(dp(52), dp(56)))
        barTitle = TextView(this).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, dp(16), 0)
            visibility = if (video) View.VISIBLE else View.GONE
        }
        topBar.addView(barTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP))

        if (video) {
            playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { v ->
                    topBar.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
                }
            )
        }

        setContentView(root)

        // ---- motor de reproducción
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (video) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        playerView.player = p

        val items = uris.mapIndexed { i, u ->
            MediaItem.Builder()
                .setUri(u)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(titles.getOrNull(i) ?: "")
                        .setArtist(artists.getOrNull(i) ?: "")
                        .build()
                )
                .build()
        }
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                showInfo(mediaItem)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "No se puede reproducir este archivo", Toast.LENGTH_LONG).show()
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }
        })
        p.setMediaItems(items, index, 0L)
        p.prepare()
        p.playWhenReady = true
        showInfo(items[index])
        applyBars(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun showInfo(item: MediaItem?) {
        val title = item?.mediaMetadata?.title?.toString() ?: ""
        var artist = item?.mediaMetadata?.artist?.toString() ?: ""
        if (artist == "<unknown>") artist = ""
        barTitle.text = title
        audioTitle?.text = title
        audioArtist?.text = artist
        audioArtist?.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE
    }

    private fun applyBars(landscape: Boolean) {
        if (kind != "video") return
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (landscape) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBars(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onStop() {
        // El video se pausa al salir de la pantalla; el audio sigue sonando.
        if (kind == "video") player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
