package com.company.backend;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pool global de literales para la seccion .data de RARS: cadenas
 * (.asciz - RARS NO reconoce .asciiz) y decimales (.double). Cada literal
 * distinto se emite una sola vez y se referencia por su etiqueta.
 */
final class AlmacenLiterales {

    private final Map<String, String> cadenas = new LinkedHashMap<>();   // texto con comillas -> etiqueta
    private final Map<String, String> decimales = new LinkedHashMap<>(); // texto -> etiqueta

    /** @param textoConComillas la cadena tal cual viene en la cuarteta, con sus comillas. */
    String etiquetaCadena(String textoConComillas) {
        return cadenas.computeIfAbsent(textoConComillas, k -> "str_" + cadenas.size());
    }

    String etiquetaDecimal(String texto) {
        return decimales.computeIfAbsent(texto, k -> "dbl_" + decimales.size());
    }

    String emitirSeccionDatos() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cadenas.entrySet()) {
            sb.append(e.getValue()).append(": .asciz ").append(e.getKey()).append("\n");
        }
        if (!decimales.isEmpty()) {
            sb.append(".align 3\n");
            for (Map.Entry<String, String> e : decimales.entrySet()) {
                sb.append(e.getValue()).append(": .double ").append(e.getKey()).append("\n");
            }
        }
        return sb.toString();
    }
}
