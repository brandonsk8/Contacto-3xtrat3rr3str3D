package com.company.backend;

import com.company.ir.Cuarteta;
import com.company.ir.TablaCuartetas;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backend "cuartetas -> C usando SOLO etiquetas".
 *
 * Regla del proyecto: el C generado no puede definir funciones propias;
 * todo el control de flujo va con etiquetas y 'goto'. Por eso se emite un
 * unico 'int main(void)' donde:
 *  - cada funcion de usuario es un bloque que empieza en la etiqueta
 *    'func_nombre:' y termina en 'func_nombre_fin:';
 *  - las variables, parametros y temporales de cada funcion son variables
 *    de ese main con prefijo 'funcion__' (sin colisiones entre funciones);
 *  - una llamada guarda el marco de la funcion que llama en pilas de
 *    arreglos (pila_i / pila_d / pila_p), copia los argumentos a los
 *    parametros, apila un numero de retorno y salta con 'goto'; al final
 *    de la funcion un 'switch' sobre ese numero salta de vuelta a la
 *    etiqueta 'ret_N' (asi funciona la recursion sin funciones de C);
 *  - un arreglo de N dimensiones es un bloque plano (calloc) mas una
 *    variable por dimension: a[i][j] -> a[i * a__d1 + j].
 *
 * Solo se usa la biblioteca estandar (printf/scanf/calloc/strcmp...),
 * nunca una funcion definida por el programa traducido.
 * Pendiente (fase 2): clases, estructuras y objetos. Lo no soportado no
 * genera C invalido: la funcion se omite y queda en getNotas().
 */
public class GeneradorCEtiquetas {

        private static final int MAX_ARGS = 16;
    private static final int PROFUNDIDAD_PILA = 1 << 22;

    /** Estado de UNA pasada de emision (se repite si hay que descartar funciones). */
    private static final class Pasada {
        final Map<String, String> cuerpos = new LinkedHashMap<>();
        final Map<String, List<Integer>> retornosDe = new LinkedHashMap<>();
        int siguienteRetorno = 1;
        int siguienteLectura = 1;
        boolean usaLectura = false;
    }

    private final TablaSimbolosGlobal global;
    private final List<String> notas = new ArrayList<>();
    private final ModeloPrograma modelo;

    public GeneradorCEtiquetas(TablaSimbolosGlobal global) {
        this.global = global;
        this.modelo = new ModeloPrograma(global, notas, "C con etiquetas");
    }

    public List<String> getNotas() {
        return notas;
    }

    // ------------------------------------------------------------------
    // Orquestacion
    // ------------------------------------------------------------------

    public String generar(String nombrePrograma, TablaCuartetas tabla) {
        notas.clear();
        Map<String, ModeloPrograma.Fn> fns = modelo.construir(tabla.getCuartetas());

        // Emision; si una funcion falla (o llama a una omitida) se descarta y se repite
        Pasada pasada;
        boolean hayCambios;
        do {
            pasada = new Pasada();
            hayCambios = false;
            for (ModeloPrograma.Fn f : new ArrayList<>(fns.values())) {
                try {
                    pasada.cuerpos.put(f.nombre, emitirFuncion(f, fns, pasada));
                } catch (ModeloPrograma.NoSoportado e) {
                    notas.add("funcion '" + f.nombre + "' (C con etiquetas): " + e.getMessage() + " - pendiente");
                    fns.remove(f.nombre);
                    hayCambios = true;
                    break;
                }
            }
        } while (hayCambios);

        return ensamblar(nombrePrograma, fns, pasada);
    }

    // ------------------------------------------------------------------
    // Nombres y texto de operandos
    // ------------------------------------------------------------------

    private String pref(ModeloPrograma.Fn f, String v) {
        return f.nombre + "__" + v;
    }

    private String dimVar(ModeloPrograma.Fn f, String base, int k) {
        return pref(f, base) + "__d" + k;
    }

