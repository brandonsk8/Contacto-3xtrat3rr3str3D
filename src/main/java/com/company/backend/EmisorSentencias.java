package com.company.backend;

import com.company.ir.Cuarteta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pase B del backend: con los tipos ya resueltos por InferenciaTipos
 * (Pase A), recorre otra vez el mismo cuerpo y emite la sentencia C real
 * de cada cuarteta (asignaciones, saltos, llamadas, lectura/escritura,
 * arreglos, concat, ...).
 */
final class EmisorSentencias {

    private final TiposC tipos;
    private final List<String> notas;

    EmisorSentencias(TiposC tipos, List<String> notas) {
        this.tipos = tipos;
        this.notas = notas;
    }

    /**
     * 'helpersLocales' junta los helpers de cadena usados dentro de esta
     * funcion; el llamador los suma al set global solo si la funcion
     * termina generandose bien (si no, quedaria un helper declarado sin usar).
     */
    String emitir(String nombreFuncion, List<Cuarteta> cuerpo, Map<String, String> tipoDe, Set<String> helpersLocales) {
        StringBuilder sent = new StringBuilder();
        List<String> paramsPendientes = new ArrayList<>();
        int idx = 0;
        while (idx < cuerpo.size()) {
            Cuarteta c = cuerpo.get(idx);
            String op = c.getOperador();
            switch (op) {
                case "label":
                    sent.append("    ").append(c.getResultado()).append(": ;\n");
                    idx++;
                    break;
                case "goto":
                    sent.append("    goto ").append(c.getResultado()).append(";\n");
                    idx++;
                    break;
                case "if_false":
                    sent.append("    if (!(").append(tipos.mapTexto(c.getArg1(), tipoDe)).append(")) goto ").append(c.getResultado()).append(";\n");
                    idx++;
                    break;
                case "if_true":
                    sent.append("    if (").append(tipos.mapTexto(c.getArg1(), tipoDe)).append(") goto ").append(c.getResultado()).append(";\n");
                    idx++;
                    break;
                case "return":
                    if (c.getArg1() == null) {
                        sent.append("    return;\n");
                    } else {
                        sent.append("    return ").append(tipos.mapTexto(c.getArg1(), tipoDe)).append(";\n");
                    }
                    idx++;
                    break;
                case "print":
                    String tp = tipos.tipoDeOperando(c.getArg1(), tipoDe);
                    if (tp != null && tp.startsWith("struct ")) {
                        notas.add("funcion '" + nombreFuncion + "': 'imprimir' de una estructura/objeto completo no soportado todavia");
                        return null;
                    }
                    sent.append("    printf(\"").append(tipos.formatoPrintf(tp)).append("\\n\", ").append(tipos.mapTexto(c.getArg1(), tipoDe)).append(");\n");
                    idx++;
                    break;
                case "read": {
                    String destinoLeido = c.getResultado();
                    // 'read' puede llegar de dos formas: (a) llena un temporal y la
                    // cuarteta siguiente lo asigna a la variable real, o (b) escribe
                    // directo en la variable (asi lo hace Pig Latin). Si destinoLeido
                    // ya tiene tipo asignado, es el caso (b).
                    String tipoDestinoDirecto = tipoDe.get(destinoLeido);
                    if (tipoDestinoDirecto != null) {
                        if (tipoDestinoDirecto.startsWith("struct ") || "char*".equals(tipoDestinoDirecto)) {
                            notas.add("funcion '" + nombreFuncion + "': 'leer()' hacia una estructura/objeto/cadena completo no soportado todavia");
                            return null;
                        }
                        emitirScanf(sent, tipoDestinoDirecto, destinoLeido);
                        idx++;
                    } else if (idx + 1 < cuerpo.size()
                            && "=".equals(cuerpo.get(idx + 1).getOperador())
                            && destinoLeido.equals(cuerpo.get(idx + 1).getArg1())) {
                        String var = cuerpo.get(idx + 1).getResultado();
                        String tv = tipoDe.get(var);
                        if (tv != null && (tv.startsWith("struct ") || "char*".equals(tv))) {
                            notas.add("funcion '" + nombreFuncion + "': 'leer()' hacia una estructura/objeto/cadena completo no soportado todavia");
                            return null;
                        }
                        emitirScanf(sent, tv, var);
                        idx += 2;
                    } else {
                        notas.add("funcion '" + nombreFuncion + "': 'leer()' sin una asignacion inmediata a una variable");
                        return null;
                    }
                    break;
                }
                case "param":
                    paramsPendientes.add(tipos.mapTexto(c.getArg1(), tipoDe));
                    idx++;
                    break;
                case "call": {
                    String arg1 = c.getArg1();
                    String llamada;
                    if (arg1 != null && arg1.indexOf('.') > 0) {
                        int punto = arg1.lastIndexOf('.');
                        String nombreMetodo = arg1.substring(punto + 1);
                        String textoBase = arg1.substring(0, punto);
                        int n = Integer.parseInt(c.getArg2());
                        List<String> ultimos = paramsPendientes.subList(paramsPendientes.size() - (n + 1), paramsPendientes.size());
                        String selfTexto = ultimos.get(n);
                        List<String> argsReales = new ArrayList<>(ultimos.subList(0, n));
                        for (int r = 0; r < n + 1; r++) {
                            paramsPendientes.remove(paramsPendientes.size() - 1);
                        }
                        String tipoBase = tipos.tipoDeOperando(textoBase, tipoDe);
                        String claseNombre = tipos.nombreTipoDesdeC(tipoBase);
                        String nombreFuncionC = claseNombre + "_" + nombreMetodo + "_" + n;
                        StringBuilder args = new StringBuilder(selfTexto);
                        for (String a : argsReales) {
                            args.append(", ").append(a);
                        }
                        llamada = nombreFuncionC + "(" + args + ")";
                    } else {
                        int n = Integer.parseInt(c.getArg2());
                        List<String> args = new ArrayList<>(paramsPendientes.subList(paramsPendientes.size() - n, paramsPendientes.size()));
                        for (int r = 0; r < n; r++) {
                            paramsPendientes.remove(paramsPendientes.size() - 1);
                        }
                        llamada = arg1 + "(" + String.join(", ", args) + ")";
                    }
                    if (c.getResultado() != null) {
                        sent.append("    ").append(c.getResultado()).append(" = ").append(llamada).append(";\n");
                    } else {
                        sent.append("    ").append(llamada).append(";\n");
                    }
                    idx++;
                    break;
                }
                case "new": {
                    String claseNombre = c.getArg1();
                    int n = Integer.parseInt(c.getArg2());
                    List<String> args = new ArrayList<>(paramsPendientes.subList(paramsPendientes.size() - n, paramsPendientes.size()));
                    for (int r = 0; r < n; r++) {
                        paramsPendientes.remove(paramsPendientes.size() - 1);
                    }
                    String llamada = claseNombre + "_crear_" + n + "(" + String.join(", ", args) + ")";
                    if (c.getResultado() != null) {
                        sent.append("    ").append(c.getResultado()).append(" = ").append(llamada).append(";\n");
                    } else {
                        sent.append("    ").append(llamada).append(";\n");
                    }
                    idx++;
                    break;
                }
                case "new_array": {
                    String baseC = tipos.tipoCPrimitivoDeArreglo(c.getArg1());
                    String tamano = tipos.mapTexto(c.getArg2(), tipoDe);
                    sent.append("    ").append(c.getResultado()).append(" = malloc(sizeof(").append(baseC)
                            .append(") * (").append(tamano).append("));\n");
                    idx++;
                    break;
                }
                case "concat": {
                    helpersLocales.add("concat");
                    String textoIzq = tipos.wrapParaConcat(c.getArg1(), tipoDe, helpersLocales);
                    String textoDer = tipos.wrapParaConcat(c.getArg2(), tipoDe, helpersLocales);
                    sent.append("    ").append(c.getResultado()).append(" = c3d_concat(")
                            .append(textoIzq).append(", ").append(textoDer).append(");\n");
                    idx++;
                    break;
                }
                case "=": {
                    String textoDestino = tipos.traducirAccesoC(c.getResultado(), tipoDe);
                    String tipoDestino = tipos.tipoDeOperando(c.getResultado(), tipoDe);
                    String tipoOrigen = tipos.tipoDeOperando(c.getArg1(), tipoDe);
                    String textoOrigen = tipos.mapTexto(c.getArg1(), tipoDe);
                    if (tipoDestino != null && tipoDestino.endsWith("*")
                            && tipoOrigen != null && tipoOrigen.equals(tipoDestino.substring(0, tipoDestino.length() - 1))) {
                        // se asigna una estructura POR VALOR a un campo-puntero: hace falta su direccion.
                        textoOrigen = "&" + textoOrigen;
                    } else if (tipoOrigen != null && tipoOrigen.endsWith("*")
                            && tipoDestino != null && tipoDestino.equals(tipoOrigen.substring(0, tipoOrigen.length() - 1))) {
                        // el caso inverso: el origen es un campo-puntero y el destino es una
                        // variable/temporal POR VALOR - hace falta desreferenciar para copiar
                        // el contenido apuntado (no el puntero).
                        textoOrigen = "*" + textoOrigen;
                    }
                    sent.append("    ").append(textoDestino).append(" = ").append(textoOrigen).append(";\n");
                    idx++;
                    break;
                }
                case "++":
                case "--":
                    sent.append("    ").append(tipos.traducirAccesoC(c.getResultado(), tipoDe)).append(op).append(";\n");
                    idx++;
                    break;
                default:
                    if ("-u".equals(op) || "!".equals(op)) {
                        // '-u' es el codigo interno; el simbolo C es '-' (sin la 'u').
                        String simbolo = "-u".equals(op) ? "-" : op;
                        sent.append("    ").append(c.getResultado()).append(" = ").append(simbolo).append(tipos.mapTexto(c.getArg1(), tipoDe)).append(";\n");
                    } else if (("==".equals(op) || "!=".equals(op))
                            && "char*".equals(tipos.tipoDeOperando(c.getArg1(), tipoDe))
                            && "char*".equals(tipos.tipoDeOperando(c.getArg2(), tipoDe))) {
                        // Dos cadenas se comparan por contenido (strcmp), no por
                        // direccion de puntero.
                        String comparacion = "strcmp(" + tipos.mapTexto(c.getArg1(), tipoDe) + ", " + tipos.mapTexto(c.getArg2(), tipoDe) + ") " + ("==".equals(op) ? "== 0" : "!= 0");
                        sent.append("    ").append(c.getResultado()).append(" = (").append(comparacion).append(");\n");
                    } else {
                        sent.append("    ").append(c.getResultado()).append(" = ")
                                .append(tipos.mapTexto(c.getArg1(), tipoDe)).append(" ").append(op).append(" ").append(tipos.mapTexto(c.getArg2(), tipoDe)).append(";\n");
                    }
                    idx++;
            }
        }
        return sent.toString();
    }

