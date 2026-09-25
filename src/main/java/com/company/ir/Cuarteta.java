package com.company.ir;

/**
 * Representa una cuarteta de codigo de tres direcciones (C3D):
 *   (operador, argumento1, argumento2, resultado)
 *
 * Ejemplos de uso una vez tengamos las gramaticas conectadas:
 *   t0 = a + b        -> new Cuarteta("+", "a", "b", "t0")
 *   if_false t2 goto L1 -> new Cuarteta("if_false", "t2", null, "L1")
 *   goto L0            -> new Cuarteta("goto", null, null, "L0")
 *
 * Esta clase es intencionalmente independiente de Y?, Zetariano y Pig Latin:
 * los tres lenguajes generan instancias de Cuarteta, y el generador de
 * bytecode/C solo conoce este modelo, no los AST de origen.
 */
public class Cuarteta {

    private final String operador;
    private final String arg1;
    private final String arg2;
    private final String resultado;

    public Cuarteta(String operador, String arg1, String arg2, String resultado) {
        this.operador = operador;
        this.arg1 = arg1;
        this.arg2 = arg2;
        this.resultado = resultado;
    }

    public String getOperador() {
        return operador;
    }

    public String getArg1() {
        return arg1;
    }

    public String getArg2() {
        return arg2;
    }

    public String getResultado() {
        return resultado;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(operador).append(", ");
        sb.append(arg1 != null ? arg1 : "_").append(", ");
        sb.append(arg2 != null ? arg2 : "_").append(", ");
        sb.append(resultado != null ? resultado : "_").append(")");
        return sb.toString();
    }
}
