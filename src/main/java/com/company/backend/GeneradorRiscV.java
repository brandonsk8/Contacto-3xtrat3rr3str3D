package com.company.backend;

import com.company.ir.TablaCuartetas;
import com.company.semantico.TablaSimbolosGlobal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orquestador del backend RISC-V (ensamblador RV32IMFD para RARS). Usa el
 * mismo ModeloPrograma que el backend de C con etiquetas, asi que las
 * funciones, tipos y arreglos de N dimensiones se interpretan igual en
 * los dos. Pendiente (fase 2): clases/objetos (herencia, polimorfismo),
 * concatenacion y comparacion de cadenas. Lo no soportado no genera
 * ensamblador invalido: la funcion se omite y queda en getNotas().
 */
public class GeneradorRiscV {

    private final List<String> notas = new ArrayList<>();
    private final ModeloPrograma modelo;
    private final AsignadorMemoria asignadorMemoria = new AsignadorMemoria();
    private final EmisorInstruccionesRiscV emisor;

    // Genera el ensamblador RISC-V a partir de las cuartetas.
    public GeneradorRiscV(TablaSimbolosGlobal global) {
        this.modelo = new ModeloPrograma(global, notas, "RISC-V");
        this.emisor = new EmisorInstruccionesRiscV(modelo);
    }

    // Avisos de construcciones no soportadas.
    public List<String> getNotas() {
        return notas;
    }

    // Arma el programa .asm completo: .data, .text, funciones y rutinas.
    public String generar(String nombrePrograma, TablaCuartetas tabla) {
        notas.clear();
        emisor.reiniciarRutinas();
        AlmacenLiterales literales = new AlmacenLiterales();
        Map<String, ModeloPrograma.Fn> fns = modelo.construir(tabla.getCuartetas());

        Map<String, String> codigos;
        boolean hayCambios;
        do {
            codigos = new LinkedHashMap<>();
            hayCambios = false;
            for (ModeloPrograma.Fn f : new ArrayList<>(fns.values())) {
                try {
                    codigos.put(f.nombre, generarFuncion(f, fns, literales));
                } catch (ModeloPrograma.NoSoportado e) {
                    notas.add("funcion '" + f.nombre + "' (RISC-V): " + e.getMessage() + " - pendiente");
                    fns.remove(f.nombre);
                    hayCambios = true;
                    break;
                }
            }
            if (hayCambios) {
                literales = new AlmacenLiterales();
            }
        } while (hayCambios);

        StringBuilder out = new StringBuilder();
        out.append("# Codigo generado automaticamente a partir de ").append(nombrePrograma)
                .append(" (RISC-V RV32IMFD - syscalls de RARS)\n\n");
        out.append(".data\n");
        out.append(literales.emitirSeccionDatos());
        out.append("__vacio: .asciz \"\"\n");
        out.append(".align 3\n__args: .space 512\n");
        out.append("\n.text\n.globl main\n");
        // Aunque RARS no tenga activada la opcion "iniciar en main", la
        // ejecucion arranca en la primera instruccion: salta a main.
        out.append("    j main\n\n");
        for (Map.Entry<String, String> e : codigos.entrySet()) {
            out.append(e.getValue()).append("\n");
        }
        if (!fns.containsKey("main")) {
            out.append("main:\n    li a7, 10\n    ecall\n");
        }
        out.append("\n").append(emisor.rutinasDeApoyo());
        return out.toString();
    }

    // Calcula el marco de pila (slots) de una funcion.
    private MarcoFuncion marcoDe(ModeloPrograma.Fn f) {
        Map<String, String> tipoMarco = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : f.tipo.entrySet()) {
            String v = e.getKey();
            if (f.dims.containsKey(v)) {
                tipoMarco.put(v, "int"); // puntero al bloque
                for (int k = 0; k < f.dims.get(v); k++) {
                    tipoMarco.put(v + "#d" + k, "int");
                }
            } else {
                tipoMarco.put(v, "double".equals(e.getValue()) ? "double" : "int");
            }
        }
        tipoMarco.put("#s0", "int"); // zonas de borrador (cadenas)
        tipoMarco.put("#s1", "int");
        return asignadorMemoria.asignar(tipoMarco);
    }

    // Emite prologo, cuerpo y epilogo de una funcion.
    private String generarFuncion(ModeloPrograma.Fn f, Map<String, ModeloPrograma.Fn> fns, AlmacenLiterales literales) {
        MarcoFuncion marco = marcoDe(f);
        String etiquetaFin = f.nombre + "_fin";
        String cuerpo = emisor.emitir(f, fns, marco, etiquetaFin, literales);

        StringBuilder asm = new StringBuilder();
        asm.append(f.nombre).append(":\n");
        asm.append(AsmUtil.ajustarSp(-marco.tamanioFrame));
        if (!f.esMain) {
            asm.append(AsmUtil.mem("sw", "ra", marco.offsetRa));
            int enteros = 0;
            int flotantes = 0;
            int extra = 0; // se leen del area global __args (ver EmisorInstruccionesRiscV.emitirCall)
            for (String p : f.params) {
                if (f.dims.containsKey(p)) {
                    int d = f.dims.get(p);
                    for (int k = -1; k < d; k++) {
                        String destino = k < 0 ? p : p + "#d" + k;
                        if (enteros < 8) {
                            asm.append(AsmUtil.mem("sw", "a" + enteros++, marco.offsetDe.get(destino)));
                        } else {
                            asm.append("    la t6, __args\n    lw t0, ").append(8 * extra++).append("(t6)\n");
                            asm.append(AsmUtil.mem("sw", "t0", marco.offsetDe.get(destino)));
                        }
                    }
                } else if ("double".equals(f.tipo.get(p))) {
                    if (flotantes < 8) {
                        asm.append(AsmUtil.mem("fsd", "fa" + flotantes++, marco.offsetDe.get(p)));
                    } else {
                        asm.append("    la t6, __args\n    fld ft0, ").append(8 * extra++).append("(t6)\n");
                        asm.append(AsmUtil.mem("fsd", "ft0", marco.offsetDe.get(p)));
                    }
                } else {
                    if (enteros < 8) {
                        asm.append(AsmUtil.mem("sw", "a" + enteros++, marco.offsetDe.get(p)));
                    } else {
                        asm.append("    la t6, __args\n    lw t0, ").append(8 * extra++).append("(t6)\n");
                        asm.append(AsmUtil.mem("sw", "t0", marco.offsetDe.get(p)));
                    }
                }
            }
        }
        asm.append(cuerpo);
        asm.append(etiquetaFin).append(":\n");
        if (f.esMain) {
            asm.append("    li a7, 10\n    ecall\n");
        } else {
            asm.append(AsmUtil.mem("lw", "ra", marco.offsetRa));
            asm.append(AsmUtil.ajustarSp(marco.tamanioFrame));
            asm.append("    ret\n");
        }
        return asm.toString();
    }
}
