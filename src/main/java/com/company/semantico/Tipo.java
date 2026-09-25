package com.company.semantico;

import java.util.Objects;

/**
 * Representa un tipo de dato semantico, unificado para los 3 lenguajes
 * del proyecto. Esto es necesario porque Pig Latin importa estructuras
 * (.y) y clases (.z), asi que sus variables terminan teniendo un tipo
 * "ESTRUCTURA" o "CLASE" definido en otro archivo - no alcanza con un
 * enum de tipos primitivos separado por lenguaje.
 *
 * Los nombres de los tipos primitivos se normalizan a un solo vocabulario
 * interno (ENTERO, DECIMAL, ...) sin importar si el programa fuente los
 * escribio como 'int'/'numerus'/'entero', 'double'/'decimalis'/'flotante',
 * etc. Esa traduccion (palabra del lenguaje fuente -> Tipo) la hace cada
 * Visitor mas adelante, no esta clase.
 */
public final class Tipo {

    public enum Categoria {
        ENTERO,
        DECIMAL,
        CADENA,
        CARACTER,
        BOOLEANO,
        VOID,
        ESTRUCTURA,   // definido en un archivo .y (Y?)
        CLASE,        // definido en un archivo .z (Zetariano)
        ARREGLO,
        ERROR,        // tipo "comodin" para cuando algo ya fallo antes (ver mas abajo)
        NULO          // el tipo del literal 'null' (Zetariano) - ver mas abajo
    }

    // Instancias unicas para los tipos primitivos (no necesitan datos extra).
    public static final Tipo ENTERO = new Tipo(Categoria.ENTERO, null, null, 0);
    public static final Tipo DECIMAL = new Tipo(Categoria.DECIMAL, null, null, 0);
    public static final Tipo CADENA = new Tipo(Categoria.CADENA, null, null, 0);
    public static final Tipo CARACTER = new Tipo(Categoria.CARACTER, null, null, 0);
    public static final Tipo BOOLEANO = new Tipo(Categoria.BOOLEANO, null, null, 0);
    public static final Tipo VOID = new Tipo(Categoria.VOID, null, null, 0);

    /**
     * Tipo especial que devuelve un Visitor cuando ya reporto un error
     * semantico y no puede determinar un tipo real (ej. una variable no
     * declarada, una funcion inexistente). Nunca es compatible con nada
     * -ni siquiera consigo mismo, a proposito- para que el llamador no
     * intente seguir generando cuartetas con el, pero tampoco dispara un
     * segundo error "en cascada": el codigo que lo usa simplemente debe
     * revisar Operando.esError() ANTES de comparar tipos, no despues.
     */
    public static final Tipo ERROR = new Tipo(Categoria.ERROR, null, null, 0);

    /**
     * El tipo del literal 'null' de Zetariano. A diferencia de ERROR, SI
     * es compatible con algo: con cualquier tipo por referencia
     * (ESTRUCTURA, CLASE o ARREGLO), igual que 'null' en Java. Ver el
     * caso especial en esCompatibleCon().
     */
    public static final Tipo NULO = new Tipo(Categoria.NULO, null, null, 0);

    private final Categoria categoria;
    private final String nombreDefinido; // nombre de la estructura/clase, solo si categoria es ESTRUCTURA o CLASE
    private final Tipo tipoBase;         // tipo de los elementos, solo si categoria es ARREGLO
    private final int dimensiones;       // cantidad de '[]', solo si categoria es ARREGLO

    private Tipo(Categoria categoria, String nombreDefinido, Tipo tipoBase, int dimensiones) {
        this.categoria = categoria;
        this.nombreDefinido = nombreDefinido;
        this.tipoBase = tipoBase;
        this.dimensiones = dimensiones;
    }

    public static Tipo estructura(String nombre) {
        return new Tipo(Categoria.ESTRUCTURA, nombre, null, 0);
    }

    public static Tipo clase(String nombre) {
        return new Tipo(Categoria.CLASE, nombre, null, 0);
    }

    public static Tipo arreglo(Tipo tipoBase, int dimensiones) {
        return new Tipo(Categoria.ARREGLO, null, tipoBase, dimensiones);
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public String getNombreDefinido() {
        return nombreDefinido;
    }

    public Tipo getTipoBase() {
        return tipoBase;
    }

    public int getDimensiones() {
        return dimensiones;
    }

    public boolean esNumerico() {
        return categoria == Categoria.ENTERO || categoria == Categoria.DECIMAL;
    }

    /**
     * Compatibilidad "amplia" para asignaciones y operaciones aritmeticas:
     * mismo tipo exacto, o entero -> decimal (promocion implicita, igual
     * que en Java/C). No cubre conversiones de/a cadena, booleano,
     * estructura o clase: esas deben coincidir exactamente.
     */
    public boolean esCompatibleCon(Tipo otro) {
        if (otro == null || this.categoria == Categoria.ERROR || otro.categoria == Categoria.ERROR) {
            return false;
        }
        if (this.equals(otro)) {
            return true;
        }
        if (otro.categoria == Categoria.NULO) {
            return this.categoria == Categoria.ESTRUCTURA || this.categoria == Categoria.CLASE || this.categoria == Categoria.ARREGLO;
        }
        return this.categoria == Categoria.DECIMAL && otro.categoria == Categoria.ENTERO;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tipo)) return false;
        Tipo tipo = (Tipo) o;
        return dimensiones == tipo.dimensiones
                && categoria == tipo.categoria
                && Objects.equals(nombreDefinido, tipo.nombreDefinido)
                && Objects.equals(tipoBase, tipo.tipoBase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(categoria, nombreDefinido, tipoBase, dimensiones);
    }

    @Override
    public String toString() {
        switch (categoria) {
            case ESTRUCTURA:
            case CLASE:
                return nombreDefinido;
            case ARREGLO:
                StringBuilder sb = new StringBuilder(tipoBase.toString());
                for (int i = 0; i < dimensiones; i++) {
                    sb.append("[]");
                }
                return sb.toString();
            default:
                return categoria.name().toLowerCase();
        }
    }
}