    /** Operando de cuarteta -> expresion C usando los nombres con prefijo; arreglos "a[i][j]" se aplanan. */
    private String r(ModeloPrograma.Fn f, String t) {
        if (t == null) {
            return "";
        }
        if (TiposC.LITERALES_VERDADERO.contains(t)) {
            return "1";
        }
        if (TiposC.LITERALES_FALSO.contains(t)) {
            return "0";
        }
        if ("null".equals(t)) {
            return "NULL";
        }
        if (TiposC.ENTERO_LIT.matcher(t).matches() || TiposC.DECIMAL_LIT.matcher(t).matches()
                || TiposC.CARACTER_LIT.matcher(t).matches() || TiposC.CADENA_LIT.matcher(t).matches()) {
            return t;
        }
        int corchete = t.indexOf('[');
        if (corchete > 0 && t.endsWith("]")) {
            String base = t.substring(0, corchete);
            Integer d = f.dims.get(base);
            List<String> idx = modelo.indices(t);
            if (d == null || d != idx.size()) {
                throw new ModeloPrograma.NoSoportado("acceso a '" + t + "' (se necesita un indice por dimension)");
            }
            String acc = r(f, idx.get(0));
            for (int k = 1; k < idx.size(); k++) {
                acc = "(" + acc + ") * " + dimVar(f, base, k) + " + " + r(f, idx.get(k));
            }
            return pref(f, base) + "[" + acc + "]";
        }
        if (f.tipo.containsKey(t)) {
            return pref(f, t);
        }
        throw new ModeloPrograma.NoSoportado("operando '" + t + "' no soportado (campo, objeto o nombre desconocido)");
    }

    private String ctipoDecl(ModeloPrograma.Fn f, String v) {
        String t = ModeloPrograma.cTipo(f.tipo.get(v));
        return f.dims.containsKey(v) ? t + "*" : t;
    }

    private static String inicial(String tipoDecl) {
        return tipoDecl.endsWith("*") ? "NULL" : "0";
    }

    private static String pilaDe(String tipoDecl) {
        if ("double".equals(tipoDecl)) {
            return "d";
        }
        return tipoDecl.endsWith("*") ? "p" : "i";
    }

    // ------------------------------------------------------------------
    // Emision de una funcion
    // ------------------------------------------------------------------

    private String emitirFuncion(ModeloPrograma.Fn f, Map<String, ModeloPrograma.Fn> fns, Pasada pas) {
        StringBuilder sb = new StringBuilder();
        String etiquetaIni = f.esMain ? "inicio_main" : "func_" + f.nombre;
        sb.append(etiquetaIni).append(": ;\n");

        List<Cuarteta> cuerpo = f.cuerpo;
        List<String> pendientes = new ArrayList<>();
        int idx = 0;
        while (idx < cuerpo.size()) {
            Cuarteta c = cuerpo.get(idx);
            String op = c.getOperador();
            switch (op) {
                case "label":
                    sb.append(c.getResultado()).append(": ;\n");
                    break;
                case "goto":
                    sb.append("    goto ").append(c.getResultado()).append(";\n");
                    break;
                case "if_false":
                    sb.append("    if (!(").append(r(f, c.getArg1())).append(")) goto ").append(c.getResultado()).append(";\n");
                    break;
                case "if_true":
                    sb.append("    if (").append(r(f, c.getArg1())).append(") goto ").append(c.getResultado()).append(";\n");
                    break;
                case "return":
                    emitirReturn(sb, f, c);
                    break;
                case "print":
                    emitirPrint(sb, f, c.getArg1(), "nl".equals(c.getResultado()));
                    break;
                case "read":
                    idx += emitirLectura(sb, f, cuerpo, idx, pas) - 1;
                    break;
                case "param":
                    pendientes.add(c.getArg1());
                    break;
                case "call":
                    emitirLlamada(sb, f, c, pendientes, fns, pas);
                    break;
                case "new_array": {
                    List<String> tams = new ArrayList<>();
                    for (String s : c.getArg2().split(",")) {
                        tams.add(r(f, s.trim()));
                    }
                    reservarArreglo(sb, f, c.getResultado(), tams);
                    break;
                }
                case "concat":
                    emitirConcat(sb, f, c);
                    break;
                case "new_obj": {
                    ModeloPrograma.Layout l = modelo.layoutDe(c.getArg1());
                    String d = r(f, c.getResultado());
                    sb.append("    ").append(d).append(" = (char*)calloc(").append(l.slots).append(", 8);\n");
                    if (l.id != 0) {
                        sb.append("    *(int*)").append(d).append(" = ").append(l.id).append(";\n");
                    }
                    break;
                }
                case "len":
                    sb.append("    ").append(r(f, c.getResultado())).append(" = ").append(dimVar(f, c.getArg1(), Integer.parseInt(c.getArg2()))).append(";\n");
                    break;
                case "classid":
                    sb.append("    ").append(r(f, c.getResultado())).append(" = *(int*)").append(r(f, c.getArg1())).append(";\n");
                    break;
                case "getf":
                    emitirLecturaCampo(sb, f, c);
                    break;
                case "setf":
                    emitirEscrituraCampo(sb, f, c);
                    break;
                case "=":
                    emitirAsignacion(sb, f, c);
                    break;
                case "++":
                case "--":
                    sb.append("    ").append(r(f, c.getResultado())).append(" = ").append(r(f, c.getResultado()))
                            .append("++".equals(op) ? " + 1;\n" : " - 1;\n");
                    break;
                default:
                    emitirOperacion(sb, f, c);
            }
            idx++;
        }

        if (!f.esMain) {
            sb.append("func_").append(f.nombre).append("_fin: ;\n");
            sb.append("    switch (pila_ret[--sp_r]) {\n");
            // los 'case' se completan en ensamblar(), cuando ya se conocen todos los sitios de retorno
            sb.append("@@CASOS_").append(f.nombre).append("@@");
            sb.append("        default: goto fin_programa;\n    }\n");
        }
        return sb.toString();
    }

