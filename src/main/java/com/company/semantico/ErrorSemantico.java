package com.company.semantico;

/**
 * Un error semantico (a diferencia de un error de sintaxis, que ya lo
 * reporta ANTLR solo): tipos incompatibles, variable/funcion/campo no
 * declarado, redeclaracion, cantidad de argumentos incorrecta, etc.
 */
public class ErrorSemantico {

    private final String mensaje;
    private final int linea;
    private final int columna;
    private final String archivo;

    /**
     * Constructor original (sin archivo) - se mantiene para no romper a
     * quien ya construya un ErrorSemantico "suelto" (fuera de
     * GestorErrores) con este; 'archivo' queda null, que es el mismo
     * comportamiento de siempre (toString() sin prefijo).
     */
    public ErrorSemantico(String mensaje, int linea, int columna) {
        this(mensaje, linea, columna, null);
    }

    /**
     * 'archivo' es, a proposito, solo un NOMBRE PARA MOSTRAR (ej.
     * "Pila.z", "utils/utils.y") y no una ruta real del disco - lo
     * necesita GestorErrores.setArchivoActual(...) para que un reporte
     * de Pig Latin (que junta errores de main.pig + de cada archivo
     * .y/.z importado en una sola lista) pueda distinguir de que
     * archivo vino cada error, ya que linea/columna son relativos al
     * archivo donde ocurrio el error, NO al .pig que el usuario tiene
     * abierto. Null (el caso de Y?/Zetariano analizados solos, fuera de
     * Pig Latin) se sigue mostrando sin prefijo, igual que siempre.
     */
    public ErrorSemantico(String mensaje, int linea, int columna, String archivo) {
        this.mensaje = mensaje;
        this.linea = linea;
        this.columna = columna;
        this.archivo = archivo;
    }

    public String getMensaje() {
        return mensaje;
    }

    public int getLinea() {
        return linea;
    }

    public int getColumna() {
        return columna;
    }

    public String getArchivo() {
        return archivo;
    }

    @Override
    public String toString() {
        String prefijo = archivo != null ? "[" + archivo + "] " : "";
        return prefijo + "linea " + linea + ":" + columna + " - " + mensaje;
    }
}