    /**
     * Emite el scanf() de un 'leer()'. Para %d/%lf, un valor no numerico
     * (ej. el usuario escribe una letra) hace que scanf falle SIN consumir
     * ese caracter del buffer de entrada - si simplemente se reintentara el
     * mismo scanf, se leeria una y otra vez el mismo caracter invalido para
     * siempre (bucle infinito, sin que el programa se quede esperando una
     * entrada nueva). Por eso el scanf va en un 'while': mientras falle,
     * se descarta el resto de la linea caracter por caracter (getchar()
     * hasta '\n' o EOF) y se reintenta - asi el programa si vuelve a
     * bloquearse esperando una entrada valida, en vez de girar en vacio.
     * '%c' no necesita esto: cualquier caracter es una entrada valida.
     */
    private void emitirScanf(StringBuilder sent, String tipoC, String destinoTexto) {
        String formato = tipos.formatoScanf(tipoC);
        if ("%d".equals(formato) || "%lf".equals(formato)) {
            sent.append("    while (scanf(\"").append(formato).append("\", &").append(destinoTexto).append(") != 1) { ")
                    .append("int c3d_ch; while ((c3d_ch = getchar()) != '\\n' && c3d_ch != EOF) {} }\n");
        } else {
            sent.append("    scanf(\"").append(formato).append("\", &").append(destinoTexto).append(");\n");
        }
    }
}