    private ModeloPrograma.Campo campoDeOperando(String spec) {
        int p = spec.indexOf('.');
        return modelo.campoDe(spec.substring(0, p), spec.substring(p + 1));
    }

    private void emitirLecturaCampo(StringBuilder sb, ModeloPrograma.Fn f, Cuarteta c) {
        ModeloPrograma.Campo k = campoDeOperando(c.getArg2());
        String obj = r(f, c.getArg1());
        String dest = r(f, c.getResultado());
        String t = ModeloPrograma.cTipo(k.tipo);
        sb.append("    ").append(dest).append(" = *(").append(t).append(k.dims > 0 ? "*" : "").append("*)(").append(obj)
                .append(" + ").append(8 * k.slot).append(");\n");
        for (int d = 0; d < k.dims; d++) {
            sb.append("    ").append(dimVar(f, c.getResultado(), d)).append(" = *(int*)(").append(obj).append(" + ")
                    .append(8 * (k.slot + 1 + d)).append(");\n");
        }
    }

    private void emitirEscrituraCampo(StringBuilder sb, ModeloPrograma.Fn f, Cuarteta c) {
        ModeloPrograma.Campo k = campoDeOperando(c.getArg2());
        String obj = r(f, c.getArg1());
        String valor = c.getResultado();
        String t = ModeloPrograma.cTipo(k.tipo);
        if (k.dims > 0) {
            boolean nulo = "null".equals(valor);
            if (!nulo && (!f.dims.containsKey(valor) || f.dims.get(valor) != k.dims)) {
                throw new ModeloPrograma.NoSoportado("se guarda en el campo arreglo '" + c.getArg2() + "' algo que no es un arreglo de sus dimensiones");
            }
            sb.append("    *(").append(t).append("**)(").append(obj).append(" + ").append(8 * k.slot).append(") = ")
                    .append(nulo ? "NULL" : r(f, valor)).append(";\n");
            for (int d = 0; d < k.dims; d++) {
                sb.append("    *(int*)(").append(obj).append(" + ").append(8 * (k.slot + 1 + d)).append(") = ")
                        .append(nulo ? "0" : dimVar(f, valor, d)).append(";\n");
            }
            return;
        }
        sb.append("    *(").append(t).append("*)(").append(obj).append(" + ").append(8 * k.slot).append(") = ")
                .append(r(f, valor)).append(";\n");
    }

