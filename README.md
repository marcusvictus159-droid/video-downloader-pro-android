# 🎥 Video Downloader Pro — versión Android (APK)

Port a Android de [video-youtube-dowloader](https://github.com/RobertLYucra/video-youtube-dowloader) (Electron/Windows).
Usa **Capacitor** para la interfaz y la librería nativa **youtubedl-android** (yt-dlp + FFmpeg + Python embebidos) como motor.

## Qué trae
- Interfaz con navegación inferior (Nuevo, Descargas, Ajustes), tema automático/claro/oscuro y barras del sistema integradas.
- Videos MP4 (con tope de calidad) o audio MP3.
- Cola de descargas con barra de progreso, ETA y botón de cancelar.
- **Motor auto-actualizable**: se actualiza cada 2 días y, si una descarga falla, se actualiza y reintenta solo.
- **Modo tanque**: 50 reintentos si se cae el internet.
- **Historial** para omitir videos ya descargados.
- **Filtro anti-mixes** (`--no-playlist`), con opción para permitir listas completas.
- Recibe enlaces desde **Compartir** de otras apps (YouTube, Facebook, TikTok…).
- Guarda en `Descargas/VideoDownloaderPro` sin pedir permisos (Android 10+).

## Compilar el APK desde el celular (sin PC)
1. Crea un repositorio nuevo en GitHub y sube **todo el contenido de esta carpeta** (incluida `.github/`).
2. Ve a **Actions → Compilar APK → Run workflow** (también corre solo en cada push a `main`).
3. Cuando termine (unos 8–12 min), abre **Releases** y descarga `VideoDownloaderPro.apk`.
4. Instálalo (permite «instalar apps de origen desconocido»).

El APK es *debug*: se instala directo, pero no sirve para Play Store (que además no admite este tipo de apps).

## Estructura
```
www/                  interfaz (HTML/CSS/JS)
native/               plugin Kotlin (yt-dlp), MainActivity y SharedStore
scripts/patch_android.py   parchea el proyecto Android generado por Capacitor
.github/workflows/    compilación en GitHub Actions
```
`android/` no se sube: se genera en cada compilación con `npx cap add android` + el parche.

## Iconos de los sitios
La tira «Navegar y descargar» usa una letra por sitio. Para mostrar tus propios iconos, copia PNG cuadrados a `www/icons/` con el nombre del sitio (`youtube.png`, `facebook.png`, `tiktok.png`, `instagram.png`, `x.png`, `vimeo.png`) y se verán automáticamente.

## Cambiar el icono
Reemplaza los PNG de 1024x1024 en `assets/` (`icon-only.png`, `icon-foreground.png` con fondo transparente e `icon-background.png`) y haz push: el workflow genera todos los tamaños.

## Notas
- Android 10 o superior (minSdk 29). Incluye arm64-v8a y armeabi-v7a.
- Mantén la app abierta mientras descarga (no hay servicio en segundo plano todavía).
- Si YouTube cambia algo, usa **Actualizar** en la tarjeta del motor.
- Para subir la versión de la librería, cambia `YTDL_VERSION` en `scripts/patch_android.py`.
- Descarga solo contenido propio o que tengas permiso de descargar.
