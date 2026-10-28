package com.company.ir;

import java.util.List;

/**
 * Texto legible del codigo de tres direcciones (C3D): una instruccion por
 * linea, con las etiquetas pegadas a la izquierda. Es el mismo codigo
 * intermedio (cuartetas) que consumen los backends de C y de RISC-V.
 */
public final class FormateadorC3D {

    private FormateadorC3D() {
    }

    // Convierte las cuartetas a texto legible (.c3d).
    public static String formatear(String nombrePrograma, List<Cuarteta> cuartetas) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Codigo de tres direcciones de ").append(nombrePrograma).append("\n");
        for (Cuarteta c : cuartetas) {
            String op = c.getOperador();
            String a = c.getArg1();
            String b = c.getArg2();
            String r = c.getResultado();
            switch (op) {
                case "label":
                    sb.append(r).append(":\n");
                    break;
                case "goto":
                    sb.append("    goto ").append(r).append("\n");
                    break;
                case "if_false":
                case "if_true":
                    sb.append("    ").append(op).append(' ').append(a).append(" goto ").append(r).append("\n");
                    break;
                case "return":
                    sb.append("    return").append(a != null ? " " + a : "").append("\n");
                    break;
                case "print":
                    sb.append("    ").append("nl".equals(r) ? "println " : "print ").append(a).append("\n");
                    break;
                case "read":
                    sb.append("    ").append(r).append(" = read\n");
                    break;
                case "param":
                    sb.append("    param ").append(a).append("\n");
                    break;
                case "call":
                    sb.append("    ").append(r != null ? r + " = " : "").append("call ").append(a).append(", ").append(b).append("\n");
                    break;
                case "new":
                    sb.append("    ").append(r).append(" = new ").append(a.replace('#', ' ').trim()).append(", ").append(b).append("\n");
                    break;
                case "new_array":
                    sb.append("    ").append(r).append(" = new_array ").append(a).append("[").append(b).append("]\n");
                    break;
                case "decl":
                    sb.append("    local ").append(r).append(" : ").append(a).append("\n");
                    break;
                case "=":
                    sb.append("    ").append(r).append(" = ").append(a).append("\n");
                    break;
                case "++":
                case "--":
                    sb.append("    ").append(r).append(" = ").append(a).append("++".equals(op) ? " + 1" : " - 1").append("\n");
                    break;
                case "-u":
                    sb.append("    ").append(r).append(" = - ").append(a).append("\n");
                    break;
                case "!":
                    sb.append("    ").append(r).append(" = ! ").append(a).append("\n");
                    break;
                case "concat":
                    sb.append("    ").append(r).append(" = concat ").append(a).append(", ").append(b).append("\n");
                    break;
                default:
                    sb.append("    ").append(r).append(" = ").append(a).append(' ').append(op).append(' ').append(b).append("\n");
            }
        }
        return sb.toString();
    }
}
