package com.company.backend;

import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloEstructura;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Traduce un Tipo semantico (o un texto de cuarteta) a su tipo C
 * equivalente. La usan tanto InferenciaTipos (Pase A) como
 * EmisorSentencias (Pase B) y GeneradorC (para las firmas y las
 * definiciones de struct), asi que las 3 comparten exactamente el mismo
 * criterio de que esta soportado y como se llama cada cosa en C.
 *
 * Regla de oro: una estructura de Y? viaja por VALOR ('struct X') salvo
 * como campo de otra estructura, donde pasa a puntero ('struct X*') para
 * evitar tamaño infinito. Una clase de Zetariano, en cambio, SIEMPRE es
 * puntero (tiene 'new'/heap real). traducirAccesoC() decide '.' vs '->'
 * en cada paso de una cadena de acceso segun esto.
 */
final class TiposC {

    static final Set<String> LITERALES_VERDADERO = Set.of("verdadero", "true", "verum");
    static final Set<String> LITERALES_FALSO = Set.of("falso", "false", "falsus");

    static final Pattern ENTERO_LIT = Pattern.compile("-?\\d+");
    static final Pattern DECIMAL_LIT = Pattern.compile("-?\\d+\\.\\d+");
    static final Pattern CARACTER_LIT = Pattern.compile("'(\\\\.|[^'\\\\])'");
    static final Pattern CADENA_LIT = Pattern.compile("\".*\"", Pattern.DOTALL);

    private final TablaSimbolosGlobal global;

    TiposC(TablaSimbolosGlobal global) {
        this.global = global;
    }

    /** Tipo C de una variable: struct por valor si es estructura, 'struct X*' si es clase. */
    String tipoC(Tipo tipo) {
        if (tipo == null) {
            return null;
        }
        switch (tipo.getCategoria()) {
            case ENTERO: return "int";
            case DECIMAL: return "double";
            case CARACTER: return "char";
            case BOOLEANO: return "int";
            case VOID: return "void";
            case CADENA: return "char*";
            case ESTRUCTURA:
                return global.buscarEstructura(tipo.getNombreDefinido()) != null
                        ? "struct " + tipo.getNombreDefinido()
                        : null;
            case CLASE:
                return global.buscarClase(tipo.getNombreDefinido()) != null
                        ? "struct " + tipo.getNombreDefinido() + "*"
                        : null;
            default: return null; // ARREGLO, ERROR, NULO: cada uno se resuelve aparte
        }
    }

    /**
     * Tipo C base de un elemento de arreglo, a partir del nombre que deja
     * 'new_array' en la cuarteta (Tipo.toString() de un primitivo: "entero",
     * "decimal", "booleano", ...). Solo estos 3 estan soportados: 'caracter'
     * se deja fuera a proposito porque su tipo C ('char*' visto como
     * arreglo) chocaria con como el generador ya usa 'char*' para CADENA
     * (imprimir/leer/concat lo tratarian como si fuera una cadena
     * terminada en '\0'); 'cadena', 'estructura' y 'clase' quedan
     * pendientes por ahora.
     */
    String tipoCPrimitivoDeArreglo(String nombreTipoBase) {
        if (nombreTipoBase == null) {
            return null;
        }
        switch (nombreTipoBase) {
            case "entero": return "int";
            case "decimal": return "double";
            case "booleano": return "int";
            default: return null;
        }
    }

    /**
     * Tipo C de una variable ARREGLO de 1 dimension (un puntero al tipo
     * base, reservado con malloc en tiempo de ejecucion - ver el case
     * "new_array" en EmisorSentencias). Arreglos de mas de 1 dimension, o
     * de un tipo base no soportado, devuelven null (igual que tipoC()
     * para cualquier otro tipo no soportado).
     */
    String tipoCArreglo(Tipo tipo) {
        if (tipo.getCategoria() != Tipo.Categoria.ARREGLO || tipo.getDimensiones() != 1) {
            return null;
        }
        String baseC = tipoCPrimitivoDeArreglo(tipo.getTipoBase().toString());
        return baseC != null ? baseC + "*" : null;
    }

    /**
     * Tipo C de un campo: un campo-estructura pasa a puntero; un campo-clase
     * ya viene con '*' desde tipoC(); un campo-arreglo (de Y?, 1 dimension,
     * tipo base primitivo) tambien se resuelve a puntero aqui, para que un
     * acceso "obj.arr[i]" pueda saber el tipo del ELEMENTO (ver el uso de
     * tipoCCampo() en tipoDeAccesoCampo()/traducirAccesoC()).
     */
    String tipoCCampo(Tipo tipo) {
        if (tipo.getCategoria() == Tipo.Categoria.ARREGLO) {
            return tipoCArreglo(tipo);
        }
        String base = tipoC(tipo);
        if (base != null && tipo.getCategoria() == Tipo.Categoria.ESTRUCTURA) {
            return base + "*";
        }
        return base;
    }

