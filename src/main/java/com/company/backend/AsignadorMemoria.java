package com.company.backend;

import java.util.Map;

/**
 * Pase A-bis: decide DONDE vive cada variable/temporal (offset fijo desde
 * 'sp'). Criterio simple a proposito: TODO vive siempre en memoria; cada
 * cuarteta carga sus operandos a registros de borrador, opera y guarda el
 * resultado de vuelta. Menos eficiente que un compilador real, pero
 * correcto y facil de verificar a mano.
 */
final class AsignadorMemoria {

    private static final int TAMANIO_ENTERO = 4;
    private static final int TAMANIO_DOBLE = 8;

    MarcoFuncion asignar(Map<String, String> tipoDe) {
        MarcoFuncion marco = new MarcoFuncion();
        int offset = 0;
        for (Map.Entry<String, String> e : tipoDe.entrySet()) {
            boolean flotante = "double".equals(e.getValue());
            int tamanio = flotante ? TAMANIO_DOBLE : TAMANIO_ENTERO;
            if (flotante && offset % TAMANIO_DOBLE != 0) {
                offset += TAMANIO_ENTERO; // alinear el double a multiplo de 8
            }
            marco.offsetDe.put(e.getKey(), offset);
            marco.esFlotante.put(e.getKey(), flotante);
            offset += tamanio;
        }
        marco.offsetRa = offset;
        int total = offset + TAMANIO_ENTERO;
        marco.tamanioFrame = ((total + 15) / 16) * 16;
        return marco;
    }
}
