package com.videodownloader.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Datos que pasan entre pantallas nativas y la interfaz web. */
public class SharedStore {
    /** Texto/enlace recibido desde "Compartir" de otras apps. */
    public static volatile String text = null;

    /** Videos que el usuario agregó desde el navegador (JSON con url, formato y calidad). */
    public static final List<String> pending = Collections.synchronizedList(new ArrayList<String>());

    /** Lista de reproducción que recibe el reproductor. */
    public static volatile List<String> queueUris = new ArrayList<>();
    public static volatile List<String> queueTitles = new ArrayList<>();
    public static volatile List<String> queueArtists = new ArrayList<>();
}