    /**
     * Tipo C de un tipo de Y? (retorno o parametro), aceptando ademas los
     * 2 casos que tipoC() por si sola no cubre: un parametro-arreglo
     * ('[] tipo nombre', pasado por referencia, sin tamaño fijo - se
     * traduce a un puntero) y una ESTRUCTURA que SI esta soportada
     * (tipoC ya la resuelve) pero cuyo propio cuerpo no lo esta (algun
     * campo arreglo invalido - ver GeneradorC.calcularEstructurasNoSoportadas()).
     */
    String tipoCTipoY(Tipo tipo, Set<String> estructurasNoSoportadas) {
        if (tipo.getCategoria() == Tipo.Categoria.ESTRUCTURA && estructurasNoSoportadas.contains(tipo.getNombreDefinido())) {
            return null;
        }
        String tc = tipoC(tipo);
        if (tc != null) {
            return tc;
        }
        if (tipo.getCategoria() == Tipo.Categoria.ARREGLO && tipo.getDimensiones() == 1) {
            String baseC = tipoCPrimitivoDeArreglo(tipo.getTipoBase().toString());
            return baseC != null ? baseC + "*" : null;
        }
        return null;
    }

    /** true si el campo/variable ARREGLO de Y? se puede declarar como un 'tipo nombre[N];' real de C. */
    boolean esArregloYSoportado(Simbolo s) {
        Tipo tipo = s.getTipo();
        if (tipo.getCategoria() != Tipo.Categoria.ARREGLO || tipo.getDimensiones() != 1) {
            return false;
        }
        if (tipoCPrimitivoDeArreglo(tipo.getTipoBase().toString()) == null) {
            return false;
        }
        String tamanio = s.getTamanioArreglo();
        return tamanio != null && ENTERO_LIT.matcher(tamanio).matches() && !tamanio.startsWith("-");
    }

    /** Tipo C de un operando: variable/temporal, literal, un indice "arr[i]", o un acceso "nodo.valor" (se camina segmento a segmento). */
    String tipoDeOperando(String texto, Map<String, String> tipoDe) {
        if (texto == null) {
            return null;
        }
        if (tipoDe.containsKey(texto)) {
            return tipoDe.get(texto);
        }
        if (LITERALES_VERDADERO.contains(texto) || LITERALES_FALSO.contains(texto)) {
            return "int";
        }
        if ("null".equals(texto)) {
            return null; // no hay forma de saber a que struct/clase apunta solo con 'null'
        }
        if (ENTERO_LIT.matcher(texto).matches()) {
            return "int";
        }
        if (DECIMAL_LIT.matcher(texto).matches()) {
            return "double";
        }
        if (CARACTER_LIT.matcher(texto).matches()) {
            return "char";
        }
        if (CADENA_LIT.matcher(texto).matches()) {
            return "char*";
        }
        if (texto.endsWith("]") && texto.indexOf('[') > 0) {
            // "arr[i]" o "obj.campoArreglo[i]": el tipo del ELEMENTO es el
            // tipo del arreglo (un puntero, ej. "int*") sin un nivel de
            // '*' - igual que desreferenciar un puntero en C. Se resuelve
            // primero el tipo de la parte ANTES del '[' (puede tener sus
            // propios '.'), no el texto completo.
            String base = texto.substring(0, texto.indexOf('['));
            String tipoBaseArreglo = base.indexOf('.') > 0 ? tipoDeAccesoCampo(base, tipoDe) : tipoDe.get(base);
            if (tipoBaseArreglo != null && tipoBaseArreglo.endsWith("*") && !tipoBaseArreglo.startsWith("struct ")) {
                return tipoBaseArreglo.substring(0, tipoBaseArreglo.length() - 1);
            }
            return null;
        }
        if (texto.indexOf('.') > 0 && !texto.startsWith("\"")) {
            return tipoDeAccesoCampo(texto, tipoDe);
        }
        return null; // identificador desconocido para el generador
    }

    private String tipoDeAccesoCampo(String texto, Map<String, String> tipoDe) {
        String[] segmentos = texto.split("\\.");
        String tipoActual = tipoDe.get(segmentos[0]);
        for (int s = 1; s < segmentos.length; s++) {
            Simbolo campo = campoDe(tipoActual, segmentos[s]);
            if (campo == null) {
                return null;
            }
            tipoActual = tipoCCampo(campo.getTipo());
        }
        return tipoActual;
    }

