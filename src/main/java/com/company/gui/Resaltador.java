package com.company.gui;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;

import java.awt.Color;
import java.awt.FontMetrics;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INCREMENTO 2 del IDE: coloreado de sintaxis basico para las 3
 * palabras reservadas/cadenas/comentarios/numeros de Y?, Zetariano y
 * Pig Latin. Trabaja sobre un JTextPane (no JTextArea - un JTextArea
 * no soporta color por caracter, solo un color uniforme para todo el
 * texto) con un solo regex por lenguaje que reconoce, en un solo pase,
 * las 4 categorias que coloreamos.
 *
 * 'aplicar(...)' vuelve a colorear el documento COMPLETO cada vez que
 * se llama (no incremental) - para el tamaño de archivo de un proyecto
 * de estudiante esto es instantaneo, asi que no hizo falta optimizar
 * un resaltado incremental (solo la porcion editada).
 *
 * IMPORTANTE para quien toque esta clase: 'doc.setCharacterAttributes'
 * (como lo usa aplicar()) SOLO cambia atributos de estilo, no dispara
 * DocumentListener.insertUpdate/removeUpdate - solo changedUpdate. Por
 * eso VentanaPrincipal engancha el auto-resaltado-al-escribir en
 * insertUpdate/removeUpdate (nunca en changedUpdate): si escuchara
 * changedUpdate tambien, cada llamada a aplicar() dispararia otra
 * llamada a aplicar() en bucle infinito.
 */
public class Resaltador {

    private static final Color COLOR_PALABRA_CLAVE = new Color(0, 0, 200);
    private static final Color COLOR_CADENA = new Color(0, 128, 0);
    private static final Color COLOR_COMENTARIO = new Color(128, 128, 128);
    private static final Color COLOR_NUMERO = new Color(153, 0, 153);
    private static final Color COLOR_NORMAL = Color.BLACK;

    private static final SimpleAttributeSet ESTILO_PALABRA_CLAVE = estilo(COLOR_PALABRA_CLAVE, true);
    private static final SimpleAttributeSet ESTILO_CADENA = estilo(COLOR_CADENA, false);
    private static final SimpleAttributeSet ESTILO_COMENTARIO = estiloItalica(COLOR_COMENTARIO);
    private static final SimpleAttributeSet ESTILO_NUMERO = estilo(COLOR_NUMERO, false);
    private static final SimpleAttributeSet ESTILO_NORMAL = estilo(COLOR_NORMAL, false);

    // Palabras reservadas de cada lenguaje - ver los .g4 en src/main/antlr4
    // (YLexer.g4, Zetariano.g4, PigLatin.g4) para la lista original.
    private static final Pattern PATRON_Y = construirPatron(
            "%estructuras", "%funciones", "estructura", "definir", "retornar", "si", "entonces",
            "sino", "contrario", "elegir", "caso", "siempre", "romper", "continuar", "para",
            "mientras", "hacer", "verdadero", "falso", "entero", "cadena", "flotante", "caracter", "bool");

    private static final Pattern PATRON_ZETARIANO = construirPatron(
            "public", "private", "class", "void", "new", "if", "else", "switch", "case", "default",
            "for", "while", "do", "break", "continue", "return", "true", "false", "null", "this",
            "int", "double", "char", "boolean", "String");

    private static final Pattern PATRON_PIG = construirPatron(
            "import", "esto", "series", "si", "aliter", "dum", "facere", "per", "perge",
            "interrumpe", "novus", "numerus", "textum", "decimalis", "littera", "verum", "falsus",
            "finis", "FINIS", "VARIABILES>", "MAIOR>");

    private Resaltador() {
    }

    /**
     * Vuelve a colorear TODO el texto de 'editor' segun la extension
     * dada ("y", "z" o "pig"). Cualquier otra extension (o null) deja
     * el texto en color normal, sin resaltar nada.
     */
    public static void aplicar(JTextPane editor, String extension) {
        StyledDocument doc = editor.getStyledDocument();
        String texto;
        try {
            texto = doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            return;
        }

        configurarTabulado(editor, doc, texto.length());

        doc.setCharacterAttributes(0, texto.length(), ESTILO_NORMAL, true);

        Pattern patron = patronPara(extension);
        if (patron == null) {
            return;
        }

        Matcher m = patron.matcher(texto);
        while (m.find()) {
            SimpleAttributeSet estilo;
            if (m.group("CADENA") != null) {
                estilo = ESTILO_CADENA;
            } else if (m.group("COMENTARIO") != null) {
                estilo = ESTILO_COMENTARIO;
            } else if (m.group("NUMERO") != null) {
                estilo = ESTILO_NUMERO;
            } else {
                estilo = ESTILO_PALABRA_CLAVE;
            }
            doc.setCharacterAttributes(m.start(), m.end() - m.start(), estilo, true);
        }
    }

    private static Pattern patronPara(String extension) {
        if (extension == null) {
            return null;
        }
        switch (extension) {
            case "y":
                return PATRON_Y;
            case "z":
                return PATRON_ZETARIANO;
            case "pig":
                return PATRON_PIG;
            default:
                return null;
        }
    }

    /**
     * Tabs de 4 espacios visuales (en vez del tab por defecto de
     * JTextPane, mucho mas ancho) - importa especialmente para Y?, que
     * usa indentacion significativa: con el tab por defecto la
     * indentacion se ve exagerada y es mas dificil de leer.
     */
    private static void configurarTabulado(JTextPane editor, StyledDocument doc, int longitudTexto) {
        FontMetrics fm = editor.getFontMetrics(editor.getFont());
        int anchoTab = fm.charWidth(' ') * 4;
        TabStop[] paradas = new TabStop[64];
        for (int i = 0; i < paradas.length; i++) {
            paradas[i] = new TabStop(anchoTab * (i + 1));
        }
        SimpleAttributeSet atributosParrafo = new SimpleAttributeSet();
        StyleConstants.setTabSet(atributosParrafo, new TabSet(paradas));
        doc.setParagraphAttributes(0, longitudTexto, atributosParrafo, false);
    }

    private static Pattern construirPatron(String... palabrasClave) {
        StringBuilder alternativas = new StringBuilder();
        for (String palabra : palabrasClave) {
            if (alternativas.length() > 0) {
                alternativas.append('|');
            }
            alternativas.append(Pattern.quote(palabra));
        }
        String regex =
                "(?<CADENA>\"(?:\\\\.|[^\"\\\\])*\")"
                + "|(?<COMENTARIO>//[^\\n]*|(?s:/\\*.*?\\*/))"
                + "|(?<NUMERO>(?<![A-Za-z0-9_])\\d+(?:\\.\\d+)?(?![A-Za-z0-9_]))"
                + "|(?<PALABRACLAVE>(?<![A-Za-z0-9_])(?:" + alternativas + ")(?![A-Za-z0-9_]))";
        return Pattern.compile(regex);
    }

    private static SimpleAttributeSet estilo(Color color, boolean negrita) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, negrita);
        return a;
    }

    private static SimpleAttributeSet estiloItalica(Color color) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setItalic(a, true);
        return a;
    }
}
