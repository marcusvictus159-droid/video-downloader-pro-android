package com.videodownloader.pro

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.util.Locale
import java.util.TreeMap

/**
 * Menú inferior para elegir qué descargar: video MP4 con una de las calidades que
 * realmente existen en ese video, o solo audio MP3. Lee las calidades con yt-dlp.
 */
class FormatPicker(
    private val activity: AppCompatActivity,
    private val link: String,
    private val dark: Boolean,
    private val onConfirm: (format: String, maxHeight: Int, title: String) -> Unit
) {
    private class Quality(val height: Int, val label: String, val size: String)

    private val sheetBg = if (dark) 0xFF171C25.toInt() else 0xFFFFFFFF.toInt()
    private val field = if (dark) 0xFF212835.toInt() else 0xFFE8ECF3.toInt()
    private val ink = if (dark) 0xFFE8ECF2.toInt() else 0xFF12161C.toInt()
    private val muted = if (dark) 0xFF98A2B3.toInt() else 0xFF5B6575.toInt()
    private val accent = if (dark) 0xFF7C9BFF.toInt() else 0xFF2B54F0.toInt()
    private val onAccent = if (dark) 0xFF0A1030.toInt() else 0xFFFFFFFF.toInt()
    private val tint = if (dark) 0x2E7C9BFF else 0x212B54F0

    private val prefs = activity.getSharedPreferences("vdp_picker", Context.MODE_PRIVATE)
    private var format: String = prefs.getString("format", "mp4") ?: "mp4"
    private val wanted: Int = prefs.getInt("height", 1080)

    private var title = ""
    private var meta = ""
    private var qualities: List<Quality> = emptyList()
    private var loading = true
    private var failed = false
    private var selected = 0
    private var closed = false
    private val processId = "info_" + System.nanoTime()

    private val dialog = Dialog(activity)
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var segVideo: TextView
    private lateinit var segAudio: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var confirm: TextView

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildView())
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.setOnDismissListener {
            closed = true
            cancelInfo()
        }
        dialog.show()
        render()
        fetch()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    // ------------------------------------------------------------------ vista

    private fun buildView(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(sheetBg)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }

        val grab = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(field)
            }
        }
        val grabLp = LinearLayout.LayoutParams(dp(36), dp(4))
        grabLp.gravity = Gravity.CENTER_HORIZONTAL
        grabLp.bottomMargin = dp(16)
        root.addView(grab, grabLp)

        titleView = TextView(activity).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        root.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        metaView = TextView(activity).apply {
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        root.addView(metaView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // control segmentado
        val seg = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(field)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segVideo = segButton("Video MP4") { setFormat("mp4") }
        segAudio = segButton("Solo audio MP3") { setFormat("mp3") }
        seg.addView(segVideo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seg.addView(segAudio, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val segLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        segLp.topMargin = dp(16)
        segLp.bottomMargin = dp(10)
        root.addView(seg, segLp)

        // lista de calidades (con altura máxima)
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(dp(250), View.MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        confirm = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { confirmNow() }
        }
        val confirmLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        confirmLp.topMargin = dp(14)
        root.addView(confirm, confirmLp)

        val cancel = TextView(activity).apply {
            text = "Cancelar"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { dismiss() }
        }
        root.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        return root
    }

    private fun segButton(label: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(6), dp(11), dp(6), dp(11))
            setOnClickListener { onClick() }
        }

    private fun styleSeg(v: TextView, on: Boolean) {
        v.setTextColor(if (on) ink else muted)
        v.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(if (on) sheetBg else Color.TRANSPARENT)
        }
    }

    private fun setFormat(f: String) {
        format = f
        render()
    }

    private fun row(label: String, sub: String, isSelected: Boolean, onClick: (() -> Unit)?): View {
        val r = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(13), dp(14), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isSelected) tint else Color.TRANSPARENT)
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
        val radio = ImageView(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isSelected) {
                    setColor(accent)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), muted)
                }
            }
            if (isSelected) {
                setImageResource(R.drawable.ic_vdp_check)
                drawable.mutate().setTint(onAccent)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
        }
        val radioLp = LinearLayout.LayoutParams(dp(24), dp(24))
        radioLp.marginEnd = dp(14)
        r.addView(radio, radioLp)

        val labelView = TextView(activity).apply {
            text = label
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (isSelected) setTypeface(typeface, Typeface.BOLD)
        }
        r.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (sub.isNotBlank()) {
            val subView = TextView(activity).apply {
                text = sub
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            r.addView(subView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return r
    }

    private fun note(text: String): View =
        TextView(activity).apply {
            this.text = text
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }

    private fun fallback(): List<Quality> = listOf(
        Quality(0, "Mejor disponible", ""),
        Quality(2160, "2160p (4K)", ""),
        Quality(1440, "1440p (2K)", ""),
        Quality(1080, "1080p", ""),
        Quality(720, "720p", ""),
        Quality(480, "480p", ""),
        Quality(360, "360p", "")
    )

    private fun render() {
        styleSeg(segVideo, format == "mp4")
        styleSeg(segAudio, format == "mp3")

        titleView.text = when {
            title.isNotBlank() -> title
            loading -> "Leyendo el video…"
            else -> "Elegir qué descargar"
        }
        metaView.text = meta
        metaView.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        listBox.removeAllViews()
        if (format == "mp3") {
            listBox.addView(row("MP3", "Mejor calidad", true, null))
        } else if (loading) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(20), dp(8), dp(20))
            }
            val spinner = ProgressBar(activity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(accent)
            }
            val spLp = LinearLayout.LayoutParams(dp(26), dp(26))
            spLp.marginEnd = dp(14)
            box.addView(spinner, spLp)
            box.addView(TextView(activity).apply {
                text = "Buscando las calidades disponibles…"
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            listBox.addView(box)
        } else {
            val list = if (failed) fallback() else qualities
            if (failed) {
                listBox.addView(note("No pude leer las calidades de este video. Elige un límite y se bajará la mejor calidad dentro de él."))
            }
            list.forEach { q ->
                listBox.addView(row(q.label, q.size, q.height == selected) {
                    selected = q.height
                    render()
                })
            }
        }

        val enabled = format == "mp3" || !loading
        confirm.text = when {
            format == "mp3" -> "Descargar MP3"
            selected > 0 -> "Descargar en " + selected + "p"
            else -> "Descargar en la mejor calidad"
        }
        confirm.setTextColor(if (enabled) onAccent else muted)
        confirm.background = GradientDrawable().apply {
            cornerRadius = dp(27).toFloat()
            setColor(if (enabled) accent else field)
        }
        confirm.isEnabled = enabled
    }

    private fun confirmNow() {
        if (format != "mp3" && loading) return
        val h = if (format == "mp3") 0 else selected
        val editor = prefs.edit().putString("format", format)
        if (h > 0) editor.putInt("height", h)
        editor.apply()
        dismiss()
        onConfirm(format, h, title)
    }

    // ------------------------------------------------------ lectura con yt-dlp

    private fun cancelInfo() {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Exception) {
            // ya había terminado
        }
    }

    private fun fetch() {
        Thread {
            var result: String? = null
            try {
                YoutubeDL.getInstance().init(activity.applicationContext)
                val req = YoutubeDLRequest(link)
                req.addOption("--dump-single-json")
                req.addOption("--no-playlist")
                req.addOption("--skip-download")
                req.addOption("--no-warnings")
                req.addOption("--socket-timeout", "20")
                val resp = YoutubeDL.getInstance().execute(req, processId) { _, _, _ -> }
                result = resp.out
            } catch (e: Exception) {
                result = null
            }
            val text: String? = result
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                try {
                    if (text.isNullOrBlank()) throw IllegalStateException("sin datos")
                    parse(text.trim())
                } catch (e: Exception) {
                    failed = true
                }
                loading = false
                if (qualities.isEmpty()) failed = true
                if (failed) {
                    val opts = fallback().map { it.height }
                    selected = if (opts.contains(wanted)) wanted else 1080
                }
                render()
            }
        }.start()
    }

    private class Acc {
        var withAudio = 0L
        var videoOnly = 0L
        var fps = 0.0
    }

    private fun parse(json: String) {
        val o = JSONObject(json)
        title = if (o.isNull("title")) "" else o.optString("title")
        val dur = o.optLong("duration", 0L)
        val who = listOf("uploader", "channel", "creator")
            .map { if (o.isNull(it)) "" else o.optString(it) }
            .firstOrNull { it.isNotBlank() } ?: ""
        meta = listOf(if (dur > 0) fmtDur(dur) else "", who).filter { it.isNotBlank() }.joinToString(" · ")

        val formats = o.optJSONArray("formats")
        var bestAudio = 0L
        val byHeight = TreeMap<Int, Acc>()
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val v = if (f.isNull("vcodec")) "" else f.optString("vcodec")
                val a = if (f.isNull("acodec")) "" else f.optString("acodec")
                val h = f.optInt("height", 0)
                val size = maxOf(f.optLong("filesize", 0L), f.optLong("filesize_approx", 0L))
                val isVideo = h > 0 && v != "none"
                val hasAudio = a != "none"
                if (isVideo) {
                    val acc = byHeight.getOrPut(h) { Acc() }
                    if (hasAudio) acc.withAudio = maxOf(acc.withAudio, size)
                    else acc.videoOnly = maxOf(acc.videoOnly, size)
                    val fps = f.optDouble("fps", 0.0)
                    if (!fps.isNaN()) acc.fps = maxOf(acc.fps, fps)
                } else if (v == "none" && a.isNotBlank() && a != "none") {
                    bestAudio = maxOf(bestAudio, size)
                }
            }
        }

        val list = ArrayList<Quality>()
        for ((h, acc) in byHeight.descendingMap()) {
            val est = maxOf(acc.withAudio, if (acc.videoOnly > 0) acc.videoOnly + bestAudio else 0L)
            val base = when {
                h >= 2160 -> "2160p (4K)"
                h >= 1440 -> "1440p (2K)"
                else -> h.toString() + "p"
            }
            val label = if (acc.fps >= 50) base + " · " + acc.fps.toInt() + " fps" else base
            list.add(Quality(h, label, if (est > 0) "≈ " + fmtSize(est) else ""))
        }
        qualities = list
        if (list.isNotEmpty()) {
            selected = list.firstOrNull { it.height <= wanted }?.height ?: list.last().height
        }
    }

    private fun fmtSize(b: Long): String =
        if (b >= 1_000_000_000L) String.format(Locale.US, "%.1f GB", b / 1e9)
        else String.format(Locale.US, "%d MB", maxOf(1L, b / 1_000_000L))

    private fun fmtDur(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val x = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, x)
        else String.format(Locale.US, "%d:%02d", m, x)
    }
}
