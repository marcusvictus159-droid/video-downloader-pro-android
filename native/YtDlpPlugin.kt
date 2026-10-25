package com.videodownloader.pro

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import android.webkit.MimeTypeMap
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Puente entre la interfaz web y yt-dlp (librería youtubedl-android).
 * La interfaz consulta el avance con getProgress() (polling), así no depende de eventos.
 */
@CapacitorPlugin(
    name = "YtDlp",
    permissions = [
        Permission(
            strings = [Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO],
            alias = "media33"
        ),
        Permission(strings = [Manifest.permission.READ_EXTERNAL_STORAGE], alias = "mediaLegacy")
    ]
)
class YtDlpPlugin : Plugin() {

    private class DlState {
        @Volatile var progress: Float = 0f
        @Volatile var eta: Long = 0L
        @Volatile var lastLine: String = ""
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList<String>())

        fun log(s: String) {
            if (logs.size < 3000) logs.add(s)
        }

        fun onLine(p: Float, e: Long, line: String?) {
            if (p >= 0f) progress = p
            eta = e
            if (!line.isNullOrBlank()) {
                lastLine = line
                val isProgress = line.contains("[download]") && line.contains("%")
                if (!isProgress) log(line.trim())
            }
        }
    }

    private val executor = Executors.newCachedThreadPool()
    private val active = AtomicInteger(0)
    private val states = ConcurrentHashMap<String, DlState>()
    private val cancelled: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val initLock = Any()

    @Volatile
    private var initialized = false

    // ------------------------------------------------------------------ motor

    private fun ensureReady() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
        }
    }

    @PluginMethod
    fun prepare(call: PluginCall) {
        executor.execute {
            try {
                ensureReady()
                call.resolve(JSObject().put("ready", true))
            } catch (e: Exception) {
                call.reject("No se pudo iniciar el motor: " + shortMsg(e))
            }
        }
    }

    @PluginMethod
    fun updateEngine(call: PluginCall) {
        executor.execute {
            try {
                ensureReady()
                call.resolve(JSObject().put("status", doUpdate()))
            } catch (e: Exception) {
                call.reject("No se pudo actualizar: " + shortMsg(e))
            }
        }
    }

    /**
     * Actualiza yt-dlp. Se usa reflexión para tolerar cambios de firma entre versiones
     * de la librería (con o sin parámetro de canal).
     */
    private fun doUpdate(): String {
        val yd = YoutubeDL.getInstance()
        val app = context.applicationContext
        var last: Throwable? = null
        val candidates = yd.javaClass.methods
            .filter { it.name == "updateYoutubeDL" && !it.isSynthetic }
            .sortedByDescending { it.parameterTypes.size }
        for (m in candidates) {
            try {
                val types = m.parameterTypes
                val result: Any? = when (types.size) {
                    1 -> m.invoke(yd, app)
                    2 -> {
                        val channel = findStable(types[1])
                            ?: throw IllegalStateException("canal de actualización desconocido")
                        m.invoke(yd, app, channel)
                    }
                    else -> throw IllegalStateException("firma inesperada")
                }
                return result?.toString() ?: "OK"
            } catch (t: Throwable) {
                last = if (t is InvocationTargetException) (t.targetException ?: t) else t
            }
        }
        throw Exception(last?.message ?: "no se encontró el método de actualización")
    }

    private fun findStable(cls: Class<*>): Any? {
        val names = listOf("STABLE", "_STABLE")
        for (n in names) {
            try {
                return cls.getField(n).get(null)
            } catch (e: Exception) { /* siguiente intento */ }
        }
        try {
            val comp = cls.getField("Companion").get(null)
            if (comp != null) {
                for (n in names) {
                    try {
                        return comp.javaClass.getMethod("get$n").invoke(comp)
                    } catch (e: Exception) { /* siguiente intento */ }
                }
            }
        } catch (e: Exception) { /* siguiente intento */ }
        try {
            val nested = cls.declaredClasses.firstOrNull { names.contains(it.simpleName.uppercase()) }
            if (nested != null) return nested.getField("INSTANCE").get(null)
        } catch (e: Exception) { /* siguiente intento */ }
        try {
            val constants = cls.enumConstants
            if (constants != null) {
                return constants.firstOrNull { it.toString().uppercase().contains("STABLE") }
            }
        } catch (e: Exception) { /* sin resultado */ }
        return null
    }

    // -------------------------------------------------------------- descargas

    @PluginMethod
    fun download(call: PluginCall) {
        val url = call.getString("url")
        if (url.isNullOrBlank()) {
            call.reject("Falta el enlace")
            return
        }
        val id = call.getString("id") ?: System.currentTimeMillis().toString()
        val format = call.getString("format") ?: "mp4"
        val maxHeight = call.getInt("maxHeight") ?: 0
        val playlist = call.getBoolean("playlist") == true

        val state = DlState()
        states[id] = state
        cancelled.remove(id)

        if (active.getAndIncrement() == 0) {
            DownloadService.start(context, "Descargando en segundo plano")
        }
        executor.execute {
            try {
                runDownload(call, id, url, format, maxHeight, playlist, state)
            } finally {
                if (active.decrementAndGet() <= 0) {
                    active.set(0)
                    DownloadService.stop(context)
                }
            }
        }
    }

    private fun runDownload(
        call: PluginCall,
        id: String,
        url: String,
        format: String,
        maxHeight: Int,
        playlist: Boolean,
        state: DlState
    ) {
        val base = context.getExternalFilesDir(null) ?: context.cacheDir
        val tmp = File(base, "tmp/$id")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            ensureReady()

            try {
                runYtDlp(buildRequest(url, tmp, format, maxHeight, playlist), id, state)
            } catch (e: Exception) {
                if (cancelled.contains(id)) throw e
                state.log("⚠️ Falló la descarga: " + shortMsg(e))
                state.log("🔄 Actualizando el motor y reintentando…")
                try {
                    doUpdate()
                } catch (u: Exception) {
                    state.log("No se pudo actualizar el motor: " + shortMsg(u))
                }
                state.progress = 0f
                runYtDlp(buildRequest(url, tmp, format, maxHeight, playlist), id, state)
            }

            val saved = saveOutputs(tmp, state)
            if (saved.isEmpty()) throw IOException("No se generó ningún archivo")
            state.progress = 100f

            val files = JSArray()
            val items = JSArray()
            saved.forEach {
                files.put(it.getString("path"))
                items.put(it)
            }
            call.resolve(JSObject().put("files", files).put("items", items))
        } catch (e: Exception) {
            if (cancelled.contains(id)) {
                call.resolve(JSObject().put("cancelled", true))
            } else {
                call.reject(shortMsg(e))
            }
        } finally {
            tmp.deleteRecursively()
            cancelled.remove(id)
        }
    }

    private fun runYtDlp(request: YoutubeDLRequest, id: String, state: DlState) {
        YoutubeDL.getInstance().execute(request, id) { progress, eta, line ->
            state.onLine(progress, eta, line)
        }
    }

    private fun buildRequest(
        url: String,
        dir: File,
        format: String,
        maxHeight: Int,
        playlist: Boolean
    ): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        // Filtro anti-mixes: con --no-playlist solo baja el video del enlace.
        r.addOption(if (playlist) "--yes-playlist" else "--no-playlist")
        r.addOption("-o", File(dir, "%(title).120B.%(ext)s").absolutePath)
        r.addOption("--newline")
        r.addOption("--no-mtime")
        // Modo tanque: muchos reintentos si el internet parpadea.
        r.addOption("--retries", "50")
        r.addOption("--fragment-retries", "50")
        r.addOption("--socket-timeout", "30")
        r.addOption("--concurrent-fragments", "4")

        if (format == "mp3") {
            r.addOption("-f", "bestaudio/best")
            r.addOption("-x")
            r.addOption("--audio-format", "mp3")
            r.addOption("--audio-quality", "0")
        } else {
            val h = if (maxHeight > 0) "[height<=$maxHeight]" else ""
            r.addOption("-f", "bv*$h[vcodec^=avc1]+ba[ext=m4a]/b$h[ext=mp4]/bv*$h+ba/b$h/b")
            r.addOption("--merge-output-format", "mp4")
        }
        return r
    }

    @PluginMethod
    fun getProgress(call: PluginCall) {
        val id = call.getString("id") ?: ""
        val since = call.getInt("since") ?: 0
        val out = JSObject()
        val s = states[id]
        if (s == null) {
            out.put("progress", 0)
            out.put("eta", 0)
            out.put("line", "")
            out.put("logs", JSArray())
            out.put("logCount", since)
            call.resolve(out)
            return
        }
        val arr = JSArray()
        var count = 0
        synchronized(s.logs) {
            count = s.logs.size
            for (i in since until count) arr.put(s.logs[i])
        }
        out.put("progress", s.progress.toDouble())
        out.put("eta", s.eta)
        out.put("line", s.lastLine)
        out.put("logs", arr)
        out.put("logCount", count)
        call.resolve(out)
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrBlank()) {
            call.reject("Falta el id")
            return
        }
        cancelled.add(id)
        try {
            YoutubeDL.getInstance().destroyProcessById(id)
        } catch (e: Exception) {
            // si ya había terminado no pasa nada
        }
        call.resolve()
    }

    /** Abre el navegador de un sitio (pantalla nativa con botón flotante de descarga). */
    @PluginMethod
    fun openBrowser(call: PluginCall) {
        try {
            val intent = Intent(activity, BrowserActivity::class.java)
            intent.putExtra("site", call.getString("site") ?: "youtube")
            activity.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("No se pudo abrir el navegador: " + shortMsg(e))
        }
    }

    /** Entrega (y vacía) los videos agregados desde el navegador, con el formato y calidad elegidos. */
    @PluginMethod
    fun takePending(call: PluginCall) {
        val arr = JSArray()
        synchronized(SharedStore.pending) {
            SharedStore.pending.forEach { raw ->
                try {
                    arr.put(JSObject(raw))
                } catch (e: Exception) {
                    arr.put(JSObject().put("url", raw))
                }
            }
            SharedStore.pending.clear()
        }
        call.resolve(JSObject().put("items", arr))
    }

    @PluginMethod
    fun getSharedText(call: PluginCall) {
        val t = SharedStore.text
        SharedStore.text = null
        call.resolve(JSObject().put("text", t ?: ""))
    }

    // ------------------------------------------------------------ biblioteca

    private fun mediaPermissionAlias(): String =
        if (Build.VERSION.SDK_INT >= 33) "media33" else "mediaLegacy"

    private fun hasMediaPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @PluginMethod
    fun mediaAccess(call: PluginCall) {
        call.resolve(JSObject().put("granted", hasMediaPermission()))
    }

    @PluginMethod
    fun requestMediaAccess(call: PluginCall) {
        if (hasMediaPermission()) {
            call.resolve(JSObject().put("granted", true))
        } else {
            requestPermissionForAliases(arrayOf(mediaPermissionAlias()), call, "mediaPermsResult")
        }
    }

    @PermissionCallback
    fun mediaPermsResult(call: PluginCall) {
        call.resolve(JSObject().put("granted", hasMediaPermission()))
    }

    /** Lista todos los videos y audios del teléfono, de cualquier carpeta. */
    @PluginMethod
    fun listMedia(call: PluginCall) {
        if (!hasMediaPermission()) {
            call.resolve(JSObject().put("granted", false))
            return
        }
        executor.execute {
            try {
                val videos = queryMedia(true)
                val audios = queryMedia(false)
                call.resolve(
                    JSObject().put("granted", true).put("videos", videos).put("audios", audios)
                )
            } catch (e: Exception) {
                call.reject("No se pudo leer la biblioteca: " + shortMsg(e))
            }
        }
    }

    private fun queryMedia(video: Boolean): JSArray {
        val out = JSArray()
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val cols = arrayListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )
        if (!video) cols.add(MediaStore.Audio.Media.ARTIST)
        val selection = if (video) null else
            MediaStore.Audio.Media.IS_RINGTONE + "=0 AND " +
                MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " +
                MediaStore.Audio.Media.IS_ALARM + "=0"

        context.contentResolver.query(
            collection, cols.toTypedArray(), selection, null,
            MediaStore.MediaColumns.DATE_ADDED + " DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iArtist = if (video) -1 else c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            var n = 0
            while (c.moveToNext() && n < 8000) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: ""
                val title = (c.getString(iTitle) ?: "").ifBlank { name.substringBeforeLast('.') }
                val rel = c.getString(iRel) ?: ""
                val bucket = c.getString(iBucket) ?: ""
                val folder = bucket.ifBlank { rel.trimEnd('/').substringAfterLast('/') }.ifBlank { "Otros" }
                val artist = if (iArtist >= 0) (c.getString(iArtist) ?: "") else ""
                out.put(
                    JSObject()
                        .put("id", id)
                        .put("uri", ContentUris.withAppendedId(collection, id).toString())
                        .put("title", title)
                        .put("name", name)
                        .put("duration", c.getLong(iDur))
                        .put("size", c.getLong(iSize))
                        .put("date", c.getLong(iDate))
                        .put("mime", c.getString(iMime) ?: "")
                        .put("folder", folder)
                        .put("path", rel)
                        .put("artist", artist)
                )
                n++
            }
        }
        return out
    }

    /** Miniatura de un video como JPEG en base64. */
    @PluginMethod
    fun getThumbnail(call: PluginCall) {
        val uriStr = call.getString("uri")
        if (uriStr.isNullOrBlank()) {
            call.resolve(JSObject())
            return
        }
        executor.execute {
            try {
                val bmp = context.contentResolver.loadThumbnail(Uri.parse(uriStr), Size(240, 135), null)
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                call.resolve(JSObject().put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)))
            } catch (e: Exception) {
                call.resolve(JSObject())
            }
        }
    }

    /** Abre el reproductor con una lista de archivos. */
    @PluginMethod
    fun playMedia(call: PluginCall) {
        val arr = call.getArray("items")
        if (arr == null || arr.length() == 0) {
            call.reject("No hay archivos para reproducir")
            return
        }
        val uris = ArrayList<String>()
        val titles = ArrayList<String>()
        val artists = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("uri")
            if (u.isNullOrBlank()) continue
            uris.add(u)
            titles.add(o.optString("title"))
            artists.add(o.optString("artist"))
        }
        if (uris.isEmpty()) {
            call.reject("No hay archivos para reproducir")
            return
        }
        SharedStore.queueUris = uris
        SharedStore.queueTitles = titles
        SharedStore.queueArtists = artists
        try {
            val intent = Intent(activity, PlayerActivity::class.java)
            intent.putExtra("kind", call.getString("kind") ?: "video")
            intent.putExtra("index", (call.getInt("index") ?: 0).coerceIn(0, uris.size - 1))
            activity.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("No se pudo abrir el reproductor: " + shortMsg(e))
        }
    }

    // ------------------------------------------- abrir / eliminar archivos

    private fun exists(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Abre el archivo con el selector de apps de Android (reproductores instalados). */
    @PluginMethod
    fun openFile(call: PluginCall) {
        val uriStr = call.getString("uri")
        if (uriStr.isNullOrBlank()) {
            call.reject("Falta el archivo")
            return
        }
        val uri = Uri.parse(uriStr)
        if (!exists(uri)) {
            call.reject("El archivo ya no existe en el teléfono")
            return
        }
        val mime = call.getString("mime") ?: "video/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val chooser = Intent.createChooser(view, "Abrir con")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(chooser)
            call.resolve()
        } catch (e: ActivityNotFoundException) {
            call.reject("No hay una app instalada para abrir este archivo")
        } catch (e: Exception) {
            call.reject("No se pudo abrir: " + shortMsg(e))
        }
    }

    @PluginMethod
    fun deleteFile(call: PluginCall) {
        val uriStr = call.getString("uri")
        if (uriStr.isNullOrBlank()) {
            call.reject("Falta el archivo")
            return
        }
        try {
            val rows = context.contentResolver.delete(Uri.parse(uriStr), null, null)
            call.resolve(JSObject().put("deleted", rows > 0))
        } catch (e: SecurityException) {
            call.reject("Android no deja que la app borre este archivo. Bórralo desde la app Archivos.")
        } catch (e: Exception) {
            call.reject("No se pudo eliminar: " + shortMsg(e))
        }
    }

    // ------------------------------------------------- guardado en Descargas

    private val allowedExt = setOf("mp4", "mkv", "webm", "mov", "mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")

    private fun saveOutputs(dir: File, state: DlState): List<JSObject> {
        val result = ArrayList<JSObject>()
        val files = dir.walkTopDown()
            .filter { it.isFile && allowedExt.contains(it.extension.lowercase()) }
            .toList()
        for (f in files) {
            val saved = saveToDownloads(f)
            state.log("💾 Guardado: " + saved.getString("path"))
            result.add(saved)
        }
        return result
    }

    /** Copia el archivo a Descargas/VideoDownloaderPro usando MediaStore (sin permisos extra, Android 10+). */
    private fun saveToDownloads(file: File): JSObject {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoDownloaderPro")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("No se pudo crear el archivo en Descargas")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("No se pudo escribir en Descargas")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return JSObject()
            .put("uri", uri.toString())
            .put("path", "Descargas/VideoDownloaderPro/" + file.name)
            .put("name", file.name)
            .put("mime", mime)
    }

    private fun shortMsg(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        val lines = m.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val err = lines.lastOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: m
        return err.take(300)
    }
}
