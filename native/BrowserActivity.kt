package com.videodownloader.pro

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Navegador de YouTube dentro de la app. Muestra un botón flotante «Descargar»
 * cuando la página abierta es un video; al tocarlo, el enlace se agrega a la cola.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var fab: TextView
    private lateinit var doneBtn: TextView
    private lateinit var fullContainer: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private data class Site(val id: String, val title: String, val start: String, val hosts: List<String>, val feed: Boolean = false)

    private val sites = listOf(
        Site("youtube", "YouTube", "https://m.youtube.com/", listOf("youtube.com", "youtu.be")),
        Site("facebook", "Facebook", "https://m.facebook.com/", listOf("facebook.com", "fb.watch"), feed = true),
        Site("tiktok", "TikTok", "https://www.tiktok.com/", listOf("tiktok.com"), feed = true),
        Site("instagram", "Instagram", "https://www.instagram.com/", listOf("instagram.com"), feed = true),
        Site("x", "X", "https://x.com/", listOf("x.com", "twitter.com"), feed = true),
        Site("vimeo", "Vimeo", "https://vimeo.com/", listOf("vimeo.com"))
    ).associateBy { it.id }

    private lateinit var site: Site

    private var currentLink: String? = null
    private var lastUrl: String? = null
    private var picker: FormatPicker? = null
    private var fromShare = false
    private var shareTs = 0L
    private val added = LinkedHashSet<String>()

    private var dark = false
    private var bg = 0
    private var ink = 0
    private var accent = 0
    private var onAccent = 0
    private var mutedBg = 0

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        site = sites[intent.getStringExtra("site")] ?: sites.getValue("youtube")

        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        bg = if (dark) Color.parseColor("#0E1218") else Color.parseColor("#F2F4F8")
        ink = if (dark) Color.parseColor("#E8ECF2") else Color.parseColor("#12161C")
        accent = if (dark) Color.parseColor("#7C9BFF") else Color.parseColor("#2B54F0")
        onAccent = if (dark) Color.parseColor("#0A1030") else Color.WHITE
        mutedBg = if (dark) Color.parseColor("#2A3140") else Color.parseColor("#DDE2EA")

        window.statusBarColor = bg
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark

        val rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(bg)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- barra superior
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
        }
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_vdp_close)
            drawable.mutate().setTint(ink)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Cerrar"
            setOnClickListener { finish() }
        }
        bar.addView(close, LinearLayout.LayoutParams(dp(52), dp(56)))

        val title = TextView(this).apply {
            text = site.title
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        doneBtn = TextView(this).apply {
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            setOnClickListener { finish() }
        }
        val doneLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        doneLp.marginEnd = dp(6)
        bar.addView(doneBtn, doneLp)

        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // ---- página + botón flotante
        val body = FrameLayout(this)
        web = WebView(this)
        body.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fab = TextView(this).apply {
            setTextColor(onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(10)
            setPadding(dp(20), dp(15), dp(24), dp(15))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setOnClickListener { addCurrent() }
        }
        val fabLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fabLp.gravity = Gravity.BOTTOM or Gravity.END
        fabLp.setMargins(dp(16), dp(16), dp(16), dp(28))
        body.addView(fab, fabLp)

        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- contenedor de video en pantalla completa
        fullContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        rootFrame.addView(fullContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootFrame)
        setupWebView()
        renderFab()
        updateDoneButton()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        web.loadUrl(savedInstanceState?.getString("url") ?: site.start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("url", web.url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "VDP")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript(HOOK_JS, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // Esquemas como vnd.youtube: o intent: abrirían otras apps; se bloquean.
                return scheme != "http" && scheme != "https"
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                onUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(HOOK_JS, null)
                onUrl(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fullContainer.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                fullContainer.visibility = View.VISIBLE
                val c = WindowInsetsControllerCompat(window, window.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        fullContainer.removeView(v)
        fullContainer.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------ enlaces de video

    private fun hostMatches(host: String): Boolean =
        site.hosts.any { host == it || host.endsWith(".$it") }

    private fun clean(u: Uri): String = u.buildUpon().fragment(null).build().toString()

    /** Devuelve el enlace del video si la página abierta es un video del sitio; si no, null. */
    private fun videoLink(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val u = try { Uri.parse(raw) } catch (e: Exception) { return null }
        val host = (u.host ?: return null).lowercase()
        if (!hostMatches(host)) return null
        val segs = u.pathSegments
        val path = u.path ?: ""

        return when (site.id) {
            "youtube" -> when {
                host == "youtu.be" -> {
                    val id = segs.firstOrNull()
                    if (!id.isNullOrBlank()) "https://www.youtube.com/watch?v=$id" else null
                }
                path == "/watch" -> {
                    val v = u.getQueryParameter("v")
                    if (!v.isNullOrBlank()) "https://www.youtube.com/watch?v=$v" else null
                }
                path.startsWith("/shorts/") -> {
                    val id = segs.getOrNull(1)
                    if (!id.isNullOrBlank()) "https://www.youtube.com/shorts/$id" else null
                }
                else -> null
            }
            "facebook" -> when {
                host == "fb.watch" || host.endsWith(".fb.watch") -> if (segs.isNotEmpty()) clean(u) else null
                path.startsWith("/watch") -> {
                    val v = u.getQueryParameter("v")
                    if (!v.isNullOrBlank()) "https://www.facebook.com/watch/?v=$v" else null
                }
                path.contains("/videos/") || path.contains("/reel/") -> clean(u)
                else -> null
            }
            "tiktok" ->
                if (path.contains("/video/") || ((host.startsWith("vm.") || host.startsWith("vt.")) && segs.isNotEmpty()))
                    clean(u) else null
            "instagram" ->
                if (segs.size >= 2 && segs[0] in listOf("reel", "reels", "p", "tv")) clean(u) else null
            "x" -> {
                val i = segs.indexOf("status")
                val id = segs.getOrNull(i + 1)
                if (i >= 0 && !id.isNullOrEmpty() && id.all { it.isDigit() })
                    "https://x.com/" + segs.take(i + 2).joinToString("/") else null
            }
            "vimeo" ->
                if (segs.any { it.length >= 6 && it.all { c -> c.isDigit() } }) clean(u) else null
            else -> null
        }
    }

    private fun onUrl(url: String?) {
        if (url == lastUrl) return
        lastUrl = url
        fromShare = false
        currentLink = videoLink(url)
        runOnUiThread { renderFab() }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun addCurrent() {
        val link = currentLink
        val fresh = !fromShare || (System.currentTimeMillis() - shareTs) < 90_000
        if (link != null && fresh) {
            choose(link)
            return
        }
        if (site.feed) resolveFromPage()
    }

    /** Muestra el menú para elegir video/MP3 y calidad antes de agregar el enlace. */
    private fun choose(link: String) {
        if (added.contains(link)) {
            toast("Ese video ya está en la cola")
            return
        }
        picker?.dismiss()
        val p = FormatPicker(this, link, dark) { format, maxHeight, title ->
            addLink(link, format, maxHeight, title)
        }
        picker = p
        p.show()
    }

    private fun addLink(link: String, format: String, maxHeight: Int, title: String) {
        if (added.contains(link)) return
        added.add(link)
        val item = org.json.JSONObject()
            .put("url", link)
            .put("format", format)
            .put("maxHeight", maxHeight)
            .put("title", title)
        SharedStore.pending.add(item.toString())
        if (fromShare) {
            fromShare = false
            currentLink = null
        }
        toast(if (format == "mp3") "Audio agregado a las descargas" else "Video agregado a las descargas")
        renderFab()
        updateDoneButton()
    }

    /** Busca en la página el video que se está viendo (feeds que no cambian de dirección). */
    private fun resolveFromPage() {
        web.evaluateJavascript(RESOLVER_JS) { res ->
            var found: String? = null
            try {
                val str = org.json.JSONTokener(res).nextValue() as? String
                if (str != null) {
                    val arr = org.json.JSONArray(str)
                    for (i in 0 until arr.length()) {
                        val v = videoLink(arr.getString(i))
                        if (v != null) { found = v; break }
                    }
                }
            } catch (e: Exception) {
                found = null
            }
            val f = found
            if (f != null) {
                choose(f)
            } else {
                Toast.makeText(
                    this,
                    "No encuentro el enlace de este video. Toca Compartir y Copiar enlace, o ábrelo desde el perfil o la búsqueda.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Texto que la página copió o compartió (Compartir → Copiar enlace). */
    private fun onSharedText(text: String) {
        val m = Regex("https?://[^\\s\"'<>]+").find(text) ?: return
        val v = videoLink(m.value) ?: return
        currentLink = v
        fromShare = true
        shareTs = System.currentTimeMillis()
        renderFab()
        toast("Enlace detectado. Toca Descargar")
    }

    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun link(text: String?) {
            if (text.isNullOrBlank()) return
            runOnUiThread { onSharedText(text) }
        }
    }

    private fun renderFab() {
        val link = currentLink
        if (link == null && !site.feed) {
            fab.visibility = View.GONE
            return
        }
        val already = link != null && added.contains(link)
        fab.text = if (already) "Agregado" else "Descargar"
        fab.setTextColor(if (already) ink else onAccent)

        val icon = resources.getDrawable(if (already) R.drawable.ic_vdp_check else R.drawable.ic_vdp_download, theme).mutate()
        icon.setTint(if (already) ink else onAccent)
        icon.setBounds(0, 0, dp(22), dp(22))
        fab.setCompoundDrawables(icon, null, null, null)

        fab.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(32).toFloat()
            setColor(if (already) mutedBg else accent)
        }
        fab.isEnabled = !already
        if (fab.visibility != View.VISIBLE) {
            fab.alpha = 0f
            fab.visibility = View.VISIBLE
            fab.animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun updateDoneButton() {
        val n = added.size
        if (n == 0) {
            doneBtn.visibility = View.GONE
        } else {
            doneBtn.text = if (n == 1) "Ver descargas (1)" else "Ver descargas ($n)"
            doneBtn.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------ ciclo de vida

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        picker?.dismiss()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** Captura los enlaces que la página copia o comparte. */
        private const val HOOK_JS = """
(function () {
  if (window.__vdpHook) return;
  window.__vdpHook = 1;
  function send(t) { try { if (t && /https?:\/\//.test(t)) VDP.link(String(t)); } catch (e) {} }
  try {
    var cw = navigator.clipboard && navigator.clipboard.writeText;
    if (cw) {
      navigator.clipboard.writeText = function (t) { send(t); return cw.apply(navigator.clipboard, arguments); };
    }
  } catch (e) {}
  try {
    navigator.share = function (d) { send(d && (d.url || d.text)); return Promise.resolve(); };
  } catch (e) {}
  document.addEventListener('copy', function () { try { send(String(window.getSelection())); } catch (e) {} }, true);
})();
"""

        /** Lista, ordenada por cercanía al centro de la pantalla, los enlaces candidatos de la página. */
        private const val RESOLVER_JS = """
(function () {
  var out = [];
  var vh = window.innerHeight || 800, cy = vh / 2;
  function abs(h) { try { return new URL(h, location.href).href; } catch (e) { return null; } }
  function dist(r) {
    if (!r || (r.width === 0 && r.height === 0)) return 1e7;
    var mid = (r.top + r.bottom) / 2;
    var vis = r.bottom > 0 && r.top < vh;
    return (vis ? 0 : 1e6) + Math.abs(mid - cy);
  }
  var c = document.querySelector('link[rel="canonical"]');
  if (c && c.href) out.push([c.href, 5e6]);
  var m = document.querySelector('meta[property="og:url"]');
  if (m && m.content) out.push([m.content, 5e6]);
  var as = document.querySelectorAll('a[href]');
  for (var i = 0; i < as.length && i < 1500; i++) {
    var u = abs(as[i].getAttribute('href'));
    if (u) out.push([u, dist(as[i].getBoundingClientRect())]);
  }
  if (location.hostname.indexOf('tiktok.com') > -1) {
    var vs = document.querySelectorAll('video');
    for (var j = 0; j < vs.length; j++) {
      var d = dist(vs[j].getBoundingClientRect());
      var el = vs[j], k = 0;
      while (el && k < 8) {
        var s = (el.id || '') + ' ' + (el.getAttribute ? (el.getAttribute('data-id') || el.getAttribute('data-video-id') || '') : '');
        var mm = s.match(/\d{17,20}/);
        if (mm) { out.push(['https://www.tiktok.com/@a/video/' + mm[0], d]); break; }
        el = el.parentElement; k++;
      }
    }
  }
  out.sort(function (a, b) { return a[1] - b[1]; });
  return JSON.stringify(out.slice(0, 40).map(function (x) { return x[0]; }));
})()
"""
    }
}
