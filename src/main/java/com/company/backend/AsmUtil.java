package com.company.backend;

/**
 * Utilidades de texto ensamblador. Los inmediatos de lw/sw/fld/fsd/addi son
 * de 12 bits con signo (max 2047): un marco con muchos temporales los
 * excede, asi que se arma la direccion con 'li' + 'add' (registro t6).
 */
final class AsmUtil {

    private AsmUtil() {
    }

    static boolean cabeEnInmediato(int valor) {
        return valor >= -2048 && valor <= 2040;
    }

    /** op reg, off(sp) -- op = lw/sw/fld/fsd. */
    static String mem(String op, String reg, int off) {
        if (cabeEnInmediato(off)) {
            return "    " + op + " " + reg + ", " + off + "(sp)\n";
        }
        return "    li t6, " + off + "\n    add t6, t6, sp\n    " + op + " " + reg + ", 0(t6)\n";
    }

    /** sp = sp + delta (delta puede ser negativo). */
    static String ajustarSp(int delta) {
        if (cabeEnInmediato(delta)) {
            return "    addi sp, sp, " + delta + "\n";
        }
        return "    li t6, " + delta + "\n    add sp, sp, t6\n";
    }
}