    private void reservarArreglo(StringBuilder sb, ModeloPrograma.Fn f, String v, List<String> tams) {
        Integer d = f.dims.get(v);
        if (d == null || d != tams.size()) {
            throw new ModeloPrograma.NoSoportado("arreglo '" + v + "': la cantidad de tamanos no coincide con sus dimensiones");
        }
        for (int k = 0; k < tams.size(); k++) {
            sb.append("    ").append(dimVar(f, v, k)).append(" = ").append(tams.get(k)).append(";\n");
        }
        StringBuilder total = new StringBuilder();
        for (int k = 0; k < tams.size(); k++) {
            total.append(k > 0 ? " * " : "").append(dimVar(f, v, k));
        }
        String elem = ModeloPrograma.cTipo(f.tipo.get(v));
        sb.append("    ").append(pref(f, v)).append(" = (").append(elem).append("*)calloc(").append(total)
                .append(", sizeof(").append(elem).append("));\n");
    }

    private void emitirReturn(StringBuilder sb, ModeloPrograma.Fn f, Cuarteta c) {
        if (c.getArg1() != null) {
            if (f.tipoRet == null) {
                throw new ModeloPrograma.NoSoportado("'retornar' con valor en una funcion sin tipo de retorno");
            }
            if (f.dimsRet > 0) {
                String a = c.getArg1();
                if (!f.dims.containsKey(a) || f.dims.get(a) != f.dimsRet) {
                    throw new ModeloPrograma.NoSoportado("retorno de arreglo que no es una variable arreglo de las mismas dimensiones");
                }
                sb.append("    ret_p = (void*)").append(pref(f, a)).append(";\n");
                for (int k = 0; k < f.dimsRet; k++) {
                    sb.append("    ret_dim[").append(k).append("] = ").append(dimVar(f, a, k)).append(";\n");
                }
            } else {
                sb.append("    ret_").append(pilaDe(ModeloPrograma.cTipo(f.tipoRet))).append(" = ").append(r(f, c.getArg1())).append(";\n");
            }
        }
        sb.append("    goto ").append(f.esMain ? "fin_programa" : "func_" + f.nombre + "_fin").append(";\n");
    }

    private void emitirPrint(StringBuilder sb, ModeloPrograma.Fn f, String operando, boolean salto) {
        String t = modelo.tipoOp(f, operando);
        String fmt;
        if ("int".equals(t)) {
            fmt = "%d";
        } else if ("double".equals(t)) {
            fmt = "%f";
        } else if ("char".equals(t)) {
            fmt = "%c";
        } else if ("char*".equals(t)) {
            fmt = "%s";
        } else {
            throw new ModeloPrograma.NoSoportado("'imprimir' de '" + operando + "' (arreglo completo, objeto o tipo desconocido)");
        }
        sb.append("    printf(\"").append(fmt).append(salto ? "\\n" : "").append("\", ").append(r(f, operando)).append(");\n");
    }

