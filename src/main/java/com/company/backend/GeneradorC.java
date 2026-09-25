package com.company.backend;

import com.company.ir.Cuarteta;
import com.company.ir.TablaCuartetas;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloEstructura;
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
 * Backend "cuartetas -> C": orquesta la traduccion de las cuartetas que
 * dejan YVisitor, ZetarianoAnalizador y PigLatinAnalizador al .c
 * equivalente. El trabajo se reparte en 3 clases (todas en este mismo
 * paquete com.company.backend, de ahi el nombre):
 *
 *   - TiposC: que tipo C le corresponde a cada Tipo semantico o texto de
 *     cuarteta (la usan las otras 2 y esta clase).
 *   - InferenciaTipos: Pase A, le asigna un tipo C a cada temporal y
 *     variable antes de emitir nada.
 *   - EmisorSentencias: Pase B, ya con los tipos resueltos, traduce cada
 *     cuarteta a su sentencia C.
 *
 * Esta clase se queda con lo que ninguna de las otras 3 puede hacer sola:
 * decidir que funcion/constructor/metodo/clase/estructura SI se puede
 * traducir (los prefiltros calcular*Soportad*s), armar la firma y las
 * declaraciones de cada funcion, y ensamblar el .c final (includes,
 * helpers de cadena, structs, prototipos, cuerpos).
 *
 * Cubre: tipos primitivos (entero/decimal/caracter/booleano), estructuras
 * de Y? (planas, anidadas y auto-referenciadas), clases de Zetariano
 * (constructores, metodos, 'this', 'new', llamadas encadenadas), cadenas
 * con 'concat', y arreglos de 1 dimension de entero/decimal/booleano:
 * campos de estructura y variables locales de Y? con tamaño literal
 * ('entero arr[10]'), y variables locales o campos de clase de Zetariano
 * / Pig Latin creados con 'new tipo[n]' o 'series' (reservados con
 * malloc() en tiempo de ejecucion). Quedan pendientes: arreglos
 * multidimensionales, de cadena/caracter/estructura/clase, arreglos-
 * parametro de Zetariano, y arreglos de Y? cuyo tamaño no sea un entero
 * literal.
 *
 * Si algo no esta soportado (arreglo, cadena en un lugar no cubierto,
 * etc.) no se genera C invalido: se omite esa funcion/clase y queda
 * registrado en getNotas().
 *
 * Solo conoce TablaCuartetas y TablaSimbolosGlobal (para las firmas) - no
 * ve nada de los 3 Visitor ni del AST.
 */
public class GeneradorC {

    // Helpers para 'concat'. Solo se agregan al .c los que se usan de
    // verdad (ver helpersCadenaUsados) para no dejar funciones 'static'
    // sin usar (gcc -Wall se queja de eso).
    private static final String HELPER_CONCAT =
            "static char* c3d_concat(const char* a, const char* b) {\n" +
                    "    size_t len = strlen(a) + strlen(b) + 1;\n" +
                    "    char* r = malloc(len);\n" +
                    "    snprintf(r, len, \"%s%s\", a, b);\n" +
                    "    return r;\n" +
                    "}\n";
    private static final String HELPER_ENTERO_A_CADENA =
            "static char* c3d_entero_a_cadena(int v) {\n" +
                    "    char* buf = malloc(16);\n" +
                    "    snprintf(buf, 16, \"%d\", v);\n" +
                    "    return buf;\n" +
                    "}\n";
    private static final String HELPER_DECIMAL_A_CADENA =
            "static char* c3d_decimal_a_cadena(double v) {\n" +
                    "    char* buf = malloc(48);\n" +
                    "    snprintf(buf, 48, \"%f\", v);\n" +
                    "    return buf;\n" +
                    "}\n";
    private static final String HELPER_CARACTER_A_CADENA =
            "static char* c3d_caracter_a_cadena(char v) {\n" +
                    "    char* buf = malloc(2);\n" +
                    "    buf[0] = v;\n" +
                    "    buf[1] = '\\0';\n" +
                    "    return buf;\n" +
                    "}\n";

    private final TablaSimbolosGlobal global;
    private final List<String> notas = new ArrayList<>();
    /** Helpers de cadena usados en la ultima llamada a generar(). */
    private final Set<String> helpersCadenaUsados = new LinkedHashSet<>();

