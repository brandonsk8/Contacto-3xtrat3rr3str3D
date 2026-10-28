package com.company.backend;

import com.company.ir.Cuarteta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pase B del backend RISC-V: traduce cada cuarteta a instrucciones RV32IMFD
 * (syscalls de RARS). Los tipos vienen de ModeloPrograma (el mismo modelo
 * que usa el backend de C con etiquetas).
 *
 * Esquema: cada variable/temporal vive en un slot fijo del marco de pila;
 * cada cuarteta carga sus operandos en t0-t2 / ft0-ft2, opera y guarda.
 * Registros de trabajo: t3 = direccion de un elemento de arreglo, t4/t5 =
 * calculo del indice, t6 = direcciones de pila fuera de los 12 bits.
 *
 * Arreglos: un bloque plano reservado con sbrk (syscall 9) + una variable
 * por dimension; a[i][j] -> base + ((i * d1) + j) * tamano, con tamano 4
 * (entero/booleano/caracter/cadena) u 8 (decimal).
 */
final class EmisorInstruccionesRiscV {

    private static final Set<String> COMPARACIONES = Set.of("<", ">", "<=", ">=", "==", "!=");

    private final ModeloPrograma modelo;

    private StringBuilder sb;
    private ModeloPrograma.Fn f;
    private MarcoFuncion marco;
    private AlmacenLiterales literales;
    private Map<String, ModeloPrograma.Fn> fns;
    private String etiquetaFin;

    // rutinas de apoyo (cadenas) que hay que anexar al .asm si algun programa las uso
    boolean usaItoa, usaCtoa, usaDtoa, usaConcat, usaStrcmp, usaLeerCad;

    // Traduce cada cuarteta a instrucciones RISC-V.
    EmisorInstruccionesRiscV(ModeloPrograma modelo) {
        this.modelo = modelo;
    }

    // Limpia las rutinas de apoyo pedidas.
    void reiniciarRutinas() {
        usaItoa = usaCtoa = usaDtoa = usaConcat = usaStrcmp = usaLeerCad = false;
    }

