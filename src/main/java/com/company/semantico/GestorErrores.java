package com.company.semantico;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Junta todos los errores semanticos encontrados durante una pasada,
 * igual que ANTLR junta sus errores de sintaxis sin frenar en el
 * primero. Cada Visitor reporta aqui en vez de lanzar una excepcion, asi
 * el usuario ve TODOS los problemas de una corrida, no uno a la vez.
 */
public class GestorErrores {

    private final List<ErrorSemantico> errores = new ArrayList<>();

    // Nombre del archivo que se esta analizando en este momento (para
    // mostrar, ej. "Pila.z") - null cuando no aplica (el caso de
    // siempre: Y?/Zetariano analizados solos). Lo usa PigLatinAnalizador
    // (y RegistradorFirmasPigLatin) para "etiquetar" cada import antes
    // de correr YVisitor/ZetarianoAnalizador/RegistradorFirmasX sobre
    // el, y lo vuelve a poner en null antes de analizar el cuerpo del
    // propio .pig - asi cada ErrorSemantico que se reporte mientras
    // tanto queda con el archivo correcto, sin tener que pasarlo por
    // parametro en cada uno de los muchisimos call-sites de
    // errores.reportar(...) de YVisitor/ZetarianoAnalizador/etc.
    private String archivoActual = null;

    public void setArchivoActual(String nombreArchivo) {
        this.archivoActual = nombreArchivo;
    }

    public void reportar(int linea, int columna, String mensaje) {
        errores.add(new ErrorSemantico(mensaje, linea, columna, archivoActual));
    }

    public boolean tieneErrores() {
        return !errores.isEmpty();
    }

    public List<ErrorSemantico> getErrores() {
        return Collections.unmodifiableList(errores);
    }

    public void imprimir() {
        for (ErrorSemantico e : errores) {
            System.out.println(e);
        }
    }
}