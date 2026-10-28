package com.company.backend;

import java.util.LinkedHashMap;
import java.util.Map;

/** Donde vive cada variable/temporal de UNA funcion dentro de su marco de pila, y cuanto mide el marco. */
final class MarcoFuncion {

    /** nombre -> offset en bytes desde 'sp'. */
    final Map<String, Integer> offsetDe = new LinkedHashMap<>();

    /** true si es 'double' (8 bytes, registro flotante). */
    final Map<String, Boolean> esFlotante = new LinkedHashMap<>();

    /** Tamano total, alineado a 16 (ABI de RISC-V). */
    int tamanioFrame;

    /** Offset donde se guarda 'ra'. */
    int offsetRa;

    boolean esFlotante(String nombre) {
        return Boolean.TRUE.equals(esFlotante.get(nombre));
    }
}
