package com.company.semantico;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un ambito (scope) de simbolos, encadenado a su ambito padre. Se crea
 * uno nuevo por cada nivel de anidamiento real del programa: el cuerpo de
 * una funcion/metodo/constructor, y cada bloque que declare sus propias
 * variables (si/ciclos, en los lenguajes que lo permitan). La busqueda de
 * un nombre sube por la cadena de padres hasta encontrarlo o llegar al
 * ambito global (padre == null).
 *
 * No hay una tabla "global" especial aqui: el ambito global de un
 * archivo es simplemente una TablaSimbolos con padre == null, igual que
 * cualquier otra. Lo que si es especial es TablaSimbolosGlobal (otra
 * clase de este paquete): ese es el registro de estructuras/clases/
 * funciones definidas en CADA archivo importado, no el ambito de un
 * archivo en particular.
 */
public class TablaSimbolos {

    private final TablaSimbolos padre;
    private final Map<String, Simbolo> simbolos = new LinkedHashMap<>();

    public TablaSimbolos(TablaSimbolos padre) {
        this.padre = padre;
    }

    public TablaSimbolos getPadre() {
        return padre;
    }

    /**
     * Declara un simbolo en ESTE ambito (no revisa los ambitos padre: un
     * simbolo puede "tapar" (shadow) a otro de un ambito mas externo, eso
     * es valido; lo que no es valido es declarar dos veces el mismo
     * nombre en el mismo ambito).
     *
     * @return true si se declaro correctamente; false si ya existia un
     *         simbolo con ese nombre en este mismo ambito (error
     *         semantico de "redeclaracion" que el Visitor debe reportar).
     */
    public boolean declarar(Simbolo simbolo) {
        if (simbolos.containsKey(simbolo.getNombre())) {
            return false;
        }
        simbolos.put(simbolo.getNombre(), simbolo);
        return true;
    }

    /** Busca solo en este ambito, sin subir a los padres. */
    public Simbolo buscarLocal(String nombre) {
        return simbolos.get(nombre);
    }

    /** Busca en este ambito y, si no lo encuentra, sube por la cadena de padres. */
    public Simbolo buscar(String nombre) {
        Simbolo s = simbolos.get(nombre);
        if (s != null) {
            return s;
        }
        return padre != null ? padre.buscar(nombre) : null;
    }

    public Map<String, Simbolo> getSimbolosLocales() {
        return simbolos;
    }
}