    /** Traduce "nodo.siguiente.valor" al C real: cada '.' se vuelve '->' apenas el tramo anterior es un puntero. */
    String traducirAccesoC(String texto, Map<String, String> tipoDe) {
        String[] segmentos = texto.split("\\.");
        StringBuilder salida = new StringBuilder(segmentos[0]);
        String tipoActual = tipoDe.get(segmentos[0]);
        for (int s = 1; s < segmentos.length; s++) {
            boolean esPuntero = tipoActual != null && tipoActual.endsWith("*");
            salida.append(esPuntero ? "->" : ".").append(segmentos[s]);
            Simbolo campo = campoDe(tipoActual, segmentos[s]);
            tipoActual = campo != null ? tipoCCampo(campo.getTipo()) : null;
        }
        return salida.toString();
    }

    /** 'struct Nodo'/'struct Nodo*' (estructura o clase) + nombre de campo -> el Simbolo de ese campo, o null si no aplica. */
    private Simbolo campoDe(String tipoCStruct, String nombreCampo) {
        String nombre = nombreTipoDesdeC(tipoCStruct);
        if (nombre == null) {
            return null;
        }
        SimboloEstructura estructura = global.buscarEstructura(nombre);
        if (estructura != null) {
            return estructura.buscarCampo(nombreCampo);
        }
        SimboloClase clase = global.buscarClase(nombre);
        return clase != null ? clase.buscarCampo(nombreCampo) : null;
    }

    /** 'struct Nombre' o 'struct Nombre*' -> 'Nombre'; null si el texto no tiene esa forma. */
    String nombreTipoDesdeC(String tipoCStruct) {
        if (tipoCStruct == null || !tipoCStruct.startsWith("struct ")) {
            return null;
        }
        return tipoCStruct.endsWith("*")
                ? tipoCStruct.substring("struct ".length(), tipoCStruct.length() - 1)
                : tipoCStruct.substring("struct ".length());
    }

    /** true si un tipo C puede envolverse en un 'char*' para 'concat'. */
    boolean esConvertibleACadena(String tipoC) {
        return "char*".equals(tipoC) || "int".equals(tipoC) || "double".equals(tipoC) || "char".equals(tipoC);
    }

    /** Envuelve un operando de 'concat' en el helper de conversion que le corresponda ('char*' se usa tal cual). */
    String wrapParaConcat(String texto, Map<String, String> tipoDe, Set<String> helpersLocales) {
        String tipo = tipoDeOperando(texto, tipoDe);
        String textoC = mapTexto(texto, tipoDe);
        if ("char*".equals(tipo)) {
            return textoC;
        }
        if ("int".equals(tipo)) {
            helpersLocales.add("entero");
            return "c3d_entero_a_cadena(" + textoC + ")";
        }
        if ("double".equals(tipo)) {
            helpersLocales.add("decimal");
            return "c3d_decimal_a_cadena(" + textoC + ")";
        }
        if ("char".equals(tipo)) {
            helpersLocales.add("caracter");
            return "c3d_caracter_a_cadena(" + textoC + ")";
        }
        return null;
    }

    /** Traduce un operando de cuarteta a su texto C: literales especiales, o un acceso "nodo.campo" via traducirAccesoC(). */
    String mapTexto(String texto, Map<String, String> tipoDe) {
        if (texto == null) {
            return "";
        }
        if (LITERALES_VERDADERO.contains(texto)) {
            return "1";
        }
        if (LITERALES_FALSO.contains(texto)) {
            return "0";
        }
        if ("null".equals(texto)) {
            return "NULL";
        }
        if (texto.indexOf('.') > 0 && !texto.startsWith("\"") && !DECIMAL_LIT.matcher(texto).matches()) {
            return traducirAccesoC(texto, tipoDe);
        }
        return texto;
    }

    String formatoPrintf(String tipoC) {
        if (tipoC == null) {
            return "%s";
        }
        switch (tipoC) {
            case "int": return "%d";
            case "double": return "%f";
            case "char": return "%c";
            default: return "%s"; // char* (cadena literal)
        }
    }

    String formatoScanf(String tipoC) {
        if (tipoC == null) {
            return "%d";
        }
        switch (tipoC) {
            case "int": return "%d";
            case "double": return "%lf";
            case "char": return " %c";
            default: return "%d";
        }
    }
}
