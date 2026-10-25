#!/usr/bin/env python3
"""
Parchea el proyecto Android generado por `npx cap add android`:
  - copia el plugin nativo (Kotlin) y MainActivity
  - agrega Kotlin, yt-dlp (youtubedl-android) y FFmpeg a Gradle
  - ajusta minSdk, empaquetado de librerías nativas y el manifest
Es idempotente: se puede ejecutar varias veces.
"""
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
APP = ANDROID / "app"
NATIVE = ROOT / "native"

PKG = "com.videodownloader.pro"
KOTLIN_VERSION = "1.9.25"
YTDL_VERSION = "0.17.4"
MIN_SDK = 29


def fail(msg):
    sys.exit("ERROR DE PARCHE: " + msg)


def read(p):
    return p.read_text(encoding="utf-8")


def write(p, s):
    p.write_text(s, encoding="utf-8")


if not APP.is_dir():
    fail("no existe android/app. Ejecuta primero: npx cap add android")

# 1) Código nativo -----------------------------------------------------------
java_dir = APP / "src" / "main" / "java" / pathlib.Path(*PKG.split("."))
java_dir.mkdir(parents=True, exist_ok=True)
for name in ("YtDlpPlugin.kt", "SharedStore.java", "MainActivity.java"):
    shutil.copy2(NATIVE / name, java_dir / name)
print("[ok] código nativo copiado a", java_dir)

# 2) minSdk ------------------------------------------------------------------
variables = ANDROID / "variables.gradle"
if variables.exists():
    s = read(variables)
    s2, n = re.subn(r"minSdkVersion\s*=\s*\d+", "minSdkVersion = %d" % MIN_SDK, s)
    if n == 0:
        fail("no se encontró minSdkVersion en variables.gradle")
    write(variables, s2)
    print("[ok] minSdkVersion =", MIN_SDK)
else:
    fail("no existe android/variables.gradle")

# 3) build.gradle raíz: plugin de Kotlin --------------------------------------
root_gradle = ANDROID / "build.gradle"
s = read(root_gradle)
if "kotlin-gradle-plugin" not in s:
    s, n = re.subn(
        r"(classpath\s+['\"]com\.android\.tools\.build:gradle:[^'\"]+['\"])",
        r"\1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:%s'" % KOTLIN_VERSION,
        s,
        count=1,
    )
    if n == 0:
        fail("no se encontró el classpath de AGP en android/build.gradle")
    write(root_gradle, s)
print("[ok] plugin de Kotlin", KOTLIN_VERSION)

# 4) app/build.gradle ----------------------------------------------------------
app_gradle = APP / "build.gradle"
s = read(app_gradle)

if "kotlin-android" not in s:
    s, n = re.subn(
        r"(apply plugin:\s*['\"]com\.android\.application['\"])",
        r"\1\napply plugin: 'kotlin-android'",
        s,
        count=1,
    )
    if n == 0:
        fail("no se encontró 'apply plugin: com.android.application'")

# Capacitor 7 define la versión de Java en capacitor.build.gradle (no en app/build.gradle).
jvm = None
for gradle_file in (app_gradle, APP / "capacitor.build.gradle"):
    if gradle_file.exists():
        m = re.search(r"JavaVersion\.VERSION_(\d+)", read(gradle_file))
        if m:
            jvm = m.group(1)
            break
if jvm is None:
    jvm = "21"

if "useLegacyPackaging" not in s:
    block = (
        "android {\n"
        "    kotlinOptions { jvmTarget = '%s' }\n"
        "    packaging {\n"
        "        jniLibs { useLegacyPackaging = true }\n"
        "    }\n" % jvm
    )
    s, n = re.subn(r"android\s*\{\n", lambda _m: block, s, count=1)
    if n == 0:
        fail("no se encontró el bloque android { en app/build.gradle")

if "abiFilters" not in s:
    s, n = re.subn(
        r"(defaultConfig\s*\{\n)",
        r"\1        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }\n",
        s,
        count=1,
    )
    if n == 0:
        fail("no se encontró defaultConfig en app/build.gradle")

if "youtubedl-android" not in s:
    deps = (
        "\n    implementation 'io.github.junkfood02.youtubedl-android:library:%s'\n"
        "    implementation 'io.github.junkfood02.youtubedl-android:ffmpeg:%s'\n"
    ) % (YTDL_VERSION, YTDL_VERSION)
    s, n = re.subn(r"(\ndependencies\s*\{\n)", lambda _m: _m.group(1) + deps.lstrip("\n"), s, count=1)
    if n == 0:
        fail("no se encontró el bloque dependencies en app/build.gradle")

write(app_gradle, s)
print("[ok] app/build.gradle (Kotlin %s, yt-dlp %s, JVM %s)" % (KOTLIN_VERSION, YTDL_VERSION, jvm))

# 5) AndroidManifest -----------------------------------------------------------
manifest = APP / "src" / "main" / "AndroidManifest.xml"
s = read(manifest)

if "extractNativeLibs" not in s:
    s, n = re.subn(r"<application(\s)", r'<application android:extractNativeLibs="true"\1', s, count=1)
    if n == 0:
        fail("no se encontró <application> en el manifest")

if "android.intent.action.SEND" not in s:
    share = (
        "        <intent-filter>\n"
        '            <action android:name="android.intent.action.SEND" />\n'
        '            <category android:name="android.intent.category.DEFAULT" />\n'
        '            <data android:mimeType="text/plain" />\n'
        "        </intent-filter>\n"
        "        "
    )
    s, n = re.subn(r"</activity>", lambda _m: share + "</activity>", s, count=1)
    if n == 0:
        fail("no se encontró </activity> en el manifest")

write(manifest, s)
print("[ok] AndroidManifest (nativeLibs + compartir enlaces)")
print("Parche completado.")