    /** @return cuartetas consumidas (1 si lee directo a la variable, 2 si le sigue la asignacion). */
    private int emitirLectura(StringBuilder sb, ModeloPrograma.Fn f, List<Cuarteta> cuerpo, int idx, Pasada pas) {
        Cuarteta c = cuerpo.get(idx);
        String destino = c.getResultado();
        int consumidas = 1;
        if (!f.tipo.containsKey(destino) && destino.indexOf('[') < 0) {
            if (idx + 1 < cuerpo.size() && "=".equals(cuerpo.get(idx + 1).getOperador())
                    && destino.equals(cuerpo.get(idx + 1).getArg1())) {
                destino = cuerpo.get(idx + 1).getResultado();
                consumidas = 2;
            } else {
                throw new ModeloPrograma.NoSoportado("'leer()' sin una asignacion inmediata a una variable");
            }
        }
        String t = modelo.tipoOp(f, destino);
        String destinoC = r(f, destino);
        if ("char".equals(t)) {
            sb.append("    scanf(\" %c\", &").append(destinoC).append(");\n");
            return consumidas;
        }
        if ("char*".equals(t)) {
            // una palabra (hasta el siguiente espacio), igual que el scanf("%255s") del compilador de referencia
            sb.append("    ").append(destinoC).append(" = (char*)malloc(256);\n");
            sb.append("    if (scanf(\"%255s\", ").append(destinoC).append(") != 1) ").append(destinoC).append("[0] = '\\0';\n");
            return consumidas;
        }
        if (!"int".equals(t) && !"double".equals(t)) {
            throw new ModeloPrograma.NoSoportado("'leer()' hacia un tipo no soportado (arreglo completo/objeto)");
        }
        pas.usaLectura = true;
        int n = pas.siguienteLectura++;
        sb.append("leer_").append(n).append(": ;\n");
        sb.append("    c3d_r = scanf(\"").append("int".equals(t) ? "%d" : "%lf").append("\", &").append(destinoC).append(");\n");
        sb.append("    if (c3d_r == 1) goto leer_ok_").append(n).append(";\n");
        sb.append("    if (c3d_r == EOF) goto fin_programa;\n");
        sb.append("leer_desc_").append(n).append(": ;\n");
        sb.append("    c3d_ch = getchar();\n");
        sb.append("    if (c3d_ch == '\\n' || c3d_ch == EOF) goto leer_").append(n).append(";\n");
        sb.append("    goto leer_desc_").append(n).append(";\n");
        sb.append("leer_ok_").append(n).append(": ;\n");
        return consumidas;
    }

    private void emitirLlamada(StringBuilder sb, ModeloPrograma.Fn f, Cuarteta c, List<String> pendientes, Map<String, ModeloPrograma.Fn> fns, Pasada pas) {
        ModeloPrograma.Fn callee = modelo.llamado(c, fns);
        int n = Integer.parseInt(c.getArg2());
        if (n != callee.params.size() || n > pendientes.size()) {
            throw new ModeloPrograma.NoSoportado("llamada a '" + callee.nombre + "' con cantidad de argumentos inconsistente");
        }
        if (n > MAX_ARGS) {
            throw new ModeloPrograma.NoSoportado("llamada con mas de " + MAX_ARGS + " argumentos");
        }
        List<String> args = new ArrayList<>(pendientes.subList(pendientes.size() - n, pendientes.size()));
        for (int k = 0; k < n; k++) {
            pendientes.remove(pendientes.size() - 1);
        }

        // 1) evaluar argumentos a la zona de paso (antes de tocar ninguna variable)
        for (int j = 0; j < n; j++) {
            String p = callee.params.get(j);
            String a = args.get(j);
            if (callee.dims.containsKey(p)) {
                if (!f.dims.containsKey(a) || !f.dims.get(a).equals(callee.dims.get(p))) {
                    throw new ModeloPrograma.NoSoportado("argumento '" + a + "' no es un arreglo de las dimensiones que espera '" + p + "'");
                }
                sb.append("    sa_p[").append(j).append("] = (void*)").append(pref(f, a)).append(";\n");
                for (int k = 0; k < callee.dims.get(p); k++) {
                    sb.append("    sa_dim[").append(j).append("][").append(k).append("] = ").append(dimVar(f, a, k)).append(";\n");
                }
            } else {
                String pila = pilaDe(ModeloPrograma.cTipo(callee.tipo.get(p)));
                String valor = r(f, a);
                sb.append("    sa_").append(pila).append("[").append(j).append("] = ").append("p".equals(pila) ? "(void*)" : "").append(valor).append(";\n");
            }
        }

        // 2) guardar el marco de quien llama (el main nunca se re-entra)
        List<String> restaurar = new ArrayList<>();
        if (!f.esMain) {
            for (Map.Entry<String, String> e : f.tipo.entrySet()) {
                String v = e.getKey();
                String td = ctipoDecl(f, v);
                String pila = pilaDe(td);
                String nombre = pref(f, v);
                sb.append("    pila_").append(pila).append("[sp_").append(pila).append("++] = ").append("p".equals(pila) ? "(void*)" : "").append(nombre).append(";\n");
                restaurar.add("    " + nombre + " = " + ("p".equals(pila) ? "(" + td + ")" : "") + "pila_" + pila + "[--sp_" + pila + "];\n");
                if (f.dims.containsKey(v)) {
                    for (int k = 0; k < f.dims.get(v); k++) {
                        sb.append("    pila_i[sp_i++] = ").append(dimVar(f, v, k)).append(";\n");
                        restaurar.add("    " + dimVar(f, v, k) + " = pila_i[--sp_i];\n");
                    }
                }
            }
        }

        // 3) pasar parametros, apilar el retorno y saltar
        for (int j = 0; j < n; j++) {
            String p = callee.params.get(j);
            if (callee.dims.containsKey(p)) {
                sb.append("    ").append(pref(callee, p)).append(" = (").append(ModeloPrograma.cTipo(callee.tipo.get(p))).append("*)sa_p[").append(j).append("];\n");
                for (int k = 0; k < callee.dims.get(p); k++) {
                    sb.append("    ").append(dimVar(callee, p, k)).append(" = sa_dim[").append(j).append("][").append(k).append("];\n");
                }
            } else {
                String td = ModeloPrograma.cTipo(callee.tipo.get(p));
                String pila = pilaDe(td);
                sb.append("    ").append(pref(callee, p)).append(" = ").append("p".equals(pila) ? "(" + td + ")" : "")
                        .append("sa_").append(pila).append("[").append(j).append("];\n");
            }
        }
        int id = pas.siguienteRetorno++;
        pas.retornosDe.computeIfAbsent(callee.nombre, k -> new ArrayList<>()).add(id);
        sb.append("    pila_ret[sp_r++] = ").append(id).append(";\n");
        sb.append("    goto func_").append(callee.nombre).append(";\n");
        sb.append("ret_").append(id).append(": ;\n");

        // 4) restaurar el marco y recoger el resultado
        for (int k = restaurar.size() - 1; k >= 0; k--) {
            sb.append(restaurar.get(k));
        }
        if (c.getResultado() != null) {
            String dest = c.getResultado();
            if (callee.dimsRet > 0) {
                sb.append("    ").append(pref(f, dest)).append(" = (").append(ModeloPrograma.cTipo(callee.tipoRet)).append("*)ret_p;\n");
                for (int k = 0; k < callee.dimsRet; k++) {
                    sb.append("    ").append(dimVar(f, dest, k)).append(" = ret_dim[").append(k).append("];\n");
                }
            } else {
                String pila = pilaDe(ModeloPrograma.cTipo(callee.tipoRet));
                sb.append("    ").append(r(f, dest)).append(" = ").append("p".equals(pila) ? "(" + ModeloPrograma.cTipo(callee.tipoRet) + ")" : "")
                        .append("ret_").append(pila).append(";\n");
            }
        }
    }

