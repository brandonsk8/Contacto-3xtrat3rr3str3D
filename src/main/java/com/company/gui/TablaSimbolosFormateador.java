package com.company.gui;

import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloEstructura;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolos;
import com.company.semantico.TablaSimbolosGlobal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Convierte una TablaSimbolosGlobal (estructuras, clases y funciones
 * registradas, con sus campos/parametros/variables locales) en filas de
 * texto listas para mostrar en una tabla: Ambito | Nombre | Categoria |
 * Tipo | Linea.
 *
 * Solo alcanza el ambito de nivel superior de cada funcion/metodo/
 * constructor (parametros y variables declaradas directamente en el
 * cuerpo): las variables declaradas dentro de un bloque anidado (si,
 * mientras, etc.) viven en un TablaSimbolos que no queda accesible
 * despues de terminar de analizar esa funcion.
 */
public final class TablaSimbolosFormateador {

    private TablaSimbolosFormateador() {
    }

    public static List<String[]> filas(TablaSimbolosGlobal global) {
        List<String[]> filas = new ArrayList<>();

        for (SimboloEstructura estructura : global.getEstructuras().values()) {
            filas.add(fila("(global)", estructura.getNombre(), estructura.getCategoria(), estructura.getTipo(), estructura.getLinea()));
            for (Simbolo campo : estructura.getCampos().values()) {
                filas.add(fila(estructura.getNombre(), campo.getNombre(), campo.getCategoria(), campo.getTipo(), campo.getLinea()));
            }
        }

        for (SimboloClase clase : global.getClases().values()) {
            filas.add(fila("(global)", clase.getNombre(), clase.getCategoria(), clase.getTipo(), clase.getLinea()));
            for (Simbolo campo : clase.getCampos().values()) {
                filas.add(fila(clase.getNombre(), campo.getNombre(), campo.getCategoria(), campo.getTipo(), campo.getLinea()));
            }
            for (SimboloInvocable constructor : clase.getConstructores()) {
                String ambito = clase.getNombre() + "." + clase.getNombre() + "(" + firma(constructor) + ")";
                filas.add(fila(clase.getNombre(), clase.getNombre(), constructor.getCategoria(), constructor.getTipoRetorno(), constructor.getLinea()));
                agregarAmbitoLocal(filas, ambito, constructor);
            }
            for (Map.Entry<String, List<SimboloInvocable>> entrada : clase.getMetodos().entrySet()) {
                for (SimboloInvocable metodo : entrada.getValue()) {
                    String ambito = clase.getNombre() + "." + metodo.getNombre() + "(" + firma(metodo) + ")";
                    filas.add(fila(clase.getNombre(), metodo.getNombre(), metodo.getCategoria(), metodo.getTipoRetorno(), metodo.getLinea()));
                    agregarAmbitoLocal(filas, ambito, metodo);
                }
            }
        }

        for (SimboloInvocable funcion : global.getFunciones().values()) {
            String ambito = funcion.getNombre() + "(" + firma(funcion) + ")";
            filas.add(fila("(global)", funcion.getNombre(), funcion.getCategoria(), funcion.getTipoRetorno(), funcion.getLinea()));
            agregarAmbitoLocal(filas, ambito, funcion);
        }

        return filas;
    }

    private static void agregarAmbitoLocal(List<String[]> filas, String ambito, SimboloInvocable invocable) {
        TablaSimbolos local = invocable.getAmbitoLocal();
        if (local == null) {
            return;
        }
        for (Simbolo simbolo : local.getSimbolosLocales().values()) {
            filas.add(fila(ambito, simbolo.getNombre(), simbolo.getCategoria(), simbolo.getTipo(), simbolo.getLinea()));
        }
    }

    private static String firma(SimboloInvocable invocable) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < invocable.getParametros().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(invocable.getParametros().get(i).getTipo());
        }
        return sb.toString();
    }

    private static String[] fila(String ambito, String nombre, Object categoria, Object tipo, int linea) {
        return new String[]{ambito, nombre, String.valueOf(categoria), String.valueOf(tipo), String.valueOf(linea)};
    }
}
