package com.company.backend;

import com.company.ir.Cuarteta;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolosGlobal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pase A del backend: recorre el cuerpo de una funcion/constructor/metodo
 * y le asigna un tipo C a cada temporal (t0, t1, ...) y a cualquier
 * variable no vista todavia, antes de que EmisorSentencias (Pase B)
 * traduzca cada cuarteta a su sentencia C real. Separado de ese Pase B
 * porque necesita conocer TODOS los tipos antes de emitir una sola linea
 * (una declaracion en C va antes de su uso, pero una cuarteta puede usar
 * un temporal antes de la instruccion que "explicaria" su tipo si se
 * leyera aislada).
 */
final class InferenciaTipos {

    private static final Set<String> OPS_ARITMETICOS = Set.of("+", "-", "*", "/", "%");
    private static final Set<String> OPS_COMPARACION = Set.of("<", ">", "<=", ">=", "==", "!=");
    private static final Set<String> OPS_LOGICOS = Set.of("&&", "||");

    private final TablaSimbolosGlobal global;
    private final TiposC tipos;
    private final List<String> notas;

    InferenciaTipos(TablaSimbolosGlobal global, TiposC tipos, List<String> notas) {
        this.global = global;
        this.tipos = tipos;
        this.notas = notas;
    }

    boolean inferir(String nombreFuncion, List<Cuarteta> cuerpo, Map<String, String> tipoDe,
                     Set<String> firmasSoportadas, Set<String> clasesSoportadas) {
        for (Cuarteta c : cuerpo) {
            String op = c.getOperador();
            switch (op) {
                case "label":
                case "goto":
                case "if_false":
                case "if_true":
                case "return":
                case "print":
                case "param":
                case "read":
                case "++":
                case "--":
                    break; // no declaran ningun nombre nuevo ('++'/'--' reusan una variable ya declarada;
                           // el temporal de 'read' se fusiona con la asignacion siguiente)
                case "call": {
                    String arg1 = c.getArg1();
                    if (arg1 != null && arg1.indexOf('.') > 0) {
                        // Llamada a metodo (explicita o implicita con 'this.').
                        int punto = arg1.lastIndexOf('.');
                        String nombreMetodo = arg1.substring(punto + 1);
                        String textoBase = arg1.substring(0, punto);
                        String tipoBase = tipos.tipoDeOperando(textoBase, tipoDe);
                        String claseNombre = tipos.nombreTipoDesdeC(tipoBase);
                        SimboloClase clase = claseNombre != null ? global.buscarClase(claseNombre) : null;
                        if (clase == null || !clasesSoportadas.contains(claseNombre)) {
                            notas.add("funcion '" + nombreFuncion + "': llamada a metodo '" + nombreMetodo + "' sobre un objeto de tipo no soportado - pendiente");
                            return false;
                        }
                        int n = Integer.parseInt(c.getArg2());
                        SimboloInvocable metodo = null;
                        for (SimboloInvocable candidato : clase.buscarMetodos(nombreMetodo)) {
                            if (candidato.getParametros().size() == n) {
                                metodo = candidato;
                                break;
                            }
                        }
                        if (metodo == null) {
                            notas.add("funcion '" + nombreFuncion + "': no se encontro el metodo '" + nombreMetodo + "' con " + n + " parametro(s) en '" + claseNombre + "'");
                            return false;
                        }
                        if (c.getResultado() != null) {
                            String tr = tipos.tipoC(metodo.getTipoRetorno());
                            if (tr == null) {
                                notas.add("funcion '" + nombreFuncion + "': el metodo '" + claseNombre + "." + nombreMetodo + "' retorna un tipo no soportado - pendiente");
                                return false;
                            }
                            tipoDe.putIfAbsent(c.getResultado(), tr);
                        }
                    } else {
                        if (arg1 != null && !firmasSoportadas.contains(arg1)) {
                            notas.add("funcion '" + nombreFuncion + "': llama a '" + arg1 + "', cuya firma no es soportada todavia - pendiente");
                            return false;
                        }
                        if (c.getResultado() != null) {
                            SimboloInvocable llamada = global.buscarFuncion(arg1);
                            tipoDe.putIfAbsent(c.getResultado(), tipos.tipoC(llamada.getTipoRetorno()));
                        }
                    }
                    break;
                }
                case "new": {
                    String claseNombre = c.getArg1();
                    if (!clasesSoportadas.contains(claseNombre)) {
                        notas.add("funcion '" + nombreFuncion + "': 'new " + claseNombre + "' - la clase no es soportada todavia (campo de tipo arreglo) - pendiente");
                        return false;
                    }
                    if (c.getResultado() != null) {
                        tipoDe.putIfAbsent(c.getResultado(), "struct " + claseNombre + "*");
                    }
                    break;
                }
                case "concat": {
                    String t1 = tipos.tipoDeOperando(c.getArg1(), tipoDe);
                    String t2 = tipos.tipoDeOperando(c.getArg2(), tipoDe);
                    if (!tipos.esConvertibleACadena(t1) || !tipos.esConvertibleACadena(t2)) {
                        notas.add("funcion '" + nombreFuncion + "': 'concat' con un operando de tipo no soportado (arreglo, estructura u objeto) - pendiente");
                        return false;
                    }
                    if (c.getResultado() != null) {
                        tipoDe.putIfAbsent(c.getResultado(), "char*");
                    }
                    break;
                }
                case "new_array": {
                    String nombreTipoBase = c.getArg1();
                    String tamanios = c.getArg2();
                    boolean multiDimensional = tamanios != null && tamanios.contains(",");
                    String baseC = tipos.tipoCPrimitivoDeArreglo(nombreTipoBase);
                    if (baseC == null || multiDimensional) {
                        notas.add("funcion '" + nombreFuncion + "': arreglo de '" + nombreTipoBase + "'"
                                + (multiDimensional ? " con mas de 1 dimension" : "")
                                + " no soportado todavia (solo arreglos de 1 dimension de entero/decimal/booleano) - pendiente");
                        return false;
                    }
                    if (c.getResultado() != null) {
                        tipoDe.putIfAbsent(c.getResultado(), baseC + "*");
                    }
                    break;
                }
                case "=":
                    if (c.getResultado().indexOf('.') > 0 || c.getResultado().indexOf('[') > 0) {
                        // 'nodo.campo = ...' / 'this.campo = ...' / 'arr[i] = ...':
                        // la base ya esta declarada (por 'new_array' o como
                        // parametro), no hace falta registrar nada nuevo.
                        break;
                    }
                    if (!tipoDe.containsKey(c.getResultado())) {
                        String t = tipos.tipoDeOperando(c.getArg1(), tipoDe);
                        if (t == null) {
                            notas.add("funcion '" + nombreFuncion + "': no se pudo determinar el tipo de '" + c.getResultado() + "' (variable declarada en un bloque anidado y nunca asignada antes de usarse) - pendiente");
                            return false;
                        }
                        // Una variable "suelta" de tipo estructura siempre va por valor
                        // (solo sus campos son punteros); si el lado derecho vino de un
                        // campo-estructura hay que recortarle el '*'. Una variable de tipo
                        // clase, en cambio, siempre es puntero - ahi no se recorta nada.
                        if (t.endsWith("*")) {
                            String nombreTipo = tipos.nombreTipoDesdeC(t);
                            boolean esEstructura = nombreTipo != null && global.buscarEstructura(nombreTipo) != null;
                            tipoDe.put(c.getResultado(), esEstructura ? t.substring(0, t.length() - 1) : t);
                        } else {
                            tipoDe.put(c.getResultado(), t);
                        }
                    }
                    break;
                default:
                    if ("-u".equals(op) || "!".equals(op)) {
                        // '-u' es negacion aritmetica unaria ('-x'); '-' a secas
                        // siempre es binario, por eso se compara contra '-u'.
                        String t = tipos.tipoDeOperando(c.getArg1(), tipoDe);
                        if (t == null) {
                            notas.add("funcion '" + nombreFuncion + "': no se pudo determinar el tipo del operando de '" + op + "'");
                            return false;
                        }
                        tipoDe.putIfAbsent(c.getResultado(), "!".equals(op) ? "int" : t);
                    } else if (OPS_COMPARACION.contains(op) || OPS_LOGICOS.contains(op)) {
                        tipoDe.putIfAbsent(c.getResultado(), "int"); // booleano -> int en C
                    } else if (OPS_ARITMETICOS.contains(op)) {
                        String t1 = tipos.tipoDeOperando(c.getArg1(), tipoDe);
                        String t2 = tipos.tipoDeOperando(c.getArg2(), tipoDe);
                        if (t1 == null || t2 == null) {
                            notas.add("funcion '" + nombreFuncion + "': no se pudo determinar el tipo de los operandos de '" + op + "'");
                            return false;
                        }
                        tipoDe.putIfAbsent(c.getResultado(), ("double".equals(t1) || "double".equals(t2)) ? "double" : "int");
                    } else {
                        notas.add("funcion '" + nombreFuncion + "': operador '" + op + "' no soportado todavia (arreglos/concat quedan pendientes)");
                        return false;
                    }
            }
        }
        return true;
    }
}
