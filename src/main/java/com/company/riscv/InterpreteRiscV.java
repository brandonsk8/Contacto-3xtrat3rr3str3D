package com.company.riscv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ensamblador + simulador de RISC-V (RV32IMFD, el subconjunto que usan los
 * programas generados y los servicios de sistema de RARS) hecho dentro del
 * propio proyecto: asi el codigo generado se EJECUTA desde la aplicacion, sin
 * ninguna herramienta externa.
 *
 * Funcionamiento: {@link #cargar(String)} convierte el texto ensamblador en
 * instrucciones decodificadas (etiquetas resueltas, seudoinstrucciones
 * expandidas) y arma el segmento .data; {@link #ejecutar(long)} las simula
 * hasta el servicio de salida (ecall 10), un error de ejecucion o el limite
 * de instrucciones. Entrada y salida se enchufan con {@link Salida} y
 * {@link Entrada}, de modo que sirve igual para la GUI que para pruebas.
 *
 * Memoria: segmento de datos en 0x10010000, monton (sbrk) desde 0x10040000 y
 * pila desde 0x7fffeffc, igual que RARS (configuracion por defecto).
 */
public final class InterpreteRiscV {

    /** Destino del texto que imprime el programa. */
    public interface Salida {
        void escribir(String texto);
    }

    /** Origen de las lineas que lee el programa; null = fin de la entrada. */
    public interface Entrada {
        String leerLinea();
    }

    /** Error de ensamblado o de ejecucion, con la linea del .asm si se conoce. */
    public static final class ErrorRiscV extends RuntimeException {
        public final int linea;

        ErrorRiscV(String mensaje, int linea) {
            super(mensaje, null, false, false);
            this.linea = linea;
        }
    }

    public static final class Resultado {
        public final boolean terminoBien;
        public final String error;
        public final long instrucciones;
        public final int codigoSalida;

        Resultado(boolean terminoBien, String error, long instrucciones, int codigoSalida) {
            this.terminoBien = terminoBien;
            this.error = error;
            this.instrucciones = instrucciones;
            this.codigoSalida = codigoSalida;
        }
    }

    // ------------------------------------------------------------------
    // Constantes de memoria
    // ------------------------------------------------------------------

    private static final int BASE_TEXTO = 0x00400000;
    private static final int BASE_DATOS = 0x10010000;
    private static final int BASE_MONTON = 0x10040000;
    private static final int TOPE_PILA = 0x7fffeffc;

    // ------------------------------------------------------------------
    // Codigos de operacion
    // ------------------------------------------------------------------

    private static final int ADD = 1, SUB = 2, MUL = 3, DIV = 4, REM = 5, AND = 6, OR = 7, XOR = 8, SLL = 9, SRL = 10,
            SRA = 11, SLT = 12, SLTU = 13, DIVU = 14, REMU = 15, MULH = 16,
            ADDI = 20, ANDI = 21, ORI = 22, XORI = 23, SLLI = 24, SRLI = 25, SRAI = 26, SLTI = 27, SLTIU = 28,
            LW = 30, LB = 31, LBU = 32, LH = 33, LHU = 34, SW = 35, SB = 36, SH = 37,
            BEQ = 40, BNE = 41, BLT = 42, BGE = 43, BLTU = 44, BGEU = 45,
            JAL = 50, JALR = 51, LUI = 52, AUIPC = 53, ECALL = 54,
            FLD = 60, FSD = 61, FADD = 62, FSUB = 63, FMUL = 64, FDIV = 65, FSQRT = 66, FNEG = 67, FABS = 68, FMVD = 69,
            FCVTWD = 70, FCVTDW = 71, FEQ = 72, FLT = 73, FLE = 74, FMIN = 75, FMAX = 76;

    private static final Map<String, Integer> TIPO_R = new HashMap<>();
    private static final Map<String, Integer> TIPO_I = new HashMap<>();
    private static final Map<String, Integer> CARGAS = new HashMap<>();
    private static final Map<String, Integer> GUARDAS = new HashMap<>();
    private static final Map<String, Integer> RAMAS = new HashMap<>();
    private static final Map<String, Integer> FP3 = new HashMap<>();
    private static final Map<String, Integer> FP2 = new HashMap<>();
    private static final Map<String, Integer> FPCMP = new HashMap<>();

    static {
        String[] r = {"add", "sub", "mul", "div", "rem", "and", "or", "xor", "sll", "srl", "sra", "slt", "sltu", "divu", "remu", "mulh"};
        int[] rc = {ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU, DIVU, REMU, MULH};
        for (int i = 0; i < r.length; i++) {
            TIPO_R.put(r[i], rc[i]);
        }
        String[] ii = {"addi", "andi", "ori", "xori", "slli", "srli", "srai", "slti", "sltiu"};
        int[] ic = {ADDI, ANDI, ORI, XORI, SLLI, SRLI, SRAI, SLTI, SLTIU};
        for (int i = 0; i < ii.length; i++) {
            TIPO_I.put(ii[i], ic[i]);
        }
        CARGAS.put("lw", LW);
        CARGAS.put("lb", LB);
        CARGAS.put("lbu", LBU);
        CARGAS.put("lh", LH);
        CARGAS.put("lhu", LHU);
        GUARDAS.put("sw", SW);
        GUARDAS.put("sb", SB);
        GUARDAS.put("sh", SH);
        RAMAS.put("beq", BEQ);
        RAMAS.put("bne", BNE);
        RAMAS.put("blt", BLT);
        RAMAS.put("bge", BGE);
        RAMAS.put("bltu", BLTU);
        RAMAS.put("bgeu", BGEU);
        FP3.put("fadd.d", FADD);
        FP3.put("fsub.d", FSUB);
        FP3.put("fmul.d", FMUL);
        FP3.put("fdiv.d", FDIV);
        FP3.put("fmin.d", FMIN);
        FP3.put("fmax.d", FMAX);
        FP2.put("fsqrt.d", FSQRT);
        FP2.put("fneg.d", FNEG);
        FP2.put("fabs.d", FABS);
        FP2.put("fmv.d", FMVD);
        FPCMP.put("feq.d", FEQ);
        FPCMP.put("flt.d", FLT);
        FPCMP.put("fle.d", FLE);
    }

    private static final Map<String, Integer> REGISTROS = new HashMap<>();
    private static final Map<String, Integer> REGISTROS_F = new HashMap<>();

    static {
        String[] abi = {"zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7",
                "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};
        for (int i = 0; i < 32; i++) {
            REGISTROS.put(abi[i], i);
            REGISTROS.put("x" + i, i);
        }
        REGISTROS.put("fp", 8);
        String[] fabi = {"ft0", "ft1", "ft2", "ft3", "ft4", "ft5", "ft6", "ft7", "fs0", "fs1", "fa0", "fa1", "fa2", "fa3", "fa4", "fa5",
                "fa6", "fa7", "fs2", "fs3", "fs4", "fs5", "fs6", "fs7", "fs8", "fs9", "fs10", "fs11", "ft8", "ft9", "ft10", "ft11"};
        for (int i = 0; i < 32; i++) {
            REGISTROS_F.put(fabi[i], i);
            REGISTROS_F.put("f" + i, i);
        }
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private final Salida salida;
    private final Entrada entrada;

    // programa decodificado
    private int[] codigo = new int[0];
    private int[] rd = new int[0];
    private int[] rs1 = new int[0];
    private int[] rs2 = new int[0];
    private int[] imm = new int[0];
    private int[] lineaDe = new int[0];
    private int[] ramaDe = new int[0];

    private final Map<String, Integer> simbolos = new HashMap<>();
    private int puntoEntrada = BASE_TEXTO;

    // maquina
    private final byte[][] paginas = new byte[1 << 20][];
    private final int[] x = new int[32];
    private final double[] f = new double[32];
    private int heap = BASE_MONTON;
    private volatile boolean cancelado = false;
    private final StringBuilder buffer = new StringBuilder();
    private int codigoSalida = 0;
    private boolean terminado = false;

    public InterpreteRiscV(Salida salida, Entrada entrada) {
        this.salida = salida;
        this.entrada = entrada;
    }

    /** Pide que la ejecucion en curso se detenga (se puede llamar desde otro hilo). */
    public void cancelar() {
        cancelado = true;
    }

    // ------------------------------------------------------------------
    // Ensamblado
    // ------------------------------------------------------------------

    private static final class Linea {
        final int numero;
        final String mnemonico;
        final List<String> args;

        Linea(int numero, String mnemonico, List<String> args) {
            this.numero = numero;
            this.mnemonico = mnemonico;
            this.args = args;
        }
    }

    /** @throws ErrorRiscV si el texto no es ensamblador valido. */
    public void cargar(String texto) {
        List<Linea> lineas = new ArrayList<>();
        boolean enDatos = false;
        int ptrDatos = BASE_DATOS;
        String[] crudas = texto.split("\r?\n");
        // Pasada 1: etiquetas y segmento de datos
        List<String[]> pendientesTexto = new ArrayList<>();
        for (int n = 0; n < crudas.length; n++) {
            String s = quitarComentario(crudas[n]).trim();
            while (true) {
                int dp = indiceEtiqueta(s);
                if (dp < 0) {
                    break;
                }
                String nombre = s.substring(0, dp).trim();
                s = s.substring(dp + 1).trim();
                if (simbolos.containsKey(nombre)) {
                    throw new ErrorRiscV("etiqueta '" + nombre + "' repetida", n + 1);
                }
                if (enDatos) {
                    simbolos.put(nombre, ptrDatos);
                } else {
                    simbolos.put(nombre, BASE_TEXTO + 4 * contarInstrucciones(lineas));
                }
            }
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith(".")) {
                String dir = s.split("\\s+", 2)[0];
                String resto = s.length() > dir.length() ? s.substring(dir.length()).trim() : "";
                switch (dir) {
                    case ".data":
                        enDatos = true;
                        break;
                    case ".text":
                        enDatos = false;
                        break;
                    case ".globl":
                    case ".global":
                    case ".eqv":
                        break;
                    case ".align": {
                        int a = 1 << Integer.parseInt(resto.trim());
                        ptrDatos = (ptrDatos + a - 1) / a * a;
                        break;
                    }
                    case ".asciz":
                    case ".ascii":
                    case ".string": {
                        String cad = decodificarCadena(resto, n + 1);
                        byte[] b = cad.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                        for (byte v : b) {
                            escribirByte(ptrDatos++, v);
                        }
                        if (!".ascii".equals(dir)) {
                            escribirByte(ptrDatos++, (byte) 0);
                        }
                        break;
                    }
                    case ".word":
                        for (String v : resto.split(",")) {
                            ptrDatos = (ptrDatos + 3) / 4 * 4;
                            escribirInt(ptrDatos, (int) parsearEntero(v.trim(), n + 1));
                            ptrDatos += 4;
                        }
                        break;
                    case ".byte":
                        for (String v : resto.split(",")) {
                            escribirByte(ptrDatos++, (byte) parsearEntero(v.trim(), n + 1));
                        }
                        break;
                    case ".space":
                        ptrDatos += (int) parsearEntero(resto, n + 1);
                        break;
                    case ".double":
                        for (String v : resto.split(",")) {
                            ptrDatos = (ptrDatos + 7) / 8 * 8;
                            escribirDouble(ptrDatos, Double.parseDouble(v.trim()));
                            ptrDatos += 8;
                        }
                        break;
                    default:
                        throw new ErrorRiscV("directiva no soportada: " + dir, n + 1);
                }
                continue;
            }
            if (enDatos) {
                throw new ErrorRiscV("instruccion dentro de .data: " + s, n + 1);
            }
            lineas.addAll(expandir(n + 1, s));
        }
        // Pasada 2: decodificar
        int total = lineas.size();
        codigo = new int[total];
        rd = new int[total];
        rs1 = new int[total];
        rs2 = new int[total];
        imm = new int[total];
        lineaDe = new int[total];
        ramaDe = new int[total];
        for (int i = 0; i < total; i++) {
            decodificar(i, lineas.get(i));
        }
        Integer main = simbolos.get("main");
        puntoEntrada = main != null ? main : BASE_TEXTO;
    }

    private int contarInstrucciones(List<Linea> lineas) {
        return lineas.size();
    }

    private static String quitarComentario(String s) {
        boolean enCadena = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                enCadena = !enCadena;
            } else if (c == '#' && !enCadena) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    /** Posicion del ':' de una etiqueta al inicio de la linea, o -1. */
    private static int indiceEtiqueta(String s) {
        int i = 0;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.' || s.charAt(i) == '$')) {
            i++;
        }
        return (i > 0 && i < s.length() && s.charAt(i) == ':') ? i : -1;
    }

    private static String decodificarCadena(String lit, int linea) {
        lit = lit.trim();
        if (lit.length() < 2 || lit.charAt(0) != '"' || lit.charAt(lit.length() - 1) != '"') {
            throw new ErrorRiscV("cadena mal formada: " + lit, linea);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lit.length() - 1; i++) {
            char c = lit.charAt(i);
            if (c == '\\' && i + 1 < lit.length() - 1) {
                char e = lit.charAt(++i);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '0': sb.append('\0'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case '\'': sb.append('\''); break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        // los caracteres fuera de Latin-1 se guardan en UTF-8 para que se impriman igual
        byte[] utf8 = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new String(utf8, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private long parsearEntero(String s, int linea) {
        s = s.trim();
        try {
            if (s.startsWith("0x") || s.startsWith("-0x")) {
                boolean neg = s.startsWith("-");
                long v = Long.parseLong(s.substring(neg ? 3 : 2), 16);
                return neg ? -v : v;
            }
            if (s.length() == 3 && s.charAt(0) == '\'' && s.charAt(2) == '\'') {
                return s.charAt(1);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ErrorRiscV("numero invalido: '" + s + "'", linea);
        }
    }

    /** Expande seudoinstrucciones a instrucciones reales (1 a 2). */
    private List<Linea> expandir(int numero, String s) {
        String[] partes = s.split("\\s+", 2);
        String m = partes[0].toLowerCase(Locale.ROOT);
        List<String> a = new ArrayList<>();
        if (partes.length > 1) {
            for (String p : partes[1].split(",")) {
                a.add(p.trim());
            }
        }
        List<Linea> out = new ArrayList<>();
        switch (m) {
            case "li": {
                long v = parsearEntero(a.get(1), numero);
                int val = (int) v;
                if (val >= -2048 && val <= 2047) {
                    out.add(new Linea(numero, "addi", List.of(a.get(0), "zero", String.valueOf(val))));
                } else {
                    int alto = (val + 0x800) >> 12;
                    int bajo = val - (alto << 12);
                    out.add(new Linea(numero, "lui", List.of(a.get(0), String.valueOf(alto & 0xfffff))));
                    out.add(new Linea(numero, "addi", List.of(a.get(0), a.get(0), String.valueOf(bajo))));
                }
                break;
            }
            case "la":
                // dos instrucciones fijas (lui + addi) para que las direcciones de las etiquetas no dependan del valor
                out.add(new Linea(numero, "la_alto", List.of(a.get(0), a.get(1))));
                out.add(new Linea(numero, "la_bajo", List.of(a.get(0), a.get(1))));
                break;
            case "mv": out.add(new Linea(numero, "addi", List.of(a.get(0), a.get(1), "0"))); break;
            case "neg": out.add(new Linea(numero, "sub", List.of(a.get(0), "zero", a.get(1)))); break;
            case "not": out.add(new Linea(numero, "xori", List.of(a.get(0), a.get(1), "-1"))); break;
            case "seqz": out.add(new Linea(numero, "sltiu", List.of(a.get(0), a.get(1), "1"))); break;
            case "snez": out.add(new Linea(numero, "sltu", List.of(a.get(0), "zero", a.get(1)))); break;
            case "nop": out.add(new Linea(numero, "addi", List.of("zero", "zero", "0"))); break;
            case "j": out.add(new Linea(numero, "jal", List.of("zero", a.get(0)))); break;
            case "jal":
                if (a.size() == 1) {
                    out.add(new Linea(numero, "jal", List.of("ra", a.get(0))));
                } else {
                    out.add(new Linea(numero, "jal", a));
                }
                break;
            case "jr": out.add(new Linea(numero, "jalr", List.of("zero", a.get(0), "0"))); break;
            case "ret": out.add(new Linea(numero, "jalr", List.of("zero", "ra", "0"))); break;
            case "beqz": out.add(new Linea(numero, "beq", List.of(a.get(0), "zero", a.get(1)))); break;
            case "bnez": out.add(new Linea(numero, "bne", List.of(a.get(0), "zero", a.get(1)))); break;
            case "blez": out.add(new Linea(numero, "bge", List.of("zero", a.get(0), a.get(1)))); break;
            case "bgez": out.add(new Linea(numero, "bge", List.of(a.get(0), "zero", a.get(1)))); break;
            case "bltz": out.add(new Linea(numero, "blt", List.of(a.get(0), "zero", a.get(1)))); break;
            case "bgtz": out.add(new Linea(numero, "blt", List.of("zero", a.get(0), a.get(1)))); break;
            case "bgt": out.add(new Linea(numero, "blt", List.of(a.get(1), a.get(0), a.get(2)))); break;
            case "ble": out.add(new Linea(numero, "bge", List.of(a.get(1), a.get(0), a.get(2)))); break;
            case "bgtu": out.add(new Linea(numero, "bltu", List.of(a.get(1), a.get(0), a.get(2)))); break;
            case "bleu": out.add(new Linea(numero, "bgeu", List.of(a.get(1), a.get(0), a.get(2)))); break;
            default:
                out.add(new Linea(numero, m, a));
        }
        return out;
    }

    private int reg(String s, int linea) {
        Integer r = REGISTROS.get(s.trim());
        if (r == null) {
            throw new ErrorRiscV("registro desconocido: '" + s + "'", linea);
        }
        return r;
    }

    private int regF(String s, int linea) {
        Integer r = REGISTROS_F.get(s.trim());
        if (r == null) {
            throw new ErrorRiscV("registro flotante desconocido: '" + s + "'", linea);
        }
        return r;
    }

    private int direccionDe(String etiqueta, int linea) {
        Integer d = simbolos.get(etiqueta.trim());
        if (d == null) {
            throw new ErrorRiscV("etiqueta no definida: '" + etiqueta + "'", linea);
        }
        return d;
    }

    private void decodificar(int i, Linea l) {
        String m = l.mnemonico;
        List<String> a = l.args;
        int n = l.numero;
        lineaDe[i] = n;
        try {
            if (TIPO_R.containsKey(m)) {
                codigo[i] = TIPO_R.get(m);
                rd[i] = reg(a.get(0), n);
                rs1[i] = reg(a.get(1), n);
                rs2[i] = reg(a.get(2), n);
            } else if (TIPO_I.containsKey(m)) {
                codigo[i] = TIPO_I.get(m);
                rd[i] = reg(a.get(0), n);
                rs1[i] = reg(a.get(1), n);
                imm[i] = (int) parsearEntero(a.get(2), n);
            } else if (CARGAS.containsKey(m) || GUARDAS.containsKey(m)) {
                codigo[i] = CARGAS.containsKey(m) ? CARGAS.get(m) : GUARDAS.get(m);
                rd[i] = reg(a.get(0), n);
                memoriaOperando(i, a.get(1), n);
            } else if (RAMAS.containsKey(m)) {
                codigo[i] = RAMAS.get(m);
                rs1[i] = reg(a.get(0), n);
                rs2[i] = reg(a.get(1), n);
                ramaDe[i] = direccionDe(a.get(2), n);
            } else if ("jal".equals(m)) {
                codigo[i] = JAL;
                rd[i] = reg(a.get(0), n);
                ramaDe[i] = direccionDe(a.get(1), n);
            } else if ("jalr".equals(m)) {
                codigo[i] = JALR;
                rd[i] = reg(a.get(0), n);
                rs1[i] = reg(a.get(1), n);
                imm[i] = a.size() > 2 ? (int) parsearEntero(a.get(2), n) : 0;
            } else if ("lui".equals(m)) {
                codigo[i] = LUI;
                rd[i] = reg(a.get(0), n);
                imm[i] = (int) parsearEntero(a.get(1), n) << 12;
            } else if ("auipc".equals(m)) {
                codigo[i] = AUIPC;
                rd[i] = reg(a.get(0), n);
                imm[i] = (int) parsearEntero(a.get(1), n) << 12;
            } else if ("la_alto".equals(m)) {
                codigo[i] = LUI;
                rd[i] = reg(a.get(0), n);
                int dir = direccionDe(a.get(1), n);
                imm[i] = ((dir + 0x800) >> 12) << 12;
            } else if ("la_bajo".equals(m)) {
                codigo[i] = ADDI;
                rd[i] = reg(a.get(0), n);
                rs1[i] = rd[i];
                int dir = direccionDe(a.get(1), n);
                imm[i] = dir - (((dir + 0x800) >> 12) << 12);
            } else if ("ecall".equals(m)) {
                codigo[i] = ECALL;
            } else if ("fld".equals(m) || "fsd".equals(m)) {
                codigo[i] = "fld".equals(m) ? FLD : FSD;
                rd[i] = regF(a.get(0), n);
                memoriaOperando(i, a.get(1), n);
            } else if (FP3.containsKey(m)) {
                codigo[i] = FP3.get(m);
                rd[i] = regF(a.get(0), n);
                rs1[i] = regF(a.get(1), n);
                rs2[i] = regF(a.get(2), n);
            } else if (FP2.containsKey(m)) {
                codigo[i] = FP2.get(m);
                rd[i] = regF(a.get(0), n);
                rs1[i] = regF(a.get(1), n);
            } else if (FPCMP.containsKey(m)) {
                codigo[i] = FPCMP.get(m);
                rd[i] = reg(a.get(0), n);
                rs1[i] = regF(a.get(1), n);
                rs2[i] = regF(a.get(2), n);
            } else if ("fcvt.w.d".equals(m)) {
                codigo[i] = FCVTWD;
                rd[i] = reg(a.get(0), n);
                rs1[i] = regF(a.get(1), n);
                imm[i] = a.size() > 2 ? modoRedondeo(a.get(2)) : 0;
            } else if ("fcvt.d.w".equals(m)) {
                codigo[i] = FCVTDW;
                rd[i] = regF(a.get(0), n);
                rs1[i] = reg(a.get(1), n);
            } else {
                throw new ErrorRiscV("instruccion no soportada: '" + m + "'", n);
            }
        } catch (IndexOutOfBoundsException e) {
            throw new ErrorRiscV("faltan operandos en '" + m + "'", n);
        }
    }

    private static int modoRedondeo(String s) {
        switch (s.trim()) {
            case "rtz": return 1;
            case "rdn": return 2;
            case "rup": return 3;
            default: return 0; // rne / dyn
        }
    }

    /** "off(reg)" -> imm = off, rs1 = reg. */
    private void memoriaOperando(int i, String op, int n) {
        int p = op.indexOf('(');
        int q = op.indexOf(')');
        if (p < 0 || q < p) {
            throw new ErrorRiscV("operando de memoria invalido: '" + op + "'", n);
        }
        String off = op.substring(0, p).trim();
        imm[i] = off.isEmpty() ? 0 : (int) parsearEntero(off, n);
        rs1[i] = reg(op.substring(p + 1, q), n);
    }

    // ------------------------------------------------------------------
    // Memoria
    // ------------------------------------------------------------------

    private byte[] pagina(int dir, boolean crear, int pc) {
        int p = dir >>> 12;
        byte[] pg = paginas[p];
        if (pg == null) {
            if (!crear) {
                return null;
            }
            pg = new byte[4096];
            paginas[p] = pg;
        }
        return pg;
    }

    private void escribirByte(int dir, byte v) {
        pagina(dir, true, 0)[dir & 4095] = v;
    }

    private void escribirInt(int dir, int v) {
        for (int k = 0; k < 4; k++) {
            escribirByte(dir + k, (byte) (v >>> (8 * k)));
        }
    }

    private void escribirDouble(int dir, double v) {
        long b = Double.doubleToRawLongBits(v);
        escribirInt(dir, (int) b);
        escribirInt(dir + 4, (int) (b >>> 32));
    }

    private int leerByte(int dir) {
        if (dir >= 0 && dir < 4096 * 16) {
            throw new ErrorRiscV(String.format("acceso a memoria invalido (0x%08x): probablemente un objeto nulo", dir), 0);
        }
        byte[] pg = paginas[dir >>> 12];
        return pg == null ? 0 : pg[dir & 4095];
    }

    private int leerInt(int dir) {
        int v = 0;
        for (int k = 3; k >= 0; k--) {
            v = (v << 8) | (leerByte(dir + k) & 0xff);
        }
        return v;
    }

    private double leerDouble(int dir) {
        long lo = leerInt(dir) & 0xffffffffL;
        long hi = leerInt(dir + 4) & 0xffffffffL;
        return Double.longBitsToDouble((hi << 32) | lo);
    }

    private void escribirIntVigilado(int dir, int v) {
        if (dir >= 0 && dir < 4096 * 16) {
            throw new ErrorRiscV(String.format("escritura en memoria invalida (0x%08x): probablemente un objeto nulo", dir), 0);
        }
        escribirInt(dir, v);
    }

    private String leerCadena(int dir) {
        byte[] tmp = new byte[64];
        int n = 0;
        while (true) {
            int b = leerByte(dir++);
            if (b == 0) {
                break;
            }
            if (n == tmp.length) {
                tmp = java.util.Arrays.copyOf(tmp, n * 2);
            }
            tmp[n++] = (byte) b;
        }
        return new String(tmp, 0, n, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Ejecucion
    // ------------------------------------------------------------------

    private void volcar() {
        if (buffer.length() > 0) {
            salida.escribir(buffer.toString());
            buffer.setLength(0);
        }
    }

    private void imprimir(String s) {
        buffer.append(s);
        if (buffer.length() > 2048) {
            volcar();
        }
    }

    /**
     * Ejecuta el programa cargado.
     *
     * @param maxInstrucciones tope para cortar ciclos infinitos (0 = sin tope)
     */
    public Resultado ejecutar(long maxInstrucciones) {
        java.util.Arrays.fill(x, 0);
        java.util.Arrays.fill(f, 0.0);
        x[2] = TOPE_PILA;
        x[3] = BASE_DATOS + 0x8000;
        heap = BASE_MONTON;
        terminado = false;
        cancelado = false;
        codigoSalida = 0;
        int pc = (puntoEntrada - BASE_TEXTO) / 4;
        long contador = 0;
        String error = null;
        int lineaError = 0;
        try {
            while (!terminado) {
                if (pc < 0 || pc >= codigo.length) {
                    throw new ErrorRiscV(String.format("el programa salio del codigo (pc=0x%08x); falta terminar con ecall 10", BASE_TEXTO + 4 * pc), 0);
                }
                if ((++contador & 0xFFFF) == 0 && cancelado) {
                    error = "ejecucion cancelada";
                    break;
                }
                if (maxInstrucciones > 0 && contador > maxInstrucciones) {
                    error = "se supero el limite de " + maxInstrucciones + " instrucciones (posible ciclo infinito)";
                    break;
                }
                lineaError = lineaDe[pc];
                pc = paso(pc);
                x[0] = 0;
            }
        } catch (ErrorRiscV e) {
            error = e.getMessage();
            lineaError = e.linea != 0 ? e.linea : lineaError;
        } catch (ArithmeticException e) {
            error = "division entre cero";
        } finally {
            volcar();
        }
        if (error != null && lineaError > 0) {
            error = "linea " + lineaError + " del ensamblador: " + error;
        }
        return new Resultado(error == null, error, contador, codigoSalida);
    }

    /** Ejecuta la instruccion 'pc' y devuelve el indice de la siguiente. */
    private int paso(int pc) {
        int op = codigo[pc];
        int a = rd[pc];
        int b = rs1[pc];
        int c = rs2[pc];
        int im = imm[pc];
        switch (op) {
            case ADD: x[a] = x[b] + x[c]; break;
            case SUB: x[a] = x[b] - x[c]; break;
            case MUL: x[a] = x[b] * x[c]; break;
            case MULH: x[a] = (int) (((long) x[b] * (long) x[c]) >> 32); break;
            case DIV:
                if (x[c] == 0) {
                    x[a] = -1;
                } else if (x[b] == Integer.MIN_VALUE && x[c] == -1) {
                    x[a] = Integer.MIN_VALUE;
                } else {
                    x[a] = x[b] / x[c];
                }
                break;
            case REM:
                if (x[c] == 0) {
                    x[a] = x[b];
                } else if (x[b] == Integer.MIN_VALUE && x[c] == -1) {
                    x[a] = 0;
                } else {
                    x[a] = x[b] % x[c];
                }
                break;
            case DIVU: x[a] = x[c] == 0 ? -1 : (int) (Integer.toUnsignedLong(x[b]) / Integer.toUnsignedLong(x[c])); break;
            case REMU: x[a] = x[c] == 0 ? x[b] : (int) (Integer.toUnsignedLong(x[b]) % Integer.toUnsignedLong(x[c])); break;
            case AND: x[a] = x[b] & x[c]; break;
            case OR: x[a] = x[b] | x[c]; break;
            case XOR: x[a] = x[b] ^ x[c]; break;
            case SLL: x[a] = x[b] << (x[c] & 31); break;
            case SRL: x[a] = x[b] >>> (x[c] & 31); break;
            case SRA: x[a] = x[b] >> (x[c] & 31); break;
            case SLT: x[a] = x[b] < x[c] ? 1 : 0; break;
            case SLTU: x[a] = Integer.compareUnsigned(x[b], x[c]) < 0 ? 1 : 0; break;
            case ADDI: x[a] = x[b] + im; break;
            case ANDI: x[a] = x[b] & im; break;
            case ORI: x[a] = x[b] | im; break;
            case XORI: x[a] = x[b] ^ im; break;
            case SLLI: x[a] = x[b] << (im & 31); break;
            case SRLI: x[a] = x[b] >>> (im & 31); break;
            case SRAI: x[a] = x[b] >> (im & 31); break;
            case SLTI: x[a] = x[b] < im ? 1 : 0; break;
            case SLTIU: x[a] = Integer.compareUnsigned(x[b], im) < 0 ? 1 : 0; break;
            case LW: x[a] = leerInt(x[b] + im); break;
            case LB: x[a] = (byte) leerByte(x[b] + im); break;
            case LBU: x[a] = leerByte(x[b] + im) & 0xff; break;
            case LH: x[a] = (short) ((leerByte(x[b] + im) & 0xff) | (leerByte(x[b] + im + 1) << 8)); break;
            case LHU: x[a] = (leerByte(x[b] + im) & 0xff) | ((leerByte(x[b] + im + 1) & 0xff) << 8); break;
            case SW: escribirIntVigilado(x[b] + im, x[a]); break;
            case SB:
                if (x[b] + im >= 0 && x[b] + im < 4096 * 16) {
                    throw new ErrorRiscV("escritura en memoria invalida: probablemente un objeto nulo", 0);
                }
                escribirByte(x[b] + im, (byte) x[a]);
                break;
            case SH:
                escribirByte(x[b] + im, (byte) x[a]);
                escribirByte(x[b] + im + 1, (byte) (x[a] >>> 8));
                break;
            case BEQ: if (x[b] == x[c]) return (ramaDe[pc] - BASE_TEXTO) / 4; break;
            case BNE: if (x[b] != x[c]) return (ramaDe[pc] - BASE_TEXTO) / 4; break;
            case BLT: if (x[b] < x[c]) return (ramaDe[pc] - BASE_TEXTO) / 4; break;
            case BGE: if (x[b] >= x[c]) return (ramaDe[pc] - BASE_TEXTO) / 4; break;
            case BLTU: if (Integer.compareUnsigned(x[b], x[c]) < 0) return (ramaDe[pc] - BASE_TEXTO) / 4; break;
            case BGEU: if (Integer.compareUnsigned(x[b], x[c]) >= 0) return (ramaDe[pc] - BASE_TEXTO) / 4; break;
            case JAL:
                x[a] = BASE_TEXTO + 4 * (pc + 1);
                return (ramaDe[pc] - BASE_TEXTO) / 4;
            case JALR: {
                int destino = (x[b] + im) & ~1;
                x[a] = BASE_TEXTO + 4 * (pc + 1);
                return (destino - BASE_TEXTO) / 4;
            }
            case LUI: x[a] = im; break;
            case AUIPC: x[a] = BASE_TEXTO + 4 * pc + im; break;
            case ECALL: ecall(); break;
            case FLD: f[a] = leerDouble(x[b] + im); break;
            case FSD: escribirDouble(x[b] + im, f[a]); break;
            case FADD: f[a] = f[b] + f[c]; break;
            case FSUB: f[a] = f[b] - f[c]; break;
            case FMUL: f[a] = f[b] * f[c]; break;
            case FDIV: f[a] = f[b] / f[c]; break;
            case FMIN: f[a] = Math.min(f[b], f[c]); break;
            case FMAX: f[a] = Math.max(f[b], f[c]); break;
            case FSQRT: f[a] = Math.sqrt(f[b]); break;
            case FNEG: f[a] = -f[b]; break;
            case FABS: f[a] = Math.abs(f[b]); break;
            case FMVD: f[a] = f[b]; break;
            case FEQ: x[a] = f[b] == f[c] ? 1 : 0; break;
            case FLT: x[a] = f[b] < f[c] ? 1 : 0; break;
            case FLE: x[a] = f[b] <= f[c] ? 1 : 0; break;
            case FCVTWD: {
                double v = f[b];
                double r;
                switch (im) {
                    case 1: r = v < 0 ? Math.ceil(v) : Math.floor(v); break;
                    case 2: r = Math.floor(v); break;
                    case 3: r = Math.ceil(v); break;
                    default: r = Math.rint(v);
                }
                x[a] = Double.isNaN(r) ? Integer.MAX_VALUE : (r >= 2147483647.0 ? Integer.MAX_VALUE : (r <= -2147483648.0 ? Integer.MIN_VALUE : (int) r));
                break;
            }
            case FCVTDW: f[a] = x[b]; break;
            default:
                throw new ErrorRiscV("instruccion invalida", 0);
        }
        return pc + 1;
    }

    // ------------------------------------------------------------------
    // Servicios del sistema (ecall), numeracion de RARS
    // ------------------------------------------------------------------

    private String lineaEntrada() {
        volcar();
        return entrada == null ? null : entrada.leerLinea();
    }

    private void ecall() {
        int servicio = x[17];
        switch (servicio) {
            case 1:
                imprimir(Integer.toString(x[10]));
                break;
            case 2:
                imprimir(Float.toString(Float.intBitsToFloat(x[10])));
                break;
            case 3:
                imprimir(Double.toString(f[10]));
                break;
            case 4:
                imprimir(leerCadena(x[10]));
                break;
            case 5: {
                String s = lineaEntrada();
                if (s == null) {
                    throw new ErrorRiscV("fin de la entrada al leer un entero", 0);
                }
                try {
                    x[10] = Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    throw new ErrorRiscV("entrada invalida, se esperaba un entero: '" + s.trim() + "'", 0);
                }
                break;
            }
            case 7: {
                String s = lineaEntrada();
                if (s == null) {
                    throw new ErrorRiscV("fin de la entrada al leer un decimal", 0);
                }
                try {
                    f[10] = Double.parseDouble(s.trim());
                } catch (NumberFormatException e) {
                    throw new ErrorRiscV("entrada invalida, se esperaba un decimal: '" + s.trim() + "'", 0);
                }
                break;
            }
            case 8: {
                String s = lineaEntrada();
                int dir = x[10];
                int max = x[11];
                if (s != null && max > 0) {
                    byte[] b = (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    int n = Math.min(b.length, max - 1);
                    for (int k = 0; k < n; k++) {
                        escribirByte(dir + k, b[k]);
                    }
                    escribirByte(dir + n, (byte) 0);
                } else if (max > 0) {
                    escribirByte(dir, (byte) 0);
                }
                break;
            }
            case 9: {
                int n = x[10];
                int dir = heap;
                heap += (n + 3) / 4 * 4;
                x[10] = dir;
                break;
            }
            case 10:
                terminado = true;
                break;
            case 11:
                imprimir(new String(new char[]{(char) (x[10] & 0xffff)}));
                break;
            case 12: {
                String s = lineaEntrada();
                if (s == null) {
                    throw new ErrorRiscV("fin de la entrada al leer un caracter", 0);
                }
                x[10] = s.isEmpty() ? '\n' : s.charAt(0);
                break;
            }
            case 17:
                codigoSalida = x[10];
                terminado = true;
                break;
            case 34:
                imprimir(String.format("0x%08x", x[10]));
                break;
            case 36:
                imprimir(Integer.toUnsignedString(x[10]));
                break;
            default:
                throw new ErrorRiscV("servicio de sistema (ecall) no soportado: " + servicio, 0);
        }
    }
}
