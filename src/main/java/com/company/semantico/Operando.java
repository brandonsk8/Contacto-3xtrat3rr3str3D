package com.company.semantico;

/**
 * Lo que devuelve visitar una expresion (o un acceso usado como
 * destino de asignacion): su Tipo, y el "texto" con el que ese valor
 * aparece en una Cuarteta (el nombre de una variable, un temporal como
 * "t3", un literal ya formateado, o algo como "arreglo[t1].campo").
 *
 * Sirve para las dos cosas a la vez -validar tipos Y generar cuartetas-
 * sin recorrer el arbol dos veces: el metodo que arma una suma, por
 * ejemplo, revisa Operando.getTipo() de sus dos operandos para saber si
 * la suma es valida, y usa Operando.getTexto() de cada uno para escribir
 * la Cuarteta del resultado.
 *
 * Hay dos "no es un valor normal" posibles, y son distintos a proposito:
 *   - error(): ya se reporto un error semantico sobre esto (variable no
 *     declarada, tipos incompatibles, etc.). El que lo recibe NO debe
 *     reportar un segundo error por su culpa (evita el efecto cascada),
 *     y no debe generar una Cuarteta usandolo.
 *   - sinValor(): no hubo ningun error, pero esto no produce un valor
 *     utilizable (ej. llamar a una funcion 'void', o un inicializador de
 *     lista '{...}' cuyos elementos ya se validaron por separado). El
 *     que lo recibe tampoco debe comparar tipos contra el, pero es un
 *     caso normal, no un error.
 */
public final class Operando {

    private static final Operando ERROR = new Operando(Tipo.ERROR, null, true);
    private static final Operando SIN_VALOR = new Operando(null, null, false);

    private final Tipo tipo;
    private final String texto;
    private final boolean error;

    public Operando(Tipo tipo, String texto) {
        this(tipo, texto, false);
    }

    private Operando(Tipo tipo, String texto, boolean error) {
        this.tipo = tipo;
        this.texto = texto;
        this.error = error;
    }

    public static Operando error() {
        return ERROR;
    }

    public static Operando sinValor() {
        return SIN_VALOR;
    }

    public boolean esError() {
        return error;
    }

    public boolean esSinValor() {
        return this == SIN_VALOR;
    }

    /** true si esto es un valor real, usable en una comparacion de tipos o en una Cuarteta. */
    public boolean esValor() {
        return !error && this != SIN_VALOR;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public String getTexto() {
        return texto;
    }
}