    private void emitirConcat(StringBuilder sb, ModeloPrograma.Fn f, Cuarteta c) {
        String t1 = modelo.tipoOp(f, c.getArg1());
        String t2 = modelo.tipoOp(f, c.getArg2());
        String f1 = formatoCadena(t1);
        String f2 = formatoCadena(t2);
        if (f1 == null || f2 == null) {
            throw new ModeloPrograma.NoSoportado("'concat' con un operando que no es cadena/numero/caracter");
        }
        String a = r(f, c.getArg1());
        String b = r(f, c.getArg2());
        StringBuilder largo = new StringBuilder("96");
        if ("char*".equals(t1)) {
            largo.append(" + strlen(").append(a).append(")");
        }
        if ("char*".equals(t2)) {
            largo.append(" + strlen(").append(b).append(")");
        }
        String dest = pref(f, c.getResultado());
        sb.append("    ").append(dest).append(" = (char*)malloc(").append(largo).append(");\n");
        sb.append("    sprintf(").append(dest).append(", \"").append(f1).append(f2).append("\", ").append(a).append(", ").append(b).append(");\n");
    }

    private String formatoCadena(String tipo) {
        if ("char*".equals(tipo)) {
            return "%s";
        }
        if ("int".equals(tipo)) {
            return "%d";
        }
        if ("double".equals(tipo)) {
            return "%f";
        }
        if ("char".equals(tipo)) {
            return "%c";
        }
        return null;
    }

