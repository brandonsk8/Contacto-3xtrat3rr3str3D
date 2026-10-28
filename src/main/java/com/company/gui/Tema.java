package com.company.gui;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Font;

/**
 * Paleta de la interfaz (claro / oscuro). Usa solo Swing (Nimbus con
 * colores propios), sin librerias externas. Los colores del editor y del
 * resaltado de sintaxis se leen de aqui.
 */
public final class Tema {

    private static boolean oscuro = true;

    public static Color fondo, panel, editorFondo, texto, textoSuave, gutterFondo, gutterTexto, borde, acento,
            seleccion, kw, cadena, comentario, numero, ok, error, consolaFondo;

    static {
        paleta(true);
    }

    private Tema() {
    }

    // Indica si el tema activo es el oscuro.
    public static boolean esOscuro() {
        return oscuro;
    }

    // Convierte un RGB entero a Color.
    private static Color c(int rgb) {
        return new Color(rgb);
    }

    // Carga la paleta de colores del tema claro u oscuro.
    private static void paleta(boolean o) {
        oscuro = o;
        if (o) {
            fondo = c(0x1e2127);
            panel = c(0x252a33);
            editorFondo = c(0x1b1e24);
            consolaFondo = c(0x16181d);
            texto = c(0xd7dce5);
            textoSuave = c(0x8b93a3);
            gutterFondo = c(0x20242b);
            gutterTexto = c(0x5c6475);
            borde = c(0x343b47);
            acento = c(0x4c8dff);
            seleccion = c(0x2f4f86);
            kw = c(0xc678dd);
            cadena = c(0x98c379);
            comentario = c(0x6b7385);
            numero = c(0xd19a66);
            ok = c(0x7ec27e);
            error = c(0xf26b6b);
        } else {
            fondo = c(0xf3f4f7);
            panel = c(0xffffff);
            editorFondo = c(0xffffff);
            consolaFondo = c(0xfafbfc);
            texto = c(0x23272f);
            textoSuave = c(0x6b7280);
            gutterFondo = c(0xeef0f4);
            gutterTexto = c(0x9aa1ae);
            borde = c(0xd5d9e0);
            acento = c(0x2b6fe6);
            seleccion = c(0xcfe0ff);
            kw = c(0x7b2fbf);
            cadena = c(0x2e7d32);
            comentario = c(0x8a909c);
            numero = c(0xb35a00);
            ok = c(0x2e7d32);
            error = c(0xc62828);
        }
    }

    /** Instala Nimbus con la paleta pedida; llamar ANTES de crear la ventana (o seguido de updateComponentTreeUI). */
    public static void aplicar(boolean oscuroNuevo) {
        paleta(oscuroNuevo);
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignorada) {
            // si Nimbus no esta, queda el aspecto por defecto
        }
        UIManager.put("control", new ColorUIResource(fondo));
        UIManager.put("nimbusBase", new ColorUIResource(oscuro ? c(0x2d333d) : c(0xdfe3ea)));
        UIManager.put("nimbusBlueGrey", new ColorUIResource(oscuro ? c(0x3a4250) : c(0xc9cfd9)));
        UIManager.put("nimbusLightBackground", new ColorUIResource(panel));
        UIManager.put("nimbusSelectionBackground", new ColorUIResource(seleccion));
        UIManager.put("nimbusFocus", new ColorUIResource(acento));
        UIManager.put("nimbusOrange", new ColorUIResource(acento));
        UIManager.put("text", new ColorUIResource(texto));
        UIManager.put("info", new ColorUIResource(panel));
        UIManager.put("infoText", new ColorUIResource(texto));
        UIManager.put("menu", new ColorUIResource(panel));
        UIManager.put("menuText", new ColorUIResource(texto));
        UIManager.put("nimbusDisabledText", new ColorUIResource(textoSuave));
        UIManager.put("Tree.background", new ColorUIResource(panel));
        UIManager.put("Tree.textBackground", new ColorUIResource(panel));
        UIManager.put("Tree.textForeground", new ColorUIResource(texto));
        UIManager.put("Tree.selectionForeground", new ColorUIResource(oscuro ? Color.WHITE : texto));
        UIManager.put("Table.background", new ColorUIResource(panel));
        UIManager.put("Table.alternateRowColor", new ColorUIResource(oscuro ? c(0x2a303a) : c(0xf5f7fa)));
        UIManager.put("TextField.background", new ColorUIResource(editorFondo));
        UIManager.put("TextField.foreground", new ColorUIResource(texto));
        UIManager.put("TextArea.background", new ColorUIResource(consolaFondo));
        UIManager.put("TextArea.foreground", new ColorUIResource(texto));
        UIManager.put("TextPane.background", new ColorUIResource(editorFondo));
        UIManager.put("TextPane.foreground", new ColorUIResource(texto));
        UIManager.put("TextPane.caretForeground", new ColorUIResource(texto));
        UIManager.put("TextArea.caretForeground", new ColorUIResource(texto));
        UIManager.put("SplitPane.dividerSize", 6);
        UIManager.put("ScrollPane.border", BorderFactory.createLineBorder(borde));
        Resaltador.actualizarColores();
    }

    // Color de texto principal del tema activo.
    public static Color oscuroTexto() {
        return oscuro ? Color.WHITE : texto;
    }

    // Fuente monoespaciada del editor.
    public static Font fuenteCodigo(int tam) {
        for (String n : new String[]{"JetBrains Mono", "Cascadia Code", "Consolas", "DejaVu Sans Mono", "Menlo"}) {
            Font f = new Font(n, Font.PLAIN, tam);
            if (f.getFamily().equalsIgnoreCase(n)) {
                return f;
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, tam);
    }
}
