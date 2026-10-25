package com.videodownloader.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Datos que pasan entre pantallas nativas y la interfaz web. */
public class SharedStore {
    /** Texto/enlace recibido desde "Compartir" de otras apps. */
    public static volatile String text = null;

    /** Enlaces que el usuario agregó desde el navegador de YouTube. */
    public static final List<String> pending = Collections.synchronizedList(new ArrayList<String>());
}