    private void emitirAsignacion(StringBuilder sb, ModeloPrograma.Fn f, Cuarteta c) {
        String dest = c.getResultado();
        String origen = c.getArg1();
        boolean destArreglo = dest.indexOf('[') < 0 && f.dims.containsKey(dest);
        if (destArreglo) {
            if ("null".equals(origen)) {
                sb.append("    ").append(pref(f, dest)).append(" = NULL;\n");
                return;
            }
            if (!f.dims.containsKey(origen) || !f.dims.get(origen).equals(f.dims.get(dest))) {
                throw new ModeloPrograma.NoSoportado("asignacion de arreglo '" + dest + "' desde algo que no es un arreglo de las mismas dimensiones");
            }
            sb.append("    ").append(pref(f, dest)).append(" = ").append(pref(f, origen)).append(";\n");
            for (int k = 0; k < f.dims.get(dest); k++) {
                sb.append("    ").append(dimVar(f, dest, k)).append(" = ").append(dimVar(f, origen, k)).append(";\n");
            }
            return;
        }
        if (f.dims.containsKey(origen) && origen.indexOf('[') < 0) {
            throw new ModeloPrograma.NoSoportado("se asigna un arreglo completo a '" + dest + "', que no es un arreglo");
        }
        sb.append("    ").append(r(f, dest)).append(" = ").append(r(f, origen)).append(";\n");
    }

    private void emitirOperacion(StringBuilder sb, ModeloPrograma.Fn f, Cuarteta c) {
        String op = c.getOperador();
        String dest = r(f, c.getResultado());
        if ("-u".equals(op)) {
            sb.append("    ").append(dest).append(" = -").append(r(f, c.getArg1())).append(";\n");
            return;
        }
        if ("!".equals(op)) {
            sb.append("    ").append(dest).append(" = !").append(r(f, c.getArg1())).append(";\n");
            return;
        }
        String t1 = modelo.tipoOp(f, c.getArg1());
        String t2 = modelo.tipoOp(f, c.getArg2());
        String a = r(f, c.getArg1());
        String b = r(f, c.getArg2());
        if (("==".equals(op) || "!=".equals(op)) && "char*".equals(t1) && "char*".equals(t2)) {
            // una cadena nula cuenta como "" (igual que __strcmp en RISC-V)
            sb.append("    ").append(dest).append(" = (strcmp(").append(a).append(" ? ").append(a).append(" : \"\", ")
                    .append(b).append(" ? ").append(b).append(" : \"\") ").append(op).append(" 0);\n");
            return;
        }
        if (("==".equals(op) || "!=".equals(op)) && ("char*".equals(t1) ^ "char*".equals(t2))
                && ("obj".equals(t1) || "obj".equals(t2))) {
            // cadena contra null: comparacion de punteros
            sb.append("    ").append(dest).append(" = (").append(a).append(" ").append(op).append(" ").append(b).append(");\n");
            return;
        }
        if ("char*".equals(t1) || "char*".equals(t2)) {
            throw new ModeloPrograma.NoSoportado("operador '" + op + "' sobre cadenas");
        }
        if ("%".equals(op) && ("double".equals(t1) || "double".equals(t2))) {
            throw new ModeloPrograma.NoSoportado("'%' sobre valores decimales");
        }
        sb.append("    ").append(dest).append(" = ").append(a).append(" ").append(op).append(" ").append(b).append(";\n");
    }

    // ------------------------------------------------------------------
    // Ensamblado del .c
    // ------------------------------------------------------------------

