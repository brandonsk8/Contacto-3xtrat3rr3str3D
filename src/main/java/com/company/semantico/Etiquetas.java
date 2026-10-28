package com.company.semantico;

import java.util.List;

/**
 * Nombres de las etiquetas de constructores y metodos en el codigo intermedio.
 * Un metodo se llama metodo_Clase_nombre_aridad; si la clase tiene OTRA sobrecarga
 * con el mismo nombre y la misma cantidad de parametros (que solo se distingue por
 * los tipos), se agrega la firma (metodo_Clase_nombre_aridad__tipo1__tipo2) para que
 * cada etiqueta sea unica. Igual para constructores (ctor_Clase_aridad).
 *
 * La "firma" (tipos de los parametros) tambien viaja en las cuartetas de llamada
 * (call recv.metodo#firma) para que los backends elijan la sobrecarga correcta y
 * encuentren, en cada subclase, el metodo que la sobreescribe.
 */
public final class Etiquetas {

    private Etiquetas() {
    }

    /** Tipos de los parametros, aptos para ir dentro de un identificador ("entero__cadena", "" si no hay). */
    public static String firma(SimboloInvocable m) {
        StringBuilder sb = new StringBuilder();
        for (Simbolo p : m.getParametros()) {
            if (sb.length() > 0) {
                sb.append("__");
            }
            sb.append(p.getTipo().toString().replace("[]", "_arr").replaceAll("[^A-Za-z0-9_]", "_"));
        }
        return sb.toString();
    }

    public static String metodo(SimboloClase clase, SimboloInvocable m) {
        String base = "metodo_" + clase.getNombre() + "_" + m.getNombre() + "_" + m.getParametros().size();
        return hayOtraSobrecargaIgualAridad(clase.buscarMetodos(m.getNombre()), m) ? base + "__" + firma(m) : base;
    }

    public static String ctor(SimboloClase clase, SimboloInvocable k) {
        String base = "ctor_" + clase.getNombre() + "_" + k.getParametros().size();
        return hayOtraSobrecargaIgualAridad(clase.getConstructores(), k) ? base + "__" + firma(k) : base;
    }

    private static boolean hayOtraSobrecargaIgualAridad(List<SimboloInvocable> todas, SimboloInvocable m) {
        for (SimboloInvocable o : todas) {
            if (o != m && o.getParametros().size() == m.getParametros().size()) {
                return true;
            }
        }
        return false;
    }
}
