package com.company.ir;
/**
 * Una cuarteta de codigo de tres direcciones (C3D): (operador, arg1,
 * arg2, resultado). Ej: "t0 = a + b" -> new Cuarteta("+", "a", "b", "t0").
 *
 * Independiente de Y?/Zetariano/Pig Latin: los 3 lenguajes generan
 * instancias de Cuarteta, y el backend solo conoce este modelo, no los
 * AST de origen.
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