    private String ensamblar(String nombrePrograma, Map<String, ModeloPrograma.Fn> fns, Pasada pas) {
        // Primero el cuerpo, para declarar solo lo que realmente se usa (sin avisos de gcc -Wall)
        StringBuilder cuerpo = new StringBuilder();
        cuerpo.append(fns.containsKey("main") ? "    goto inicio_main;\n\n" : "    goto fin_programa;\n\n");
        for (ModeloPrograma.Fn f : fns.values()) {
            if (!f.esMain) {
                cuerpo.append(completarCasos(pas.cuerpos.get(f.nombre), f.nombre, pas)).append("\n");
            }
        }
        if (fns.containsKey("main")) {
            cuerpo.append(pas.cuerpos.get("main")).append("\n");
        }
        cuerpo.append("fin_programa: ;\n    return 0;\n}\n");
        String texto = cuerpo.toString();

        StringBuilder out = new StringBuilder();
        out.append("/* Codigo generado automaticamente a partir de ").append(nombrePrograma).append(".\n");
        out.append(" * Solo etiquetas y goto: no se define ninguna funcion aparte de main. */\n");
        out.append("#include <stdio.h>\n#include <stdlib.h>\n#include <string.h>\n\n");
        out.append("int main(void) {\n");
        boolean hayLlamadas = !pas.retornosDe.isEmpty() || texto.contains("pila_ret[");
        if (hayLlamadas) {
            out.append("    /* pilas: marco de quien llama (recursion) y direcciones de retorno */\n");
            out.append("    static int pila_i[").append(PROFUNDIDAD_PILA).append("];\n");
            if (texto.contains("pila_d[")) {
                out.append("    static double pila_d[").append(PROFUNDIDAD_PILA).append("];\n");
            }
            if (texto.contains("pila_p[")) {
                out.append("    static void* pila_p[").append(PROFUNDIDAD_PILA).append("];\n");
            }
            out.append("    static int pila_ret[").append(PROFUNDIDAD_PILA).append("];\n");
            out.append("    int sp_i = 0, sp_r = 0;\n");
            if (texto.contains("sp_d")) {
                out.append("    int sp_d = 0;\n");
            }
            if (texto.contains("sp_p")) {
                out.append("    int sp_p = 0;\n");
            }
            out.append("    /* zona de paso de argumentos y valor de retorno */\n");
            if (texto.contains("sa_i[")) {
                out.append("    int sa_i[").append(MAX_ARGS).append("] = {0};\n");
            }
            if (texto.contains("sa_d[")) {
                out.append("    double sa_d[").append(MAX_ARGS).append("] = {0};\n");
            }
            if (texto.contains("sa_p[")) {
                out.append("    void* sa_p[").append(MAX_ARGS).append("] = {0};\n");
            }
            if (texto.contains("sa_dim[")) {
                out.append("    int sa_dim[").append(MAX_ARGS).append("][").append(ModeloPrograma.MAX_DIMS).append("] = {{0}};\n");
            }
            if (texto.contains("ret_i")) {
                out.append("    int ret_i = 0;\n");
            }
            if (texto.contains("ret_d")) {
                out.append("    double ret_d = 0;\n");
            }
            if (texto.contains("ret_p")) {
                out.append("    void* ret_p = NULL;\n");
            }
            if (texto.contains("ret_dim[")) {
                out.append("    int ret_dim[").append(ModeloPrograma.MAX_DIMS).append("] = {0};\n");
            }
        }
        if (pas.usaLectura) {
            out.append("    int c3d_r = 0, c3d_ch = 0;\n");
        }
        out.append("\n    /* variables de cada funcion */\n");
        for (ModeloPrograma.Fn f : fns.values()) {
            for (String v : f.tipo.keySet()) {
                String td = ctipoDecl(f, v);
                if (texto.contains(pref(f, v))) {
                    out.append("    ").append(td).append(" ").append(pref(f, v)).append(" = ").append(inicial(td)).append(";\n");
                }
                if (f.dims.containsKey(v)) {
                    for (int k = 0; k < f.dims.get(v); k++) {
                        if (texto.contains(dimVar(f, v, k))) {
                            out.append("    int ").append(dimVar(f, v, k)).append(" = 0;\n");
                        }
                    }
                }
            }
        }
        out.append("\n").append(texto);
        return out.toString();
    }

    private String completarCasos(String cuerpo, String nombre, Pasada pas) {
        StringBuilder casos = new StringBuilder();
        for (int id : pas.retornosDe.getOrDefault(nombre, List.of())) {
            casos.append("        case ").append(id).append(": goto ret_").append(id).append(";\n");
        }
        return cuerpo.replace("@@CASOS_" + nombre + "@@", casos.toString());
    }
}
