package com.company.pig;

import com.company.semantico.SimboloClase;
import com.company.y.YParser;
import com.company.zetariano.ZetarianoParser;

/**
 * Resultado de resolver un solo 'import': el arbol ya parseado del
 * archivo importado (.y o .z), listo para que PigLatinAnalizador
 * (pasada 2) recorra su cuerpo sin tener que volver a parsearlo.
 *
 * Solo uno de los dos pares de datos (arbolY / arbolZetariano+clase)
 * esta lleno, segun 'esZetariano'. 'clase' se guarda aparte porque
 * ZetarianoAnalizador.analizarPrograma(...) la necesita como segundo
 * argumento.
 *
 * 'nombreArchivo' es solo para mostrar en errores (ej. "Pila.z",
 * "utils/utils.y").
 */
public final class ArchivoImportado {

    private final boolean esZetariano;
    private final YParser.ProgramaContext arbolY;
    private final ZetarianoParser.ProgramaContext arbolZetariano;
    private final SimboloClase clase;
    private final String nombreArchivo;

    private ArchivoImportado(boolean esZetariano, YParser.ProgramaContext arbolY,
                             ZetarianoParser.ProgramaContext arbolZetariano, SimboloClase clase,
                             String nombreArchivo) {
        this.esZetariano = esZetariano;
        this.arbolY = arbolY;
        this.arbolZetariano = arbolZetariano;
        this.clase = clase;
        this.nombreArchivo = nombreArchivo;
    }

    public static ArchivoImportado deY(YParser.ProgramaContext arbol, String nombreArchivo) {
        return new ArchivoImportado(false, arbol, null, null, nombreArchivo);
    }

    public static ArchivoImportado deZetariano(ZetarianoParser.ProgramaContext arbol, SimboloClase clase, String nombreArchivo) {
        return new ArchivoImportado(true, null, arbol, clase, nombreArchivo);
    }

    public String getNombreArchivo() {
        return nombreArchivo;
    }

    public boolean esZetariano() {
        return esZetariano;
    }

    public YParser.ProgramaContext getArbolY() {
        return arbolY;
    }

    public ZetarianoParser.ProgramaContext getArbolZetariano() {
        return arbolZetariano;
    }

    public SimboloClase getClase() {
        return clase;
    }
}