    /** @throws ModeloPrograma.NoSoportado si algo de la funcion no se puede traducir todavia. */
    String emitir(ModeloPrograma.Fn funcion, Map<String, ModeloPrograma.Fn> fns, MarcoFuncion marco,
                  String etiquetaFin, AlmacenLiterales literales) {
        this.sb = new StringBuilder();
        this.f = funcion;
        this.fns = fns;
        this.marco = marco;
        this.literales = literales;
        this.etiquetaFin = etiquetaFin;

        List<Cuarteta> cuerpo = funcion.cuerpo;
        List<String> pendientes = new ArrayList<>();
        int idx = 0;
        while (idx < cuerpo.size()) {
            Cuarteta c = cuerpo.get(idx);
            String op = c.getOperador();
            switch (op) {
                case "label":
                    sb.append(c.getResultado()).append(":\n");
                    break;
                case "goto":
                    sb.append("    j ").append(c.getResultado()).append("\n");
                    break;
                case "if_false":
                    cargarEntero("t0", c.getArg1());
                    sb.append("    beqz t0, ").append(c.getResultado()).append("\n");
                    break;
                case "if_true":
                    cargarEntero("t0", c.getArg1());
                    sb.append("    bnez t0, ").append(c.getResultado()).append("\n");
                    break;
                case "return":
                    emitirReturn(c);
                    break;
                case "print":
                    emitirPrint(c.getArg1(), "nl".equals(c.getResultado()));
                    break;
                case "read":
                    idx += emitirRead(cuerpo, idx) - 1;
                    break;
                case "param":
                    pendientes.add(c.getArg1());
                    break;
                case "call":
                    emitirCall(c, pendientes);
                    break;
                case "new_array":
                    emitirNewArray(c);
                    break;
                case "concat":
                    emitirConcat(c);
                    break;
                case "new_obj":
                    emitirNewObj(c);
                    break;
                case "len":
                    sb.append(AsmUtil.mem("lw", "t0", marco.offsetDe.get(c.getArg1() + "#d" + c.getArg2())));
                    guardarEntero("t0", c.getResultado());
                    break;
                case "classid":
                    cargarEntero("t0", c.getArg1());
                    memReg("lw", "t1", "t0", 0);
                    guardarEntero("t1", c.getResultado());
                    break;
                case "getf":
                    emitirLecturaCampo(c);
                    break;
                case "setf":
                    emitirEscrituraCampo(c);
                    break;
                case "=":
                    emitirAsignacion(c);
                    break;
                case "++":
                case "--":
                    emitirIncremento(c, "++".equals(op) ? 1 : -1);
                    break;
                default:
                    emitirOperacion(c);
            }
            idx++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Operandos
    // ------------------------------------------------------------------

    // True si el operando es un acceso a arreglo.
    private static boolean esElemento(String t) {
        return t != null && t.indexOf('[') > 0 && t.endsWith("]");
    }

    // Crea el error de construccion no soportada.
    private ModeloPrograma.NoSoportado no(String texto) {
        return new ModeloPrograma.NoSoportado("operando '" + texto + "' no soportado (campo, objeto o subarreglo)");
    }

    // True si el operando es de tipo decimal.
    private boolean esDoble(String texto) {
        return "double".equals(modelo.tipoOp(f, texto));
    }

    /** Deja en t3 la direccion del elemento "a[i][j]"; devuelve el tipo C del elemento. Usa t4/t5/t6. */
    private String direccionElemento(String texto) {
        String base = texto.substring(0, texto.indexOf('['));
        List<String> idx = modelo.indices(texto);
        Integer d = f.dims.get(base);
        if (d == null || d != idx.size()) {
            throw new ModeloPrograma.NoSoportado("acceso a '" + texto + "' (se necesita un indice por dimension)");
        }
        for (String i : idx) {
            if (esElemento(i)) {
                throw new ModeloPrograma.NoSoportado("indice '" + i + "' que es otro acceso a arreglo (usar una variable intermedia)");
            }
        }
        cargarEntero("t4", idx.get(0));
        for (int k = 1; k < idx.size(); k++) {
            sb.append(AsmUtil.mem("lw", "t5", marco.offsetDe.get(base + "#d" + k)));
            sb.append("    mul t4, t4, t5\n");
            cargarEntero("t5", idx.get(k));
            sb.append("    add t4, t4, t5\n");
        }
        String elem = f.tipo.get(base);
        sb.append("    slli t4, t4, ").append("double".equals(elem) ? 3 : 2).append("\n");
        sb.append(AsmUtil.mem("lw", "t5", marco.offsetDe.get(base)));
        sb.append("    add t3, t5, t4\n");
        return elem;
    }

    /** Carga un operando entero/caracter/booleano/cadena (puntero) en 'reg'. Un decimal se trunca. */
    private void cargarEntero(String reg, String texto) {
        if (texto == null) {
            throw no("null");
        }
        if (TiposC.ENTERO_LIT.matcher(texto).matches()) {
            sb.append("    li ").append(reg).append(", ").append(texto).append("\n");
        } else if (TiposC.LITERALES_VERDADERO.contains(texto)) {
            sb.append("    li ").append(reg).append(", 1\n");
        } else if (TiposC.LITERALES_FALSO.contains(texto) || "null".equals(texto)) {
            sb.append("    li ").append(reg).append(", 0\n");
        } else if (TiposC.CARACTER_LIT.matcher(texto).matches()) {
            sb.append("    li ").append(reg).append(", ").append(valorCaracter(texto)).append("\n");
        } else if (TiposC.CADENA_LIT.matcher(texto).matches()) {
            sb.append("    la ").append(reg).append(", ").append(literales.etiquetaCadena(texto)).append("\n");
        } else if (TiposC.DECIMAL_LIT.matcher(texto).matches()) {
            cargarDoble("ft3", texto);
            sb.append("    fcvt.w.d ").append(reg).append(", ft3, rtz\n");
        } else if (esElemento(texto)) {
            String elem = direccionElemento(texto);
            if ("double".equals(elem)) {
                sb.append("    fld ft3, 0(t3)\n    fcvt.w.d ").append(reg).append(", ft3, rtz\n");
            } else {
                sb.append("    lw ").append(reg).append(", 0(t3)\n");
            }
        } else if (marco.offsetDe.containsKey(texto) && !f.dims.containsKey(texto)) {
            if (marco.esFlotante(texto)) {
                sb.append(AsmUtil.mem("fld", "ft3", marco.offsetDe.get(texto)));
                sb.append("    fcvt.w.d ").append(reg).append(", ft3, rtz\n");
            } else {
                sb.append(AsmUtil.mem("lw", reg, marco.offsetDe.get(texto)));
            }
        } else {
            throw no(texto);
        }
    }

    /** Carga un operando como double en 'freg' (promueve enteros con fcvt.d.w). */
    private void cargarDoble(String freg, String texto) {
        if (texto == null) {
            throw no("null");
        }
        if (TiposC.DECIMAL_LIT.matcher(texto).matches()) {
            sb.append("    la t4, ").append(literales.etiquetaDecimal(texto)).append("\n");
            sb.append("    fld ").append(freg).append(", 0(t4)\n");
        } else if (esElemento(texto)) {
            String elem = direccionElemento(texto);
            if ("double".equals(elem)) {
                sb.append("    fld ").append(freg).append(", 0(t3)\n");
            } else {
                sb.append("    lw t4, 0(t3)\n    fcvt.d.w ").append(freg).append(", t4\n");
            }
        } else if (marco.offsetDe.containsKey(texto) && marco.esFlotante(texto) && !f.dims.containsKey(texto)) {
            sb.append(AsmUtil.mem("fld", freg, marco.offsetDe.get(texto)));
        } else {
            cargarEntero("t4", texto);
            sb.append("    fcvt.d.w ").append(freg).append(", t4\n");
        }
    }

    // Guarda un registro entero en su variable.
    private void guardarEntero(String reg, String destino) {
        if (esElemento(destino)) {
            String elem = direccionElemento(destino);
            if ("double".equals(elem)) {
                sb.append("    fcvt.d.w ft2, ").append(reg).append("\n    fsd ft2, 0(t3)\n");
            } else {
                sb.append("    sw ").append(reg).append(", 0(t3)\n");
            }
            return;
        }
        Integer off = marco.offsetDe.get(destino);
        if (off == null || marco.esFlotante(destino) || f.dims.containsKey(destino)) {
            throw no(destino);
        }
        sb.append(AsmUtil.mem("sw", reg, off));
    }

    // Guarda un registro flotante en su variable.
    private void guardarDoble(String freg, String destino) {
        if (esElemento(destino)) {
            String elem = direccionElemento(destino);
            if (!"double".equals(elem)) {
                throw new ModeloPrograma.NoSoportado("se guarda un decimal en el elemento '" + destino + "', que no es decimal");
            }
            sb.append("    fsd ").append(freg).append(", 0(t3)\n");
            return;
        }
        Integer off = marco.offsetDe.get(destino);
        if (off == null || !marco.esFlotante(destino)) {
            throw no(destino);
        }
        sb.append(AsmUtil.mem("fsd", freg, off));
    }

    /** 'A' -> 65, '\n' -> 10, etc. */
    private int valorCaracter(String lit) {
        String cuerpo = lit.substring(1, lit.length() - 1);
        if (cuerpo.length() == 1) {
            return cuerpo.charAt(0);
        }
        switch (cuerpo.charAt(1)) {
            case 'n': return 10;
            case 't': return 9;
            case 'r': return 13;
            case '0': return 0;
            case '\\': return 92;
            case '\'': return 39;
            case '"': return 34;
            default: return cuerpo.charAt(1);
        }
    }

    // ------------------------------------------------------------------
    // Objetos y cadenas
    // ------------------------------------------------------------------

    /** op reg, off(base) con desplazamientos grandes resueltos por t6. */
    private void memReg(String op, String reg, String base, int off) {
        if (AsmUtil.cabeEnInmediato(off)) {
            sb.append("    ").append(op).append(" ").append(reg).append(", ").append(off).append("(").append(base).append(")\n");
        } else {
            sb.append("    li t6, ").append(off).append("\n    add t6, t6, ").append(base).append("\n    ")
                    .append(op).append(" ").append(reg).append(", 0(t6)\n");
        }
    }

    // Resuelve el campo de un objeto desde su especificacion.
    private ModeloPrograma.Campo campoDeOperando(String spec) {
        int p = spec.indexOf('.');
        return modelo.campoDe(spec.substring(0, p), spec.substring(p + 1));
    }

    // Reserva un objeto en el heap (sbrk) y guarda su classId.
    private void emitirNewObj(Cuarteta c) {
        ModeloPrograma.Layout l = modelo.layoutDe(c.getArg1());
        sb.append("    li a0, ").append(8 * l.slots).append("\n    li a7, 9\n    ecall\n");
        if (l.id != 0) {
            sb.append("    li t0, ").append(l.id).append("\n    sw t0, 0(a0)\n");
        }
        guardarEntero("a0", c.getResultado());
    }

    // getf: lee un campo del objeto.
    private void emitirLecturaCampo(Cuarteta c) {
        ModeloPrograma.Campo k = campoDeOperando(c.getArg2());
        String dest = c.getResultado();
        cargarEntero("t0", c.getArg1());
        int off = 8 * k.slot;
        if (k.dims > 0) {
            memReg("lw", "t1", "t0", off);
            sb.append(AsmUtil.mem("sw", "t1", marco.offsetDe.get(dest)));
            for (int d = 0; d < k.dims; d++) {
                memReg("lw", "t1", "t0", 8 * (k.slot + 1 + d));
                sb.append(AsmUtil.mem("sw", "t1", marco.offsetDe.get(dest + "#d" + d)));
            }
        } else if ("double".equals(k.tipo)) {
            memReg("fld", "ft0", "t0", off);
            guardarDoble("ft0", dest);
        } else {
            memReg("lw", "t1", "t0", off);
            guardarEntero("t1", dest);
        }
    }

    // setf: escribe un campo del objeto.
    private void emitirEscrituraCampo(Cuarteta c) {
        ModeloPrograma.Campo k = campoDeOperando(c.getArg2());
        String valor = c.getResultado();
        cargarEntero("t0", c.getArg1());
        int off = 8 * k.slot;
        if (k.dims > 0) {
            boolean nulo = "null".equals(valor);
            if (!nulo && (!f.dims.containsKey(valor) || f.dims.get(valor) != k.dims)) {
                throw new ModeloPrograma.NoSoportado("se guarda en el campo arreglo '" + c.getArg2() + "' algo que no es un arreglo de sus dimensiones");
            }
            if (nulo) {
                memReg("sw", "zero", "t0", off);
            } else {
                sb.append(AsmUtil.mem("lw", "t1", marco.offsetDe.get(valor)));
                memReg("sw", "t1", "t0", off);
            }
            for (int d = 0; d < k.dims; d++) {
                if (nulo) {
                    memReg("sw", "zero", "t0", 8 * (k.slot + 1 + d));
                } else {
                    sb.append(AsmUtil.mem("lw", "t1", marco.offsetDe.get(valor + "#d" + d)));
                    memReg("sw", "t1", "t0", 8 * (k.slot + 1 + d));
                }
            }
        } else if ("double".equals(k.tipo)) {
            cargarDoble("ft0", valor);
            memReg("fsd", "ft0", "t0", off);
        } else {
            cargarEntero("t1", valor);
            memReg("sw", "t1", "t0", off);
        }
    }

    /** Deja en a0 el operando convertido a texto (puntero a cadena terminada en 0). */
    private void cargarComoCadena(String operando) {
        String t = modelo.tipoOp(f, operando);
        if ("char*".equals(t)) {
            cargarEntero("a0", operando);
        } else if ("int".equals(t)) {
            cargarEntero("a0", operando);
            sb.append("    jal ra, __itoa\n");
            usaItoa = true;
        } else if ("char".equals(t)) {
            cargarEntero("a0", operando);
            sb.append("    jal ra, __ctoa\n");
            usaCtoa = true;
        } else if ("double".equals(t)) {
            cargarDoble("fa0", operando);
            sb.append("    jal ra, __dtoa\n");
            usaDtoa = true;
        } else {
            throw new ModeloPrograma.NoSoportado("'concat' con un operando que no es cadena/numero/caracter");
        }
    }

    // Concatena dos cadenas con __concat.
    private void emitirConcat(Cuarteta c) {
        cargarComoCadena(c.getArg1());
        sb.append(AsmUtil.mem("sw", "a0", marco.offsetDe.get("#s0")));
        cargarComoCadena(c.getArg2());
        sb.append("    mv a1, a0\n");
        sb.append(AsmUtil.mem("lw", "a0", marco.offsetDe.get("#s0")));
        sb.append("    jal ra, __concat\n");
        usaConcat = true;
        guardarEntero("a0", c.getResultado());
    }

    // ------------------------------------------------------------------
    // Rutinas de apoyo (se anexan al final del .asm solo si se usaron)
    // ------------------------------------------------------------------

    // Rutinas runtime: itoa, ctoa, dtoa, concat, strcmp, leer cadena.
    String rutinasDeApoyo() {
        StringBuilder r = new StringBuilder();
        if (usaItoa || usaDtoa) {
            // __itoa: a0 = entero -> a0 = cadena nueva ("-123")
            r.append("__itoa:\n"
                    + "    mv t0, a0\n    li a0, 16\n    li a7, 9\n    ecall\n"
                    + "    addi t2, a0, 15\n    sb zero, 0(t2)\n    li t3, 0\n"
                    + "    bgez t0, __itoa_pos\n    li t3, 1\n    sub t0, zero, t0\n"
                    + "__itoa_pos:\n    li t4, 10\n    bnez t0, __itoa_lazo\n"
                    + "    addi t2, t2, -1\n    li t5, 48\n    sb t5, 0(t2)\n    j __itoa_signo\n"
                    + "__itoa_lazo:\n    beqz t0, __itoa_signo\n    rem t5, t0, t4\n    div t0, t0, t4\n"
                    + "    addi t5, t5, 48\n    addi t2, t2, -1\n    sb t5, 0(t2)\n    j __itoa_lazo\n"
                    + "__itoa_signo:\n    beqz t3, __itoa_fin\n    addi t2, t2, -1\n    li t5, 45\n    sb t5, 0(t2)\n"
                    + "__itoa_fin:\n    mv a0, t2\n    ret\n\n");
        }
        if (usaCtoa) {
            r.append("__ctoa:\n    mv t0, a0\n    li a0, 2\n    li a7, 9\n    ecall\n"
                    + "    sb t0, 0(a0)\n    sb zero, 1(a0)\n    ret\n\n");
        }
        if (usaConcat) {
            r.append("__concat:\n"
                    + "    bnez a0, __cc_a\n    la a0, __vacio\n"
                    + "__cc_a:\n    bnez a1, __cc_b\n    la a1, __vacio\n"
                    + "__cc_b:\n    mv t0, a0\n    mv t1, a1\n    mv t2, t0\n"
                    + "__cc_l1:\n    lb t3, 0(t2)\n    beqz t3, __cc_l1e\n    addi t2, t2, 1\n    j __cc_l1\n"
                    + "__cc_l1e:\n    sub t4, t2, t0\n    mv t2, t1\n"
                    + "__cc_l2:\n    lb t3, 0(t2)\n    beqz t3, __cc_l2e\n    addi t2, t2, 1\n    j __cc_l2\n"
                    + "__cc_l2e:\n    sub t5, t2, t1\n    add a0, t4, t5\n    addi a0, a0, 1\n    li a7, 9\n    ecall\n"
                    + "    mv t2, a0\n"
                    + "__cc_c1:\n    lb t3, 0(t0)\n    beqz t3, __cc_c2\n    sb t3, 0(t2)\n    addi t0, t0, 1\n    addi t2, t2, 1\n    j __cc_c1\n"
                    + "__cc_c2:\n    lb t3, 0(t1)\n    sb t3, 0(t2)\n    beqz t3, __cc_fin\n    addi t1, t1, 1\n    addi t2, t2, 1\n    j __cc_c2\n"
                    + "__cc_fin:\n    ret\n\n");
        }
        if (usaLeerCad) {
            // __leercad: lee una palabra de la entrada (como scanf("%255s") del compilador de referencia) -> a0 = cadena nueva.
            // Usa el servicio 8 de RARS (lee una linea): salta lineas en blanco y se queda con la primera palabra.
            r.append("__leercad:\n    li a0, 256\n    li a7, 9\n    ecall\n    mv t0, a0\n"
                    + "__lc_otra:\n    sb zero, 0(t0)\n    mv a0, t0\n    li a1, 255\n    li a7, 8\n    ecall\n    mv t1, t0\n"
                    + "__lc_ini:\n    lb t2, 0(t1)\n    beqz t2, __lc_finlinea\n    li t3, 33\n    bge t2, t3, __lc_corta\n"
                    + "    addi t1, t1, 1\n    j __lc_ini\n"
                    + "__lc_finlinea:\n    bne t1, t0, __lc_otra\n    sb zero, 0(t0)\n    mv a0, t0\n    ret\n"
                    + "__lc_corta:\n    mv t4, t0\n"
                    + "__lc_cp:\n    lb t2, 0(t1)\n    li t3, 33\n    blt t2, t3, __lc_fin\n    sb t2, 0(t4)\n    addi t1, t1, 1\n    addi t4, t4, 1\n    j __lc_cp\n"
                    + "__lc_fin:\n    sb zero, 0(t4)\n    mv a0, t0\n    ret\n\n");
        }
        if (usaStrcmp) {
            r.append("__strcmp:\n"
                    + "    bnez a0, __sc_a\n    la a0, __vacio\n"
                    + "__sc_a:\n    bnez a1, __sc_l\n    la a1, __vacio\n"
                    + "__sc_l:\n    lb t0, 0(a0)\n    lb t1, 0(a1)\n    bne t0, t1, __sc_ne\n    beqz t0, __sc_eq\n"
                    + "    addi a0, a0, 1\n    addi a1, a1, 1\n    j __sc_l\n"
                    + "__sc_ne:\n    sub a0, t0, t1\n    ret\n__sc_eq:\n    li a0, 0\n    ret\n\n");
        }
        if (usaDtoa) {
            // __dtoa: fa0 = decimal -> a0 = cadena nueva con 6 decimales (como "%f" de C)
            r.append("__dtoa:\n"
                    + "    addi sp, sp, -16\n    sw ra, 0(sp)\n"
                    + "    li t0, 0\n    fcvt.d.w ft0, zero\n    flt.d t1, fa0, ft0\n    beqz t1, __dt_pos\n"
                    + "    li t0, 1\n    fneg.d fa0, fa0\n"
                    + "__dt_pos:\n    fcvt.w.d t2, fa0, rtz\n    fcvt.d.w ft1, t2\n    fsub.d ft1, fa0, ft1\n"
                    + "    li t3, 1000000\n    fcvt.d.w ft2, t3\n    fmul.d ft1, ft1, ft2\n"
                    + "    li t4, 1\n    fcvt.d.w ft3, t4\n    li t4, 2\n    fcvt.d.w ft4, t4\n    fdiv.d ft3, ft3, ft4\n"
                    + "    fadd.d ft1, ft1, ft3\n    fcvt.w.d t4, ft1, rtz\n"
                    + "    blt t4, t3, __dt_ok\n    sub t4, t4, t3\n    addi t2, t2, 1\n"
                    + "__dt_ok:\n    li a0, 48\n    li a7, 9\n    ecall\n"
                    + "    addi t5, a0, 47\n    sb zero, 0(t5)\n    li t3, 6\n    li t1, 10\n"
                    + "__dt_fr:\n    rem a1, t4, t1\n    div t4, t4, t1\n    addi a1, a1, 48\n    addi t5, t5, -1\n    sb a1, 0(t5)\n"
                    + "    addi t3, t3, -1\n    bnez t3, __dt_fr\n"
                    + "    addi t5, t5, -1\n    li a1, 46\n    sb a1, 0(t5)\n"
                    + "    bnez t2, __dt_in\n    addi t5, t5, -1\n    li a1, 48\n    sb a1, 0(t5)\n    j __dt_sg\n"
                    + "__dt_in:\n    beqz t2, __dt_sg\n    rem a1, t2, t1\n    div t2, t2, t1\n    addi a1, a1, 48\n    addi t5, t5, -1\n    sb a1, 0(t5)\n    j __dt_in\n"
                    + "__dt_sg:\n    beqz t0, __dt_fin\n    addi t5, t5, -1\n    li a1, 45\n    sb a1, 0(t5)\n"
                    + "__dt_fin:\n    mv a0, t5\n    lw ra, 0(sp)\n    addi sp, sp, 16\n    ret\n\n");
        }
        return r.toString();
    }

    // ------------------------------------------------------------------
    // Cuartetas
    // ------------------------------------------------------------------

    // Retorna valor y vuelve al llamador.
    private void emitirReturn(Cuarteta c) {
        if (c.getArg1() != null) {
            if (f.tipoRet == null) {
                throw new ModeloPrograma.NoSoportado("'retornar' con valor en una funcion sin tipo de retorno");
            }
            if (f.dimsRet > 0) {
                String a = c.getArg1();
                if (!f.dims.containsKey(a) || f.dims.get(a) != f.dimsRet) {
                    throw new ModeloPrograma.NoSoportado("retorno de arreglo que no es una variable arreglo de las mismas dimensiones");
                }
                sb.append(AsmUtil.mem("lw", "a0", marco.offsetDe.get(a)));
                for (int k = 0; k < f.dimsRet; k++) {
                    sb.append(AsmUtil.mem("lw", "a" + (k + 1), marco.offsetDe.get(a + "#d" + k)));
                }
            } else if ("double".equals(f.tipoRet)) {
                cargarDoble("fa0", c.getArg1());
            } else {
                cargarEntero("a0", c.getArg1());
            }
        }
        sb.append("    j ").append(etiquetaFin).append("\n");
    }

    // Imprime un operando (ecall), con o sin salto de linea.
    private void emitirPrint(String operando, boolean salto) {
        String t = modelo.tipoOp(f, operando);
        if ("double".equals(t)) {
            // igual que "%f" de C (6 decimales): el RARS imprimiria otro formato
            cargarDoble("fa0", operando);
            sb.append("    jal ra, __dtoa\n    li a7, 4\n    ecall\n");
            usaDtoa = true;
        } else if ("char*".equals(t)) {
            cargarEntero("a0", operando);
            sb.append("    li a7, 4\n    ecall\n");
        } else if ("char".equals(t)) {
            cargarEntero("a0", operando);
            sb.append("    li a7, 11\n    ecall\n");
        } else if ("int".equals(t)) {
            cargarEntero("a0", operando);
            sb.append("    li a7, 1\n    ecall\n");
        } else {
            throw new ModeloPrograma.NoSoportado("'imprimir' de '" + operando + "' (arreglo completo, objeto o tipo desconocido)");
        }
        if (salto) {
            sb.append("    li a0, 10\n    li a7, 11\n    ecall\n");
        }
    }

    /** @return cuartetas consumidas (1 si lee directo a la variable, 2 si le sigue la asignacion). */
    private int emitirRead(List<Cuarteta> cuerpo, int idx) {
        Cuarteta c = cuerpo.get(idx);
        String destino = c.getResultado();
        int consumidas = 1;
        if (!f.tipo.containsKey(destino) && !esElemento(destino)) {
            if (idx + 1 < cuerpo.size() && "=".equals(cuerpo.get(idx + 1).getOperador())
                    && destino.equals(cuerpo.get(idx + 1).getArg1())) {
                destino = cuerpo.get(idx + 1).getResultado();
                consumidas = 2;
            } else {
                throw new ModeloPrograma.NoSoportado("'leer()' sin una asignacion inmediata a una variable");
            }
        }
        String t = modelo.tipoOp(f, destino);
        if ("double".equals(t)) {
            sb.append("    li a7, 7\n    ecall\n");
            guardarDoble("fa0", destino);
        } else if ("char".equals(t) || "int".equals(t)) {
            sb.append("    li a7, ").append("char".equals(t) ? 12 : 5).append("\n    ecall\n    mv t0, a0\n");
            guardarEntero("t0", destino);
        } else if ("char*".equals(t)) {
            sb.append("    jal ra, __leercad\n");
            usaLeerCad = true;
            guardarEntero("a0", destino);
        } else {
            throw new ModeloPrograma.NoSoportado("'leer()' hacia un arreglo completo/objeto no soportado");
        }
        return consumidas;
    }

    // Llamada: argumentos en registros, extras por __args.
    private void emitirCall(Cuarteta c, List<String> pendientes) {
        ModeloPrograma.Fn callee = modelo.llamado(c, fns);
        int n = Integer.parseInt(c.getArg2());
        if (n != callee.params.size() || n > pendientes.size()) {
            throw new ModeloPrograma.NoSoportado("llamada a '" + callee.nombre + "' con cantidad de argumentos inconsistente");
        }
        List<String> args = new ArrayList<>(pendientes.subList(pendientes.size() - n, pendientes.size()));
        for (int k = 0; k < n; k++) {
            pendientes.remove(pendientes.size() - 1);
        }
        int enteros = 0;
        int flotantes = 0;
        int extra = 0; // argumentos que no caben en registros: van por el area global __args (8 bytes c/u)
        for (int j = 0; j < n; j++) {
            String p = callee.params.get(j);
            String a = args.get(j);
            if (callee.dims.containsKey(p)) {
                int d = callee.dims.get(p);
                if (!f.dims.containsKey(a) || f.dims.get(a) != d) {
                    throw new ModeloPrograma.NoSoportado("argumento '" + a + "' no es un arreglo de las dimensiones que espera '" + p + "'");
                }
                for (int k = -1; k < d; k++) {
                    String fuente = k < 0 ? a : a + "#d" + k;
                    if (enteros < 8) {
                        sb.append(AsmUtil.mem("lw", "a" + enteros++, marco.offsetDe.get(fuente)));
                    } else {
                        sb.append(AsmUtil.mem("lw", "t0", marco.offsetDe.get(fuente)));
                        sb.append("    la t6, __args\n    sw t0, ").append(8 * extra++).append("(t6)\n");
                    }
                }
            } else if ("double".equals(callee.tipo.get(p))) {
                if (flotantes < 8) {
                    cargarDoble("fa" + flotantes++, a);
                } else {
                    cargarDoble("ft0", a);
                    sb.append("    la t6, __args\n    fsd ft0, ").append(8 * extra++).append("(t6)\n");
                }
            } else {
                if (enteros < 8) {
                    cargarEntero("a" + enteros++, a);
                } else {
                    cargarEntero("t0", a);
                    sb.append("    la t6, __args\n    sw t0, ").append(8 * extra++).append("(t6)\n");
                }
            }
        }
        sb.append("    jal ra, ").append(callee.nombre).append("\n");
        if (c.getResultado() != null) {
            String dest = c.getResultado();
            if (callee.dimsRet > 0) {
                sb.append(AsmUtil.mem("sw", "a0", marco.offsetDe.get(dest)));
                for (int k = 0; k < callee.dimsRet; k++) {
                    sb.append(AsmUtil.mem("sw", "a" + (k + 1), marco.offsetDe.get(dest + "#d" + k)));
                }
            } else if ("double".equals(callee.tipoRet)) {
                guardarDoble("fa0", dest);
            } else {
                guardarEntero("a0", dest);
            }
        }
    }

    // Reserva un arreglo y guarda sus dimensiones.
    private void emitirNewArray(Cuarteta c) {
        String v = c.getResultado();
        String[] tams = c.getArg2().split(",");
        Integer d = f.dims.get(v);
        if (d == null || d != tams.length) {
            throw new ModeloPrograma.NoSoportado("arreglo '" + v + "': la cantidad de tamanos no coincide con sus dimensiones");
        }
        for (int k = 0; k < tams.length; k++) {
            cargarEntero("t0", tams[k].trim());
            sb.append(AsmUtil.mem("sw", "t0", marco.offsetDe.get(v + "#d" + k)));
        }
        sb.append("    li t1, 1\n");
        for (int k = 0; k < tams.length; k++) {
            sb.append(AsmUtil.mem("lw", "t0", marco.offsetDe.get(v + "#d" + k)));
            sb.append("    mul t1, t1, t0\n");
        }
        sb.append("    slli a0, t1, ").append("double".equals(f.tipo.get(v)) ? 3 : 2).append("\n");
        sb.append("    li a7, 9\n    ecall\n");
        sb.append(AsmUtil.mem("sw", "a0", marco.offsetDe.get(v)));
    }

    // Asignacion simple entre operandos.
    private void emitirAsignacion(Cuarteta c) {
        String destino = c.getResultado();
        String origen = c.getArg1();
        if (!esElemento(destino) && f.dims.containsKey(destino)) {
            if ("null".equals(origen)) {
                sb.append(AsmUtil.mem("sw", "zero", marco.offsetDe.get(destino)));
                return;
            }
            if (!f.dims.containsKey(origen) || !f.dims.get(origen).equals(f.dims.get(destino))) {
                throw new ModeloPrograma.NoSoportado("asignacion de arreglo '" + destino + "' desde algo que no es un arreglo de las mismas dimensiones");
            }
            sb.append(AsmUtil.mem("lw", "t0", marco.offsetDe.get(origen)));
            sb.append(AsmUtil.mem("sw", "t0", marco.offsetDe.get(destino)));
            for (int k = 0; k < f.dims.get(destino); k++) {
                sb.append(AsmUtil.mem("lw", "t0", marco.offsetDe.get(origen + "#d" + k)));
                sb.append(AsmUtil.mem("sw", "t0", marco.offsetDe.get(destino + "#d" + k)));
            }
            return;
        }
        if (!esElemento(origen) && f.dims.containsKey(origen)) {
            throw new ModeloPrograma.NoSoportado("se asigna un arreglo completo a '" + destino + "', que no es un arreglo");
        }
        if (esDoble(destino)) {
            cargarDoble("ft0", origen);
            guardarDoble("ft0", destino);
        } else {
            cargarEntero("t0", origen);
            guardarEntero("t0", destino);
        }
    }

    // Suma o resta 1 a una variable.
    private void emitirIncremento(Cuarteta c, int delta) {
        String v = c.getResultado();
        if (esDoble(v)) {
            cargarDoble("ft0", v);
            sb.append("    li t4, ").append(delta).append("\n    fcvt.d.w ft1, t4\n    fadd.d ft0, ft0, ft1\n");
            guardarDoble("ft0", v);
        } else {
            cargarEntero("t0", v);
            sb.append("    addi t0, t0, ").append(delta).append("\n");
            guardarEntero("t0", v);
        }
    }

    // Operaciones aritmeticas, relacionales y logicas.
    private void emitirOperacion(Cuarteta c) {
        String op = c.getOperador();
        String res = c.getResultado();

        if ("-u".equals(op) || "!".equals(op)) {
            if ("!".equals(op)) {
                cargarEntero("t0", c.getArg1());
                sb.append("    seqz t0, t0\n");
                guardarEntero("t0", res);
            } else if (esDoble(c.getArg1())) {
                cargarDoble("ft0", c.getArg1());
                sb.append("    fneg.d ft0, ft0\n");
                guardarDoble("ft0", res);
            } else {
                cargarEntero("t0", c.getArg1());
                sb.append("    sub t0, zero, t0\n");
                guardarEntero("t0", res);
            }
            return;
        }

        boolean algunDoble = esDoble(c.getArg1()) || esDoble(c.getArg2());

        if (COMPARACIONES.contains(op)) {
            if ("char*".equals(modelo.tipoOp(f, c.getArg1())) && "char*".equals(modelo.tipoOp(f, c.getArg2()))) {
                if (!"==".equals(op) && !"!=".equals(op)) {
                    throw new ModeloPrograma.NoSoportado("operador '" + op + "' sobre cadenas");
                }
                cargarEntero("a0", c.getArg1());
                sb.append(AsmUtil.mem("sw", "a0", marco.offsetDe.get("#s0")));
                cargarEntero("a1", c.getArg2());
                sb.append(AsmUtil.mem("lw", "a0", marco.offsetDe.get("#s0")));
                sb.append("    jal ra, __strcmp\n");
                sb.append("==".equals(op) ? "    seqz t0, a0\n" : "    snez t0, a0\n");
                usaStrcmp = true;
                guardarEntero("t0", res);
                return;
            }
            if (algunDoble) {
                cargarDoble("ft0", c.getArg1());
                cargarDoble("ft1", c.getArg2());
                switch (op) {
                    case "<": sb.append("    flt.d t0, ft0, ft1\n"); break;
                    case ">": sb.append("    flt.d t0, ft1, ft0\n"); break;
                    case "<=": sb.append("    fle.d t0, ft0, ft1\n"); break;
                    case ">=": sb.append("    fle.d t0, ft1, ft0\n"); break;
                    case "==": sb.append("    feq.d t0, ft0, ft1\n"); break;
                    default: sb.append("    feq.d t0, ft0, ft1\n    xori t0, t0, 1\n");
                }
            } else {
                cargarEntero("t0", c.getArg1());
                cargarEntero("t1", c.getArg2());
                switch (op) {
                    case "<": sb.append("    slt t0, t0, t1\n"); break;
                    case ">": sb.append("    slt t0, t1, t0\n"); break;
                    case "<=": sb.append("    slt t0, t1, t0\n    xori t0, t0, 1\n"); break;
                    case ">=": sb.append("    slt t0, t0, t1\n    xori t0, t0, 1\n"); break;
                    case "==": sb.append("    sub t0, t0, t1\n    seqz t0, t0\n"); break;
                    default: sb.append("    sub t0, t0, t1\n    snez t0, t0\n");
                }
            }
            guardarEntero("t0", res);
            return;
        }

        if ("&&".equals(op) || "||".equals(op)) {
            cargarEntero("t0", c.getArg1());
            cargarEntero("t1", c.getArg2());
            sb.append("    ").append("&&".equals(op) ? "and" : "or").append(" t0, t0, t1\n");
            guardarEntero("t0", res);
            return;
        }

        // Aritmetica: + - * / %
        if (marco.esFlotante(res)) {
            if ("%".equals(op)) {
                throw new ModeloPrograma.NoSoportado("'%' sobre valores decimales");
            }
            cargarDoble("ft0", c.getArg1());
            cargarDoble("ft1", c.getArg2());
            String ins;
            switch (op) {
                case "+": ins = "fadd.d"; break;
                case "-": ins = "fsub.d"; break;
                case "*": ins = "fmul.d"; break;
                case "/": ins = "fdiv.d"; break;
                default: throw new ModeloPrograma.NoSoportado("operador '" + op + "'");
            }
            sb.append("    ").append(ins).append(" ft0, ft0, ft1\n");
            guardarDoble("ft0", res);
        } else {
            cargarEntero("t0", c.getArg1());
            cargarEntero("t1", c.getArg2());
            String ins;
            switch (op) {
                case "+": ins = "add"; break;
                case "-": ins = "sub"; break;
                case "*": ins = "mul"; break;
                case "/": ins = "div"; break;
                case "%": ins = "rem"; break;
                default: throw new ModeloPrograma.NoSoportado("operador '" + op + "'");
            }
            sb.append("    ").append(ins).append(" t0, t0, t1\n");
            guardarEntero("t0", res);
        }
    }
}