    private final TiposC tipos;
    private final InferenciaTipos inferenciaTipos;
    private final EmisorSentencias emisorSentencias;

    public GeneradorC(TablaSimbolosGlobal global) {
        this.global = global;
        this.tipos = new TiposC(global);
        this.inferenciaTipos = new InferenciaTipos(global, tipos, notas);
        this.emisorSentencias = new EmisorSentencias(tipos, notas);
    }

    /** Funciones/constructores/metodos que se omitieron del .c y por que. */
    public List<String> getNotas() {
        return notas;
    }

    /** tabla trae todas las cuartetas del programa, una funcion/constructor/metodo detras de otro. */
    public String generar(String nombrePrograma, TablaCuartetas tabla) {
        helpersCadenaUsados.clear();
        List<Cuarteta> todas = tabla.getCuartetas();
        Set<String> estructurasNoSoportadas = calcularEstructurasNoSoportadas();
        Set<String> firmasSoportadas = calcularFirmasSoportadas(todas, estructurasNoSoportadas);
        Set<String> clasesSoportadas = calcularClasesSoportadas();

        // Arma de antemano el nombre de etiqueta de cada ctor/metodo de cada
        // clase ("ctor_Clase_N" / "metodo_Clase_nombre_N"), para despues
        // buscarlo directo en vez de parsear el texto de la etiqueta.
        Map<String, SimboloClase> claseDeCtor = new LinkedHashMap<>();
        Map<String, SimboloInvocable> ctorPorEtiqueta = new LinkedHashMap<>();
        Map<String, SimboloClase> claseDeMetodo = new LinkedHashMap<>();
        Map<String, SimboloInvocable> metodoPorEtiqueta = new LinkedHashMap<>();
        for (SimboloClase clase : global.getClases().values()) {
            for (SimboloInvocable ctor : clase.getConstructores()) {
                String etiqueta = "ctor_" + clase.getNombre() + "_" + ctor.getParametros().size();
                claseDeCtor.put(etiqueta, clase);
                ctorPorEtiqueta.put(etiqueta, ctor);
            }
            for (List<SimboloInvocable> sobrecargas : clase.getMetodos().values()) {
                for (SimboloInvocable metodo : sobrecargas) {
                    String etiqueta = "metodo_" + clase.getNombre() + "_" + metodo.getNombre() + "_" + metodo.getParametros().size();
                    claseDeMetodo.put(etiqueta, clase);
                    metodoPorEtiqueta.put(etiqueta, metodo);
                }
            }
        }

        Map<String, String> definiciones = new LinkedHashMap<>();
        int i = 0;
        while (i < todas.size()) {
            Cuarteta c = todas.get(i);
            if (esInicioDeFuncion(c)) {
                String etiqueta = c.getResultado();
                int fin = i + 1;
                while (fin < todas.size() && !esInicioDeFuncion(todas.get(fin))) {
                    fin++;
                }
                List<Cuarteta> cuerpo = todas.subList(i + 1, fin);

                if (etiqueta.startsWith("func_")) {
                    String nombre = etiqueta.substring("func_".length());
                    if (firmasSoportadas.contains(nombre)) {
                        String codigo = generarFuncion(nombre, cuerpo, firmasSoportadas, clasesSoportadas, estructurasNoSoportadas);
                        if (codigo != null) {
                            definiciones.put(etiqueta, codigo);
                        }
                    } else {
                        notas.add("funcion '" + nombre + "': firma con arreglo, clase o cadena - pendiente");
                    }
                } else if (ctorPorEtiqueta.containsKey(etiqueta)) {
                    SimboloClase clase = claseDeCtor.get(etiqueta);
                    SimboloInvocable ctor = ctorPorEtiqueta.get(etiqueta);
                    if (clasesSoportadas.contains(clase.getNombre())) {
                        String codigo = generarConstructor(clase, ctor, cuerpo, firmasSoportadas, clasesSoportadas);
                        if (codigo != null) {
                            definiciones.put(etiqueta, codigo);
                        }
                    } else {
                        notas.add("constructor de '" + clase.getNombre() + "': la clase tiene un campo de tipo no soportado (cadena o arreglo) - pendiente");
                    }
                } else if (metodoPorEtiqueta.containsKey(etiqueta)) {
                    SimboloClase clase = claseDeMetodo.get(etiqueta);
                    SimboloInvocable metodo = metodoPorEtiqueta.get(etiqueta);
                    if (clasesSoportadas.contains(clase.getNombre())) {
                        String codigo = generarMetodo(clase, metodo, cuerpo, firmasSoportadas, clasesSoportadas);
                        if (codigo != null) {
                            definiciones.put(etiqueta, codigo);
                        }
                    } else {
                        notas.add("metodo '" + clase.getNombre() + "." + metodo.getNombre() + "': la clase tiene un campo de tipo no soportado (cadena o arreglo) - pendiente");
                    }
                } else if ("main".equals(etiqueta)) {
                    String codigo = generarMain(cuerpo, firmasSoportadas, clasesSoportadas);
                    if (codigo != null) {
                        definiciones.put(etiqueta, codigo);
                    }
                }
                i = fin;
            } else {
                i++;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("/* Codigo generado automaticamente a partir de ").append(nombrePrograma).append(". */\n");
        out.append("#include <stdio.h>\n");
        out.append("#include <stdlib.h>\n");
        out.append("#include <string.h>\n\n");
        // Solo se agregan los helpers de 'concat' que realmente se usaron
        // (helpersCadenaUsados, ya poblado por el bucle de arriba al
        // generar cada funcion/constructor/metodo) - ver el Javadoc de
        // HELPER_CONCAT para el motivo (evitar -Wunused-function con gcc -Wall).
        if (helpersCadenaUsados.contains("concat")) {
            out.append(HELPER_CONCAT);
        }
        if (helpersCadenaUsados.contains("entero")) {
            out.append(HELPER_ENTERO_A_CADENA);
        }
        if (helpersCadenaUsados.contains("decimal")) {
            out.append(HELPER_DECIMAL_A_CADENA);
        }
        if (helpersCadenaUsados.contains("caracter")) {
            out.append(HELPER_CARACTER_A_CADENA);
        }
        if (!helpersCadenaUsados.isEmpty()) {
            out.append("\n");
        }

        for (String defStruct : definirEstructuras(estructurasNoSoportadas)) {
            out.append(defStruct).append("\n");
        }
        for (String defClase : definirClases(clasesSoportadas)) {
            out.append(defClase).append("\n");
        }

        for (Map.Entry<String, String> def : definiciones.entrySet()) {
            String cuerpo = def.getValue();
            String firma = cuerpo.substring(0, cuerpo.indexOf('{')).trim();
            out.append(firma).append(";\n");
        }
        out.append("\n");
        for (String cuerpo : definiciones.values()) {
            out.append(cuerpo).append("\n");
        }
        return out.toString();
    }

    private boolean esInicioDeFuncion(Cuarteta c) {
        if (!"label".equals(c.getOperador()) || c.getResultado() == null) {
            return false;
        }
        String r = c.getResultado();
        // "main" es la etiqueta del cuerpo del propio .pig (el punto de
        // entrada real) - cuenta como inicio de "funcion" igual que las
        // otras, si no su cuerpo se mezcla con el de la funcion anterior.
        return r.startsWith("func_") || r.startsWith("ctor_") || r.startsWith("metodo_") || "main".equals(r);
    }

    /** Prefiltro rapido: solo mira la FIRMA (parametros + retorno) de cada funcion Y?, no su cuerpo. */
    private Set<String> calcularFirmasSoportadas(List<Cuarteta> todas, Set<String> estructurasNoSoportadas) {
        Set<String> ok = new LinkedHashSet<>();
        for (Cuarteta c : todas) {
            if (!esInicioDeFuncion(c) || !c.getResultado().startsWith("func_")) {
                continue;
            }
            String nombre = c.getResultado().substring("func_".length());
            SimboloInvocable funcion = global.buscarFuncion(nombre);
            if (funcion == null || tipos.tipoCTipoY(funcion.getTipoRetorno(), estructurasNoSoportadas) == null) {
                continue;
            }
            boolean todosPrimitivos = true;
            for (Simbolo parametro : funcion.getParametros()) {
                if (tipos.tipoCTipoY(parametro.getTipo(), estructurasNoSoportadas) == null) {
                    todosPrimitivos = false;
                    break;
                }
            }
            if (todosPrimitivos) {
                ok.add(nombre);
            }
        }
        return ok;
    }

    /**
     * Estructuras de Y? con un campo de tipo arreglo que NO se puede
     * traducir a C todavia (mas de 1 dimension, tipo base no primitivo, o
     * tamaño que no es un entero literal). Cualquier funcion que las
     * use (por parametro, retorno o variable local) tambien se excluye,
     * para no generar un .c con un campo invalido o un tipo incompleto.
     */
    private Set<String> calcularEstructurasNoSoportadas() {
        Set<String> noSoportadas = new LinkedHashSet<>();
        for (SimboloEstructura estructura : global.getEstructuras().values()) {
            for (Simbolo campo : estructura.getCampos().values()) {
                if (campo.getTipo().getCategoria() == Tipo.Categoria.ARREGLO && !tipos.esArregloYSoportado(campo)) {
                    noSoportadas.add(estructura.getNombre());
                    notas.add("estructura '" + estructura.getNombre() + "': el campo '" + campo.getNombre()
                            + "' es un arreglo no soportado (solo arreglos de 1 dimension de entero/decimal/booleano, con tamaño literal) - pendiente");
                    break;
                }
            }
        }
        return noSoportadas;
    }

    /** Prefiltro analogo, pero para clases de Zetariano: solo mira que TODOS sus campos tengan un tipo C soportado. */
    private Set<String> calcularClasesSoportadas() {
        Set<String> ok = new LinkedHashSet<>();
        clases:
        for (SimboloClase clase : global.getClases().values()) {
            for (Simbolo campo : clase.getCampos().values()) {
                if (tipos.tipoCCampo(campo.getTipo()) == null) {
                    continue clases;
                }
            }
            ok.add(clase.getNombre());
        }
        return ok;
    }

    private String generarFuncion(String nombre, List<Cuarteta> cuerpo, Set<String> firmasSoportadas, Set<String> clasesSoportadas,
                                  Set<String> estructurasNoSoportadas) {
        SimboloInvocable funcion = global.buscarFuncion(nombre);
        String tipoRetornoC = tipos.tipoCTipoY(funcion.getTipoRetorno(), estructurasNoSoportadas);

        Map<String, String> tipoDe = new LinkedHashMap<>();
        // Arreglos de Y? de tamaño fijo ("entero arr[10]"): nombre -> el
        // texto literal del tamaño, para declararlos como 'tipo nombre[N];'
        // real en vez de como puntero (ver el bucle de 'decls' mas abajo).
        // Un arreglo-parametro ('[] tipo nombre') NO entra aqui: ese SI se
        // declara como puntero, sin tamaño, igual que en C.
        Map<String, String> tamanioFijoDe = new LinkedHashMap<>();
        List<String> nombresParametros = new ArrayList<>();
        for (Simbolo parametro : funcion.getParametros()) {
            String tc = tipos.tipoCTipoY(parametro.getTipo(), estructurasNoSoportadas);
            tipoDe.put(parametro.getNombre(), tc);
            nombresParametros.add(parametro.getNombre());
        }
        if (funcion.getAmbitoLocal() != null) {
            for (Simbolo s : funcion.getAmbitoLocal().getSimbolosLocales().values()) {
                if (tipoDe.containsKey(s.getNombre())) {
                    continue; // ya es un parametro
                }
                if (s.getTipo().getCategoria() == Tipo.Categoria.ARREGLO) {
                    if (!tipos.esArregloYSoportado(s)) {
                        notas.add("funcion '" + nombre + "': la variable '" + s.getNombre()
                                + "' es un arreglo no soportado (solo arreglos de 1 dimension de entero/decimal/booleano, con tamaño literal) - pendiente");
                        return null;
                    }
                    String baseC = tipos.tipoCPrimitivoDeArreglo(s.getTipo().getTipoBase().toString());
                    tipoDe.put(s.getNombre(), baseC + "*");
                    tamanioFijoDe.put(s.getNombre(), s.getTamanioArreglo());
                    continue;
                }
                String tc = tipos.tipoCTipoY(s.getTipo(), estructurasNoSoportadas);
                if (tc == null) {
                    notas.add("funcion '" + nombre + "': la variable '" + s.getNombre() + "' es de un tipo no soportado (arreglo o cadena) - pendiente");
                    return null;
                }
                tipoDe.put(s.getNombre(), tc);
            }
        }

        if (!inferenciaTipos.inferir(nombre, cuerpo, tipoDe, firmasSoportadas, clasesSoportadas)) {
            return null; // ya se agrego la nota correspondiente
        }

        StringBuilder firma = new StringBuilder();
        firma.append(tipoRetornoC).append(" ").append(nombre).append("(");
        for (int p = 0; p < nombresParametros.size(); p++) {
            if (p > 0) {
                firma.append(", ");
            }
            String pn = nombresParametros.get(p);
            firma.append(tipoDe.get(pn)).append(" ").append(pn);
        }
        firma.append(")");

        StringBuilder decls = new StringBuilder();
        for (Map.Entry<String, String> e : tipoDe.entrySet()) {
            if (nombresParametros.contains(e.getKey())) {
                continue; // ya declarada en la firma
            }
            if (tamanioFijoDe.containsKey(e.getKey())) {
                String baseSinPuntero = e.getValue().substring(0, e.getValue().length() - 1);
                decls.append("    ").append(baseSinPuntero).append(" ").append(e.getKey())
                        .append("[").append(tamanioFijoDe.get(e.getKey())).append("];\n");
                continue;
            }
            decls.append("    ").append(e.getValue()).append(" ").append(e.getKey());
            if (e.getValue().startsWith("struct ") && !e.getValue().endsWith("*")) {
                decls.append(" = {0}"); // C deja un struct local sin inicializar con basura; se arranca en cero
            }
            decls.append(";\n");
        }

        Set<String> helpersLocales = new LinkedHashSet<>();
        String sentencias = emisorSentencias.emitir(nombre, cuerpo, tipoDe, helpersLocales);
        if (sentencias == null) {
            return null;
        }
        helpersCadenaUsados.addAll(helpersLocales);

        return firma + " {\n" + decls + sentencias + "}\n";
    }

    /** Traduce un constructor a 'struct Clase* Clase_crear_N(params...)': calloc + cuerpo + return this. */
    private String generarConstructor(SimboloClase clase, SimboloInvocable constructor, List<Cuarteta> cuerpo,
                                      Set<String> firmasSoportadas, Set<String> clasesSoportadas) {
        String tipoEsta = "struct " + clase.getNombre() + "*";
        String nombreCtx = "constructor de '" + clase.getNombre() + "'";

        Map<String, String> tipoDe = new LinkedHashMap<>();
        List<String> nombresParametros = new ArrayList<>();
        for (Simbolo parametro : constructor.getParametros()) {
            String tc = tipos.tipoC(parametro.getTipo());
            if (tc == null) {
                notas.add(nombreCtx + ": el parametro '" + parametro.getNombre() + "' es de un tipo no soportado (cadena o arreglo) - pendiente");
                return null;
            }
            tipoDe.put(parametro.getNombre(), tc);
            nombresParametros.add(parametro.getNombre());
        }
        tipoDe.put("this", tipoEsta);
        if (constructor.getAmbitoLocal() != null) {
            for (Simbolo s : constructor.getAmbitoLocal().getSimbolosLocales().values()) {
                if (tipoDe.containsKey(s.getNombre())) {
                    continue;
                }
                String tc = tipos.tipoC(s.getTipo());
                if (tc == null) {
                    tc = tipos.tipoCArreglo(s.getTipo());
                }
                if (tc == null) {
                    notas.add(nombreCtx + ": la variable '" + s.getNombre() + "' es de un tipo no soportado - pendiente");
                    return null;
                }
                tipoDe.put(s.getNombre(), tc);
            }
        }

        if (!inferenciaTipos.inferir(nombreCtx, cuerpo, tipoDe, firmasSoportadas, clasesSoportadas)) {
            return null;
        }

        StringBuilder firma = new StringBuilder();
        firma.append(tipoEsta).append(" ").append(clase.getNombre()).append("_crear_").append(nombresParametros.size()).append("(");
        for (int p = 0; p < nombresParametros.size(); p++) {
            if (p > 0) {
                firma.append(", ");
            }
            String pn = nombresParametros.get(p);
            firma.append(tipoDe.get(pn)).append(" ").append(pn);
        }
        firma.append(")");

        StringBuilder decls = new StringBuilder();
        for (Map.Entry<String, String> e : tipoDe.entrySet()) {
            if (nombresParametros.contains(e.getKey()) || "this".equals(e.getKey())) {
                continue;
            }
            decls.append("    ").append(e.getValue()).append(" ").append(e.getKey());
            if (e.getValue().startsWith("struct ") && !e.getValue().endsWith("*")) {
                decls.append(" = {0}");
            }
            decls.append(";\n");
        }

        Set<String> helpersLocales = new LinkedHashSet<>();
        String sentencias = emisorSentencias.emitir(nombreCtx, cuerpo, tipoDe, helpersLocales);
        if (sentencias == null) {
            return null;
        }
        helpersCadenaUsados.addAll(helpersLocales);

        StringBuilder cuerpoC = new StringBuilder();
        cuerpoC.append("    ").append(tipoEsta).append(" this = calloc(1, sizeof(struct ").append(clase.getNombre()).append("));\n");
        cuerpoC.append(decls);
        cuerpoC.append(sentencias);
        cuerpoC.append("    return this;\n");

        return firma + " {\n" + cuerpoC + "}\n";
    }

    /** Traduce un metodo a 'TipoRet Clase_metodo_N(struct Clase* this, params...)'. */
    private String generarMetodo(SimboloClase clase, SimboloInvocable metodo, List<Cuarteta> cuerpo,
                                 Set<String> firmasSoportadas, Set<String> clasesSoportadas) {
        String tipoRetornoC = tipos.tipoC(metodo.getTipoRetorno());
        String nombreCtx = "metodo '" + clase.getNombre() + "." + metodo.getNombre() + "'";
        if (tipoRetornoC == null) {
            notas.add(nombreCtx + ": el tipo de retorno no es soportado (cadena o arreglo) - pendiente");
            return null;
        }

        String tipoEsta = "struct " + clase.getNombre() + "*";
        Map<String, String> tipoDe = new LinkedHashMap<>();
        List<String> nombresParametros = new ArrayList<>();
        for (Simbolo parametro : metodo.getParametros()) {
            String tc = tipos.tipoC(parametro.getTipo());
            if (tc == null) {
                notas.add(nombreCtx + ": el parametro '" + parametro.getNombre() + "' es de un tipo no soportado (cadena o arreglo) - pendiente");
                return null;
            }
            tipoDe.put(parametro.getNombre(), tc);
            nombresParametros.add(parametro.getNombre());
        }
        tipoDe.put("this", tipoEsta);
        if (metodo.getAmbitoLocal() != null) {
            for (Simbolo s : metodo.getAmbitoLocal().getSimbolosLocales().values()) {
                if (tipoDe.containsKey(s.getNombre())) {
                    continue;
                }
                String tc = tipos.tipoC(s.getTipo());
                if (tc == null) {
                    tc = tipos.tipoCArreglo(s.getTipo());
                }
                if (tc == null) {
                    notas.add(nombreCtx + ": la variable '" + s.getNombre() + "' es de un tipo no soportado - pendiente");
                    return null;
                }
                tipoDe.put(s.getNombre(), tc);
            }
        }

        if (!inferenciaTipos.inferir(nombreCtx, cuerpo, tipoDe, firmasSoportadas, clasesSoportadas)) {
            return null;
        }

        StringBuilder firma = new StringBuilder();
        firma.append(tipoRetornoC).append(" ").append(clase.getNombre()).append("_").append(metodo.getNombre())
                .append("_").append(nombresParametros.size()).append("(").append(tipoEsta).append(" this");
        for (String pn : nombresParametros) {
            firma.append(", ").append(tipoDe.get(pn)).append(" ").append(pn);
        }
        firma.append(")");

        StringBuilder decls = new StringBuilder();
        for (Map.Entry<String, String> e : tipoDe.entrySet()) {
            if (nombresParametros.contains(e.getKey()) || "this".equals(e.getKey())) {
                continue;
            }
            decls.append("    ").append(e.getValue()).append(" ").append(e.getKey());
            if (e.getValue().startsWith("struct ") && !e.getValue().endsWith("*")) {
                decls.append(" = {0}");
            }
            decls.append(";\n");
        }

        Set<String> helpersLocales = new LinkedHashSet<>();
        String sentencias = emisorSentencias.emitir(nombreCtx, cuerpo, tipoDe, helpersLocales);
        if (sentencias == null) {
            return null;
        }
        helpersCadenaUsados.addAll(helpersLocales);

        return firma + " {\n" + decls + sentencias + "}\n";
    }

    /**
     * Traduce el cuerpo del .pig (etiqueta "main") al 'int main(void)' real.
     * A diferencia de una funcion normal, este cuerpo no tiene tabla de
     * simbolos propia, asi que el tipo de cada variable se infiere de su
     * primera cuarteta '=' (igual que una variable de bloque anidado).
     */
    private String generarMain(List<Cuarteta> cuerpo, Set<String> firmasSoportadas, Set<String> clasesSoportadas) {
        Map<String, String> tipoDe = new LinkedHashMap<>();
        if (!inferenciaTipos.inferir("main", cuerpo, tipoDe, firmasSoportadas, clasesSoportadas)) {
            return null; // ya se agrego la nota correspondiente
        }

        StringBuilder decls = new StringBuilder();
        for (Map.Entry<String, String> e : tipoDe.entrySet()) {
            decls.append("    ").append(e.getValue()).append(" ").append(e.getKey());
            if (e.getValue().startsWith("struct ") && !e.getValue().endsWith("*")) {
                decls.append(" = {0}");
            }
            decls.append(";\n");
        }

        Set<String> helpersLocales = new LinkedHashSet<>();
        String sentencias = emisorSentencias.emitir("main", cuerpo, tipoDe, helpersLocales);
        if (sentencias == null) {
            return null;
        }
        helpersCadenaUsados.addAll(helpersLocales);

        return "int main(void) {\n" + decls + sentencias + "    return 0;\n}\n";
    }

    /**
     * Emite un forward-declare + la definicion de cada estructura
     * registrada, en ese orden (asi no importa el orden real ni si se
     * referencian entre si). Una estructura en 'estructurasNoSoportadas'
     * (campo arreglo invalido - ver calcularEstructurasNoSoportadas())
     * queda solo forward-declarada, sin cuerpo: ninguna funcion la usa
     * (fueron excluidas tambien), asi que no hace falta definirla.
     */
    private List<String> definirEstructuras(Set<String> estructurasNoSoportadas) {
        List<String> defs = new ArrayList<>();
        StringBuilder forwards = new StringBuilder();
        for (SimboloEstructura estructura : global.getEstructuras().values()) {
            forwards.append("struct ").append(estructura.getNombre()).append(";\n");
        }
        if (forwards.length() > 0) {
            defs.add(forwards.toString());
        }
        for (SimboloEstructura estructura : global.getEstructuras().values()) {
            if (estructurasNoSoportadas.contains(estructura.getNombre())) {
                continue;
            }
            StringBuilder def = new StringBuilder();
            def.append("struct ").append(estructura.getNombre()).append(" {\n");
            for (Simbolo campo : estructura.getCampos().values()) {
                if (campo.getTipo().getCategoria() == Tipo.Categoria.ARREGLO) {
                    String baseC = tipos.tipoCPrimitivoDeArreglo(campo.getTipo().getTipoBase().toString());
                    def.append("    ").append(baseC).append(" ").append(campo.getNombre())
                            .append("[").append(campo.getTamanioArreglo()).append("];\n");
                } else {
                    def.append("    ").append(tipos.tipoCCampo(campo.getTipo())).append(" ").append(campo.getNombre()).append(";\n");
                }
            }
            def.append("};\n");
            defs.add(def.toString());
        }
        return defs;
    }

    /** Igual que definirEstructuras() pero para clases: forward-declara todas, pero solo define los campos de las soportadas. */
    private List<String> definirClases(Set<String> clasesSoportadas) {
        List<String> defs = new ArrayList<>();
        StringBuilder forwards = new StringBuilder();
        for (SimboloClase clase : global.getClases().values()) {
            forwards.append("struct ").append(clase.getNombre()).append(";\n");
        }
        if (forwards.length() > 0) {
            defs.add(forwards.toString());
        }
        for (SimboloClase clase : global.getClases().values()) {
            if (!clasesSoportadas.contains(clase.getNombre())) {
                continue;
            }
            StringBuilder def = new StringBuilder();
            def.append("struct ").append(clase.getNombre()).append(" {\n");
            for (Simbolo campo : clase.getCampos().values()) {
                def.append("    ").append(tipos.tipoCCampo(campo.getTipo())).append(" ").append(campo.getNombre()).append(";\n");
            }
            def.append("};\n");
            defs.add(def.toString());
        }
        return defs;
    }
}