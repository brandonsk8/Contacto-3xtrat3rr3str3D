package com.company.backend;

import com.company.ir.Cuarteta;
import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.Etiquetas;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloEstructura;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modelo comun de los backends de Etapa 2 (C con etiquetas y RISC-V):
 * parte las cuartetas en funciones, arma la firma de cada una a partir de
 * la tabla de simbolos y le infiere un tipo a cada variable/temporal.
 * Un arreglo se describe con su tipo de ELEMENTO + su numero de
 * dimensiones; el acceso "a[i][j]" se aplana en cada backend.
 * Lo no soportado se reporta con NoSoportado y queda en las notas.
 */
final class ModeloPrograma {

    static final int MAX_DIMS = 4;

    private final TablaSimbolosGlobal global;
    private final List<String> notas;
    private final String etiquetaBackend;

    ModeloPrograma(TablaSimbolosGlobal global, List<String> notas, String etiquetaBackend) {
        this.global = global;
        this.notas = notas;
        this.etiquetaBackend = etiquetaBackend;
    }

    /** Parte, arma firmas e infiere tipos; descarta (con nota) lo que no se puede traducir. */
    Map<String, Fn> construir(List<Cuarteta> todas) {
        Map<String, Fn> fns = new LinkedHashMap<>();
        partir(todas, fns);
        for (Fn f : new ArrayList<>(fns.values())) {
            try {
                bajar(f, fns);
            } catch (NoSoportado e) {
                notas.add("funcion '" + f.nombre + "' (" + etiquetaBackend + "): " + e.getMessage() + " - pendiente");
                fns.remove(f.nombre);
            }
        }
        return fns;
    }

    static final class NoSoportado extends RuntimeException {
        NoSoportado(String m) {
            super(m, null, false, false);
        }
    }

    /** Una funcion de usuario (o el main de un .pig) con los tipos de todos sus nombres. */
    static final class Fn {
        final String nombre;
        final boolean esMain;
        final Map<String, String> tipo = new LinkedHashMap<>();   // tipo C escalar, o tipo C del ELEMENTO si es arreglo
        final Map<String, Integer> dims = new LinkedHashMap<>();  // solo arreglos: numero de dimensiones
        final Map<String, String> clase = new LinkedHashMap<>();  // tipo "obj": nombre de la clase/estructura (del objeto o del ELEMENTO)
        final List<String> estructurasLocales = new ArrayList<>(); // locales de tipo estructura: se reservan al entrar
        String claseRet;  // si retorna un objeto
        String claseThis; // si es ctor/metodo: clase a la que pertenece
        final List<String> params = new ArrayList<>();
        final Map<String, List<String>> tamaniosFijos = new LinkedHashMap<>(); // arreglos de Y? con tamano en el simbolo
        String tipoRet;   // null = void
        int dimsRet;      // > 0 si retorna un arreglo
        List<Cuarteta> cuerpo = new ArrayList<>();

        Fn(String nombre, boolean esMain) {
            this.nombre = nombre;
            this.esMain = esMain;
        }
    }

    /** Parte las cuartetas por etiqueta de inicio de funcion y arma el esqueleto (firma) de cada una. */
    void partir(List<Cuarteta> todas, Map<String, Fn> fns) {
        int i = 0;
        while (i < todas.size()) {
            Cuarteta c = todas.get(i);
            if (!esInicio(c)) {
                i++;
                continue;
            }
            String etiqueta = c.getResultado();
            int fin = i + 1;
            while (fin < todas.size() && !esInicio(todas.get(fin))) {
                fin++;
            }
            if (etiqueta.startsWith("func_")) {
                String nombre = etiqueta.substring("func_".length());
                try {
                    Fn f = esqueletoFuncion(nombre);
                    f.cuerpo = new ArrayList<>(todas.subList(i + 1, fin));
                    fns.put(nombre, f);
                } catch (NoSoportado e) {
                    notas.add("funcion '" + nombre + "' (" + etiquetaBackend + "): " + e.getMessage() + " - pendiente");
                }
            } else if (etiqueta.startsWith("ctor_") || etiqueta.startsWith("metodo_")) {
                try {
                    Fn f = esqueletoMiembro(etiqueta);
                    f.cuerpo = new ArrayList<>(todas.subList(i + 1, fin));
                    fns.put(etiqueta, f);
                } catch (NoSoportado e) {
                    notas.add("'" + etiqueta + "' (" + etiquetaBackend + "): " + e.getMessage() + " - pendiente");
                }
            } else if ("main".equals(etiqueta)) {
                Fn f = new Fn("main", true);
                f.cuerpo = new ArrayList<>(todas.subList(i + 1, fin));
                fns.put("main", f);
            }
            i = fin;
        }
    }

    // True si la cuarteta abre una funcion.
    boolean esInicio(Cuarteta c) {
        if (!"label".equals(c.getOperador()) || c.getResultado() == null) {
            return false;
        }
        String r = c.getResultado();
        return r.startsWith("func_") || r.startsWith("ctor_") || r.startsWith("metodo_") || "main".equals(r);
    }

    // ------------------------------------------------------------------
    // Esqueletos (firma + tipos declarados) de funciones, constructores y metodos
    // ------------------------------------------------------------------

    // Crea la funcion vacia con su firma.
    Fn esqueletoFuncion(String nombre) {
        SimboloInvocable s = global.buscarFuncion(nombre);
        if (s == null) {
            throw new NoSoportado("funcion sin simbolo");
        }
        Fn f = new Fn(nombre, false);
        llenarFirma(f, s, null);
        return f;
    }

    /** Rellena tipo de retorno, parametros (+ 'this' al final si es de una clase) y locales. */
    private void llenarFirma(Fn f, SimboloInvocable s, SimboloClase duena) {
        Tipo ret = s.getTipoRetorno();
        if (ret != null && ret.getCategoria() != Tipo.Categoria.VOID && s.getCategoria() != CategoriaSimbolo.CONSTRUCTOR) {
            declararTipo(f, "#ret", ret);
            f.tipoRet = f.tipo.remove("#ret");
            Integer d = f.dims.remove("#ret");
            f.dimsRet = d != null ? d : 0;
            f.claseRet = f.clase.remove("#ret");
        }
        for (Simbolo p : s.getParametros()) {
            declararTipo(f, p.getNombre(), p.getTipo());
            f.params.add(p.getNombre());
        }
        if (duena != null) {
            f.tipo.put("this", "obj");
            f.clase.put("this", duena.getNombre());
            f.params.add("this");
            f.claseThis = duena.getNombre();
        }
        if (s.getAmbitoLocal() != null) {
            for (Simbolo l : s.getAmbitoLocal().getSimbolosLocales().values()) {
                if (f.tipo.containsKey(l.getNombre())) {
                    continue;
                }
                declararTipo(f, l.getNombre(), l.getTipo());
                if (l.getTipo().getCategoria() == Tipo.Categoria.ESTRUCTURA) {
                    f.estructurasLocales.add(l.getNombre());
                }
            }
        }
    }

    private Map<String, Object[]> miembros; // etiqueta -> {SimboloClase, SimboloInvocable}

    // Indice de metodos y constructores por etiqueta.
    private Map<String, Object[]> miembros() {
        if (miembros == null) {
            miembros = new LinkedHashMap<>();
            for (SimboloClase c : global.getClases().values()) {
                for (SimboloInvocable k : c.getConstructores()) {
                    miembros.putIfAbsent(Etiquetas.ctor(c, k), new Object[]{c, k});
                }
                for (List<SimboloInvocable> ms : c.getMetodos().values()) {
                    for (SimboloInvocable m : ms) {
                        miembros.putIfAbsent(Etiquetas.metodo(c, m), new Object[]{c, m});
                    }
                }
            }
        }
        return miembros;
    }

    // Crea el esqueleto de un metodo/constructor (this va al final).
    Fn esqueletoMiembro(String etiqueta) {
        Object[] m = miembros().get(etiqueta);
        if (m == null) {
            throw new NoSoportado("miembro de clase sin simbolo");
        }
        Fn f = new Fn(etiqueta, false);
        llenarFirma(f, (SimboloInvocable) m[1], (SimboloClase) m[0]);
        return f;
    }

    // ------------------------------------------------------------------
    // Tipos: escalares, arreglos, objetos
    // ------------------------------------------------------------------

    // Registra el tipo de una variable local.
    void declararTipo(Fn f, String nombre, Tipo t) {
        if (t.getCategoria() == Tipo.Categoria.ARREGLO) {
            Tipo base = t.getTipoBase();
            String elem = tipoElemento(base);
            if (elem == null) {
                throw new NoSoportado("arreglo de '" + base + "' no soportado");
            }
            if (t.getDimensiones() > MAX_DIMS) {
                throw new NoSoportado("arreglo de mas de " + MAX_DIMS + " dimensiones");
            }
            f.tipo.put(nombre, elem);
            f.dims.put(nombre, t.getDimensiones());
            if ("obj".equals(elem)) {
                f.clase.put(nombre, base.getNombreDefinido());
            }
            return;
        }
        String c = tipoElemento(t);
        if (c == null) {
            throw new NoSoportado("tipo '" + t + "' no soportado todavia");
        }
        f.tipo.put(nombre, c);
        if ("obj".equals(c)) {
            f.clase.put(nombre, t.getNombreDefinido());
        }
    }

    /** Tipo del modelo: int/double/char/char* (cadena) u "obj" para clases y estructuras; null si no aplica. */
    String tipoElemento(Tipo t) {
        if (t.getCategoria() == Tipo.Categoria.CLASE || t.getCategoria() == Tipo.Categoria.ESTRUCTURA) {
            return "obj";
        }
        return elementoC(t.toString());
    }

    /** Nombre de tipo del lenguaje (Tipo.toString()) -> tipo del modelo; null si no esta soportado. */
    String elementoC(String nombre) {
        switch (nombre) {
            case "entero": return "int";
            case "decimal": return "double";
            case "booleano": return "int";
            case "caracter": return "char";
            case "cadena": return "char*";
            default: return null;
        }
    }

    // ------------------------------------------------------------------
    // Disposicion en memoria de los objetos
    // ------------------------------------------------------------------

    /** Un campo de un objeto: celda 'slot' (cada celda ocupa 8 bytes; el arreglo usa ademas 'dims' celdas para sus dimensiones). */
    static final class Campo {
        String nombre;
        String tipo;      // tipo del modelo del escalar o del ELEMENTO
        String clase;     // si tipo == "obj"
        int dims;         // > 0 si es arreglo
        int slot;
    }

    static final class Layout {
        String nombre;
        int id;                       // identificador de clase en tiempo de ejecucion (0 = estructura)
        String padre;
        final Map<String, Campo> campos = new LinkedHashMap<>();
        int slots = 1;                // celda 0 = id de clase
    }

    private final Map<String, Layout> layouts = new LinkedHashMap<>();

    // Disposicion en memoria de una clase/struct (slots de 8 bytes).
    Layout layoutDe(String nombre) {
        Layout l = layouts.get(nombre);
        if (l != null) {
            return l;
        }
        l = new Layout();
        l.nombre = nombre;
        Map<String, Simbolo> propios;
        SimboloClase c = global.buscarClase(nombre);
        if (c != null) {
            int id = 1;
            for (String n : global.getClases().keySet()) {
                if (n.equals(nombre)) {
                    break;
                }
                id++;
            }
            l.id = id;
            l.padre = c.getNombreSuperclase();
            if (l.padre != null && global.buscarClase(l.padre) != null) {
                Layout p = layoutDe(l.padre);
                for (Campo k : p.campos.values()) {
                    l.campos.put(k.nombre, k);
                }
                l.slots = p.slots;
            }
            propios = c.getCampos();
        } else {
            SimboloEstructura e = global.buscarEstructura(nombre);
            if (e == null) {
                throw new NoSoportado("clase/estructura '" + nombre + "' desconocida");
            }
            propios = e.getCampos();
        }
        for (Simbolo s : propios.values()) {
            Campo k = new Campo();
            k.nombre = s.getNombre();
            Tipo t = s.getTipo();
            if (t.getCategoria() == Tipo.Categoria.ARREGLO) {
                k.tipo = tipoElemento(t.getTipoBase());
                k.dims = t.getDimensiones();
                k.clase = "obj".equals(k.tipo) ? t.getTipoBase().getNombreDefinido() : null;
            } else {
                k.tipo = tipoElemento(t);
                k.clase = "obj".equals(k.tipo) ? t.getNombreDefinido() : null;
            }
            if (k.tipo == null || k.dims > MAX_DIMS) {
                throw new NoSoportado("campo '" + nombre + "." + k.nombre + "' de tipo no soportado");
            }
            k.slot = l.slots;
            l.slots += 1 + k.dims;
            l.campos.put(k.nombre, k);
        }
        layouts.put(nombre, l);
        return l;
    }

    // Busca un campo (con herencia) y su posicion.
    Campo campoDe(String clase, String campo) {
        Campo k = layoutDe(clase).campos.get(campo);
        if (k == null) {
            throw new NoSoportado("la clase '" + clase + "' no tiene el campo '" + campo + "'");
        }
        return k;
    }

    // ------------------------------------------------------------------
    // Bajado: objetos, llamadas virtuales y tipos (una pasada por funcion)
    // ------------------------------------------------------------------

    private int contadorTemp = 0;
    private int contadorEtiqueta = 0;

    // Crea un temporal tipado.
    private String nuevoTemp(Fn f, String tipo, String clase, int dims) {
        String t = "o_" + (contadorTemp++);
        f.tipo.put(t, tipo);
        if (dims > 0) {
            f.dims.put(t, dims);
        }
        if (clase != null) {
            f.clase.put(t, clase);
        }
        return t;
    }

    private static final class Acceso {
        final boolean campo;
        final String texto;

        Acceso(boolean campo, String texto) {
            this.campo = campo;
            this.texto = texto;
        }
    }

    private static final class Cadena {
        String base;
        final List<Acceso> accesos = new ArrayList<>();
    }

    // True si el texto es un literal.
    private boolean esLiteral(String t) {
        return "null".equals(t) || TiposC.LITERALES_VERDADERO.contains(t) || TiposC.LITERALES_FALSO.contains(t)
                || TiposC.ENTERO_LIT.matcher(t).matches() || TiposC.DECIMAL_LIT.matcher(t).matches()
                || TiposC.CARACTER_LIT.matcher(t).matches() || TiposC.CADENA_LIT.matcher(t).matches();
    }

    // Descompone un acceso a.b[i].c en partes.
    private Cadena parsear(String t) {
        Cadena c = new Cadena();
        int i = 0;
        while (i < t.length() && t.charAt(i) != '.' && t.charAt(i) != '[') {
            i++;
        }
        c.base = t.substring(0, i);
        while (i < t.length()) {
            char ch = t.charAt(i);
            if (ch == '.') {
                int j = i + 1;
                while (j < t.length() && t.charAt(j) != '.' && t.charAt(j) != '[') {
                    j++;
                }
                c.accesos.add(new Acceso(true, t.substring(i + 1, j)));
                i = j;
            } else if (ch == '[') {
                int nivel = 0;
                int j = i;
                for (; j < t.length(); j++) {
                    if (t.charAt(j) == '[') {
                        nivel++;
                    } else if (t.charAt(j) == ']' && --nivel == 0) {
                        break;
                    }
                }
                if (j >= t.length()) {
                    throw new NoSoportado("indice mal formado en '" + t + "'");
                }
                c.accesos.add(new Acceso(false, t.substring(i + 1, j)));
                i = j + 1;
            } else {
                throw new NoSoportado("acceso no soportado '" + t + "'");
            }
        }
        return c;
    }

    /**
     * Baja un operando con campos ("this.celdas[f][c]", "a.b.c") a un operando simple
     * (variable, literal o "arr[i][j]" con indices simples), emitiendo las lecturas de campo necesarias.
     * hasta = cantidad de accesos a resolver (para poder dejar el ultimo campo sin leer, al asignar).
     */
    private String resolver(Fn f, Cadena ch, int hasta, List<Cuarteta> out) {
        String cur = ch.base;
        if (!f.tipo.containsKey(cur)) {
            throw new NoSoportado("operando '" + cur + "' desconocido");
        }
        int i = 0;
        while (i < hasta) {
            Acceso a = ch.accesos.get(i);
            if (a.campo) {
                if (!"obj".equals(f.tipo.get(cur)) || f.dims.containsKey(cur) || f.clase.get(cur) == null) {
                    throw new NoSoportado("'" + cur + "." + a.texto + "': no es un objeto");
                }
                Campo k = campoDe(f.clase.get(cur), a.texto);
                String t = nuevoTemp(f, k.tipo, k.clase, k.dims);
                out.add(new Cuarteta("getf", cur, f.clase.get(cur) + "." + k.nombre, t));
                cur = t;
                i++;
            } else {
                Integer d = f.dims.get(cur);
                if (d == null) {
                    throw new NoSoportado("'" + cur + "' no es un arreglo");
                }
                if (i + d > hasta) {
                    throw new NoSoportado("acceso a '" + cur + "' (se necesita un indice por dimension)");
                }
                StringBuilder sb = new StringBuilder(cur);
                for (int k = 0; k < d; k++) {
                    Acceso ia = ch.accesos.get(i + k);
                    if (ia.campo) {
                        throw new NoSoportado("indices incompletos en '" + cur + "'");
                    }
                    sb.append("[").append(atomo(f, ia.texto, out)).append("]");
                }
                i += d;
                String elem = sb.toString();
                if (i < hasta) {
                    // el elemento (un objeto) tiene mas accesos: se materializa
                    String t = nuevoTemp(f, f.tipo.get(cur), f.clase.get(cur), 0);
                    out.add(new Cuarteta("=", elem, null, t));
                    cur = t;
                } else {
                    cur = elem;
                }
            }
        }
        return cur;
    }

    // Deja un operando como valor simple.
    String atomo(Fn f, String t, List<Cuarteta> out) {
        if (t == null || esLiteral(t) || (t.indexOf('.') < 0 && t.indexOf('[') < 0)) {
            return t;
        }
        Cadena ch = parsear(t);
        return resolver(f, ch, ch.accesos.size(), out);
    }

    /** Guarda 'valor' en 'dest' (variable, elemento de arreglo o campo), bajando lo que haga falta. */
    private void guardar(Fn f, String dest, String valor, List<Cuarteta> out) {
        if (esLiteral(dest) || (dest.indexOf('.') < 0 && dest.indexOf('[') < 0)) {
            if (!f.tipo.containsKey(dest)) {
                copiarTipo(f, dest, valor);
            }
            out.add(new Cuarteta("=", valor, null, dest));
            return;
        }
        Cadena ch = parsear(dest);
        Acceso ultimo = ch.accesos.get(ch.accesos.size() - 1);
        if (ultimo.campo) {
            String obj = resolver(f, ch, ch.accesos.size() - 1, out);
            String clase = claseOp(f, obj);
            if (clase == null) {
                throw new NoSoportado("asignacion a '" + dest + "': no es un campo de un objeto");
            }
            Campo k = campoDe(clase, ultimo.texto);
            out.add(new Cuarteta("setf", obj, clase + "." + k.nombre, valor));
        } else {
            out.add(new Cuarteta("=", valor, null, resolver(f, ch, ch.accesos.size(), out)));
        }
    }

    // Copia la informacion de tipo entre variables.
    private void copiarTipo(Fn f, String dest, String origen) {
        if (f.dims.containsKey(origen) && origen.indexOf('[') < 0) {
            f.tipo.put(dest, f.tipo.get(origen));
            f.dims.put(dest, f.dims.get(origen));
            if (f.clase.containsKey(origen)) {
                f.clase.put(dest, f.clase.get(origen));
            }
            return;
        }
        String t = tipoOp(f, origen);
        if (t == null || "null".equals(origen)) {
            throw new NoSoportado("no se pudo determinar el tipo de '" + dest + "'");
        }
        f.tipo.put(dest, t);
        if ("obj".equals(t)) {
            String c = claseOp(f, origen);
            if (c != null) {
                f.clase.put(dest, c);
            }
        }
    }

    /** Clase de un operando de tipo objeto (variable o elemento de arreglo de objetos); null si no aplica. */
    String claseOp(Fn f, String t) {
        if (t == null || "null".equals(t)) {
            return null;
        }
        int corchete = t.indexOf('[');
        String base = corchete > 0 ? t.substring(0, corchete) : t;
        if (!"obj".equals(f.tipo.get(base))) {
            return null;
        }
        Integer d = f.dims.get(base);
        if (corchete > 0) {
            if (d == null || d != indices(t).size()) {
                return null;
            }
        } else if (d != null) {
            return null;
        }
        return f.clase.get(base);
    }

    private int contadorStructAnidado = 0;

    /**
     * Reserva un struct y, recursivamente, los structs que lleva DENTRO como campos
     * (un campo 'Punto a' de otro struct es una estructura embebida, no un puntero nulo).
     */
    private void nuevoStruct(Fn f, String estructura, String destino, List<Cuarteta> out) {
        out.add(new Cuarteta("new_obj", estructura, null, destino));
        Layout l = layoutDe(estructura);
        for (Campo k : l.campos.values()) {
            if (k.dims == 0 && k.clase != null && global.buscarEstructura(k.clase) != null) {
                String tmp = "__sn" + (contadorStructAnidado++);
                f.tipo.put(tmp, "obj");
                f.clase.put(tmp, k.clase);
                nuevoStruct(f, k.clase, tmp, out);
                out.add(new Cuarteta("setf", destino, estructura + "." + k.nombre, tmp));
            }
        }
    }

    // Baja todas las cuartetas de una funcion.
    void bajar(Fn f, Map<String, Fn> fns) {
        List<Cuarteta> out = new ArrayList<>();
        List<String> pend = new ArrayList<>();
        for (String v : f.estructurasLocales) {
            nuevoStruct(f, f.clase.get(v), v, out);
        }
        for (Cuarteta c : f.cuerpo) {
            bajarUna(f, c, out, pend, fns);
        }
        f.cuerpo = out;
    }

    // Baja una cuarteta (getf, setf, len, new, print...).
    private void bajarUna(Fn f, Cuarteta c, List<Cuarteta> out, List<String> pend, Map<String, Fn> fns) {
        String op = c.getOperador();
        String res = c.getResultado();
        switch (op) {
            case "label":
            case "goto":
                out.add(c);
                return;
            case "if_false":
            case "if_true":
                out.add(new Cuarteta(op, atomo(f, c.getArg1(), out), null, res));
                return;
            case "return":
                out.add(new Cuarteta(op, c.getArg1() == null ? null : atomo(f, c.getArg1(), out), null, null));
                return;
            case "print":
                out.add(new Cuarteta(op, atomo(f, c.getArg1(), out), null, res));
                return;
            case "param":
                pend.add(atomo(f, c.getArg1(), out));
                return;
            case "read":
                out.add(c);
                return;
            case "len": {
                // arr.length / arr[i].length: la dimension k del arreglo (arg2 = k)
                String base = atomo(f, c.getArg1(), out);
                Integer d = f.dims.get(base);
                int k = Integer.parseInt(c.getArg2());
                if (d == null || k >= d) {
                    throw new NoSoportado("'" + c.getArg1() + ".length': no es un arreglo con esa dimension");
                }
                f.tipo.put(res, "int");
                out.add(new Cuarteta("len", base, c.getArg2(), res));
                return;
            }
            case "decl": {
                Tipo t = tipoDeTexto(c.getArg1());
                if (t == null) {
                    throw new NoSoportado("tipo '" + c.getArg1() + "' desconocido");
                }
                if (f.tipo.containsKey(res) && !f.params.contains(res) && !"this".equals(res)) {
                    f.tipo.remove(res);
                    f.dims.remove(res);
                    f.clase.remove(res);
                }
                if (!f.params.contains(res)) {
                    declararTipo(f, res, t);
                }
                if (t.getCategoria() == Tipo.Categoria.ESTRUCTURA) {
                    nuevoStruct(f, c.getArg1(), res, out);
                }
                return;
            }
            case "++":
            case "--": {
                String d = res;
                if (esLiteral(d) || (d.indexOf('.') < 0 && d.indexOf('[') < 0)) {
                    out.add(c);
                    return;
                }
                Cadena ch = parsear(d);
                if (!ch.accesos.get(ch.accesos.size() - 1).campo) {
                    String a = resolver(f, ch, ch.accesos.size(), out);
                    out.add(new Cuarteta(op, a, null, a));
                    return;
                }
                String valor = atomo(f, d, out);
                String t = nuevoTemp(f, f.tipo.get(valor), null, 0);
                out.add(new Cuarteta("=", valor, null, t));
                out.add(new Cuarteta(op, t, null, t));
                guardar(f, d, t, out);
                return;
            }
            case "new_array": {
                List<String> tams = new ArrayList<>();
                for (String s : c.getArg2().split(",")) {
                    tams.add(atomo(f, s.trim(), out));
                }
                String elem = elementoC(c.getArg1());
                String clase = null;
                if (elem == null) {
                    if (global.buscarClase(c.getArg1()) != null || global.buscarEstructura(c.getArg1()) != null) {
                        elem = "obj";
                        clase = c.getArg1();
                    } else {
                        throw new NoSoportado("arreglo de '" + c.getArg1() + "' no soportado");
                    }
                }
                if (tams.size() > MAX_DIMS) {
                    throw new NoSoportado("arreglo de mas de " + MAX_DIMS + " dimensiones");
                }
                String destino = res;
                boolean temp = !(destino.indexOf('.') < 0 && destino.indexOf('[') < 0);
                if (temp) {
                    destino = nuevoTemp(f, elem, clase, tams.size());
                } else {
                    f.tipo.putIfAbsent(destino, elem);
                    f.dims.putIfAbsent(destino, tams.size());
                    if (clase != null) {
                        f.clase.putIfAbsent(destino, clase);
                    }
                }
                out.add(new Cuarteta("new_array", c.getArg1(), String.join(",", tams), destino));
                if (temp) {
                    guardar(f, res, destino, out);
                }
                return;
            }
            case "new": {
                String[] cf = c.getArg1().split("#", 2);
                String clase = cf[0];
                String firma = cf.length > 1 ? cf[1] : "";
                int n = Integer.parseInt(c.getArg2());
                SimboloClase sc = global.buscarClase(clase);
                if (sc == null) {
                    throw new NoSoportado("'new " + clase + "': clase desconocida");
                }
                layoutDe(clase);
                String etiqueta = null;
                for (SimboloInvocable k : sc.getConstructores()) {
                    if (Etiquetas.firma(k).equals(firma) && k.getParametros().size() == n) {
                        etiqueta = Etiquetas.ctor(sc, k);
                    }
                }
                Fn ctor = etiqueta != null ? fns.get(etiqueta) : null;
                if (ctor == null) {
                    throw new NoSoportado("la clase '" + clase + "' no tiene un constructor de " + n + " argumento(s) traducible");
                }
                List<String> args = tomar(pend, n);
                f.tipo.put(res, "obj");
                f.clase.put(res, clase);
                out.add(new Cuarteta("new_obj", clase, null, res));
                for (String a : args) {
                    out.add(new Cuarteta("param", a, null, null));
                }
                out.add(new Cuarteta("param", res, null, null));
                out.add(new Cuarteta("call", etiqueta, String.valueOf(n + 1), null));
                return;
            }
            case "call":
                bajarLlamada(f, c, out, pend, fns);
                return;
            case "concat": {
                String a = atomo(f, c.getArg1(), out);
                String b = atomo(f, c.getArg2(), out);
                destinoGeneral(f, res, "char*", new Cuarteta("concat", a, b, null), out);
                return;
            }
            case "=": {
                String origen = atomo(f, c.getArg1(), out);
                guardar(f, res, origen, out);
                return;
            }
            default:
                break;
        }
        // operadores aritmeticos, de comparacion y logicos
        String a = atomo(f, c.getArg1(), out);
        String b = c.getArg2() == null ? null : atomo(f, c.getArg2(), out);
        String tipo;
        if ("-u".equals(op)) {
            tipo = necesitaTipo(f, a);
        } else if ("!".equals(op) || COMPARACIONES.contains(op) || "&&".equals(op) || "||".equals(op)) {
            tipo = "int";
        } else if (ARITMETICOS.contains(op)) {
            String t1 = necesitaTipo(f, a);
            String t2 = necesitaTipo(f, b);
            tipo = ("double".equals(t1) || "double".equals(t2)) ? "double" : "int";
        } else {
            throw new NoSoportado("operador '" + op + "'");
        }
        destinoGeneral(f, res, tipo, new Cuarteta(op, a, b, null), out);
    }

    /** Emite 'base' con resultado 'res'; si res es un campo/elemento complejo, pasa por un temporal. */
    private void destinoGeneral(Fn f, String res, String tipo, Cuarteta base, List<Cuarteta> out) {
        if (res.indexOf('.') < 0 && res.indexOf('[') < 0) {
            f.tipo.putIfAbsent(res, tipo);
            out.add(new Cuarteta(base.getOperador(), base.getArg1(), base.getArg2(), res));
            return;
        }
        String t = nuevoTemp(f, tipo, null, 0);
        out.add(new Cuarteta(base.getOperador(), base.getArg1(), base.getArg2(), t));
        guardar(f, res, t, out);
    }

    // Saca los ultimos n parametros pendientes.
    private List<String> tomar(List<String> pend, int n) {
        if (n > pend.size()) {
            throw new NoSoportado("llamada con menos 'param' que argumentos");
        }
        List<String> args = new ArrayList<>(pend.subList(pend.size() - n, pend.size()));
        for (int k = 0; k < n; k++) {
            pend.remove(pend.size() - 1);
        }
        return args;
    }

    // Baja una llamada: despacho virtual, super, this(...) y sobrecarga.
    private void bajarLlamada(Fn f, Cuarteta c, List<Cuarteta> out, List<String> pend, Map<String, Fn> fns) {
        String objetivo = c.getArg1();
        String firma = "";
        int almohadilla = objetivo.indexOf('#');
        if (almohadilla >= 0) {
            firma = objetivo.substring(almohadilla + 1);
            objetivo = objetivo.substring(0, almohadilla);
        }
        int n = Integer.parseInt(c.getArg2());
        String res = c.getResultado();
        int punto = objetivo.lastIndexOf('.');

        // funcion normal (Y? / funcion suelta)
        if (punto < 0) {
            Fn callee = fns.get(objetivo);
            if (callee == null) {
                throw new NoSoportado("llama a '" + objetivo + "', que no se pudo traducir");
            }
            List<String> args = tomar(pend, n);
            emitirLlamadaDirecta(f, callee, args, res, out);
            return;
        }

        String nombre = objetivo.substring(punto + 1);
        String recvTxt = objetivo.substring(0, punto);

        // this(...): otro constructor de la misma clase
        if ("this".equals(recvTxt) && "<init>".equals(nombre)) {
            List<String> args = tomar(pend, n + 1);
            SimboloClase yo = global.buscarClase(f.claseThis);
            String etiquetaCtor = null;
            if (yo != null) {
                for (SimboloInvocable k : yo.getConstructores()) {
                    if (Etiquetas.firma(k).equals(firma) && k.getParametros().size() == n) {
                        etiquetaCtor = Etiquetas.ctor(yo, k);
                    }
                }
            }
            Fn ctor = etiquetaCtor != null ? fns.get(etiquetaCtor) : null;
            if (ctor == null) {
                throw new NoSoportado("no se encuentra el constructor para this(...) con " + n + " argumento(s)");
            }
            emitirLlamadaDirecta(f, ctor, args, null, out);
            return;
        }

        // super(...): constructor del padre
        if ("super".equals(recvTxt) && "<init>".equals(nombre)) {
            List<String> args = tomar(pend, n + 1);
            SimboloClase yo = global.buscarClase(f.claseThis);
            SimboloClase padreSim = yo != null ? yo.getSuperclase(global) : null;
            String padre = padreSim != null ? padreSim.getNombre() : null;
            String etiquetaCtor = null;
            if (padreSim != null) {
                for (SimboloInvocable k : padreSim.getConstructores()) {
                    if (Etiquetas.firma(k).equals(firma) && k.getParametros().size() == n) {
                        etiquetaCtor = Etiquetas.ctor(padreSim, k);
                    }
                }
            }
            Fn ctor = etiquetaCtor != null ? fns.get(etiquetaCtor) : null;
            if (ctor == null) {
                throw new NoSoportado("no se encuentra el constructor de la superclase para super(...) con " + n + " argumento(s)");
            }
            emitirLlamadaDirecta(f, ctor, args, null, out);
            return;
        }

        List<String> args = tomar(pend, n + 1);
        String receptor = args.get(n);

        // super.metodo(...): llamada NO virtual a la implementacion del padre
        if ("super".equals(recvTxt)) {
            SimboloClase yo = global.buscarClase(f.claseThis);
            String padre = yo != null ? yo.getNombreSuperclase() : null;
            String etiqueta = padre != null ? implementacion(padre, nombre, n, firma) : null;
            Fn callee = etiqueta != null ? fns.get(etiqueta) : null;
            if (callee == null) {
                throw new NoSoportado("super." + nombre + ": no se encuentra la implementacion heredada");
            }
            emitirLlamadaDirecta(f, callee, args, res, out);
            return;
        }

        String estatica = claseOp(f, receptor);
        if (estatica == null) {
            throw new NoSoportado("llamada a '" + objetivo + "': no se conoce la clase del receptor");
        }

        // despacho virtual: una rama por clase concreta que pueda ser el receptor
        Map<String, List<Integer>> porImpl = new LinkedHashMap<>();
        for (SimboloClase k : global.getClases().values()) {
            if (!k.esSubclaseDeOIgual(estatica, global)) {
                continue;
            }
            String impl = implementacion(k.getNombre(), nombre, n, firma);
            if (impl != null) {
                porImpl.computeIfAbsent(impl, x -> new ArrayList<>()).add(layoutDe(k.getNombre()).id);
            }
        }
        if (porImpl.isEmpty()) {
            throw new NoSoportado("el metodo '" + nombre + "' no existe en '" + estatica + "'");
        }
        if (porImpl.size() == 1) {
            Fn callee = fns.get(porImpl.keySet().iterator().next());
            if (callee == null) {
                throw new NoSoportado("el metodo '" + nombre + "' no se pudo traducir");
            }
            emitirLlamadaDirecta(f, callee, args, res, out);
            return;
        }
        String cid = nuevoTemp(f, "int", null, 0);
        out.add(new Cuarteta("classid", receptor, null, cid));
        String fin = "Ldv" + (contadorEtiqueta++);
        int pos = 0;
        for (Map.Entry<String, List<Integer>> e : porImpl.entrySet()) {
            Fn callee = fns.get(e.getKey());
            if (callee == null) {
                throw new NoSoportado("el metodo '" + nombre + "' (" + e.getKey() + ") no se pudo traducir");
            }
            pos++;
            boolean ultimo = pos == porImpl.size();
            String sig = "Ldv" + (contadorEtiqueta++);
            if (!ultimo) {
                String acum = null;
                for (int id : e.getValue()) {
                    String t = nuevoTemp(f, "int", null, 0);
                    out.add(new Cuarteta("==", cid, String.valueOf(id), t));
                    if (acum == null) {
                        acum = t;
                    } else {
                        String t2 = nuevoTemp(f, "int", null, 0);
                        out.add(new Cuarteta("||", acum, t, t2));
                        acum = t2;
                    }
                }
                out.add(new Cuarteta("if_false", acum, null, sig));
            }
            emitirLlamadaDirecta(f, callee, args, res, out);
            if (!ultimo) {
                out.add(new Cuarteta("goto", null, null, fin));
                out.add(new Cuarteta("label", null, null, sig));
            }
        }
        out.add(new Cuarteta("label", null, null, fin));
    }

    // Emite la llamada directa a una funcion conocida.
    private void emitirLlamadaDirecta(Fn f, Fn callee, List<String> args, String res, List<Cuarteta> out) {
        if (args.size() != callee.params.size()) {
            throw new NoSoportado("llamada a '" + callee.nombre + "' con cantidad de argumentos inconsistente");
        }
        for (String a : args) {
            out.add(new Cuarteta("param", a, null, null));
        }
        String dest = null;
        if (res != null) {
            if (callee.tipoRet == null) {
                throw new NoSoportado("se usa el resultado de '" + callee.nombre + "', que no retorna valor");
            }
            f.tipo.putIfAbsent(res, callee.tipoRet);
            if (callee.dimsRet > 0) {
                f.dims.putIfAbsent(res, callee.dimsRet);
            }
            if (callee.claseRet != null) {
                f.clase.putIfAbsent(res, callee.claseRet);
            }
            dest = res;
        }
        out.add(new Cuarteta("call", callee.nombre, String.valueOf(args.size()), dest));
    }

    /** Etiqueta de la implementacion de 'nombre'/n con esa firma que usa la clase (la propia o la heredada mas cercana). */
    String implementacion(String clase, String nombre, int n, String firma) {
        SimboloClase k = global.buscarClase(clase);
        while (k != null) {
            for (SimboloInvocable m : k.buscarMetodos(nombre)) {
                if (m.getParametros().size() == n && Etiquetas.firma(m).equals(firma)) {
                    return Etiquetas.metodo(k, m);
                }
            }
            k = k.getSuperclase(global);
        }
        return null;
    }

    static final Set<String> COMPARACIONES = Set.of("<", ">", "<=", ">=", "==", "!=");
    static final Set<String> ARITMETICOS = Set.of("+", "-", "*", "/", "%");

    // Tipo requerido por una operacion.
    String necesitaTipo(Fn f, String op) {
        String t = tipoOp(f, op);
        if (t == null || "obj".equals(t)) {
            throw new NoSoportado("operando '" + op + "' de tipo desconocido o no soportado (objeto/subarreglo)");
        }
        return t;
    }

    // Funcion invocada por una cuarteta call.
    Fn llamado(Cuarteta c, Map<String, Fn> fns) {
        String nombre = c.getArg1();
        Fn callee = fns.get(nombre);
        if (callee == null) {
            throw new NoSoportado("llama a '" + nombre + "', que no se pudo traducir");
        }
        return callee;
    }

    /** Tipo escalar del modelo de un operando simple (literal, variable o elemento "a[i][j]" completo); null si no aplica. */
    String tipoOp(Fn f, String t) {
        if (t == null) {
            return null;
        }
        if ("null".equals(t)) {
            return "obj";
        }
        if (TiposC.LITERALES_VERDADERO.contains(t) || TiposC.LITERALES_FALSO.contains(t)) {
            return "int";
        }
        if (TiposC.ENTERO_LIT.matcher(t).matches()) {
            return "int";
        }
        if (TiposC.DECIMAL_LIT.matcher(t).matches()) {
            return "double";
        }
        if (TiposC.CARACTER_LIT.matcher(t).matches()) {
            return "char";
        }
        if (TiposC.CADENA_LIT.matcher(t).matches()) {
            return "char*";
        }
        int corchete = t.indexOf('[');
        if (corchete > 0 && t.endsWith("]")) {
            String base = t.substring(0, corchete);
            Integer d = f.dims.get(base);
            return d != null && d == indices(t).size() ? f.tipo.get(base) : null;
        }
        if (f.dims.containsKey(t)) {
            return null;
        }
        return f.tipo.get(t);
    }

    /** "entero", "Clase", "cadena[][]"... (Tipo.toString()) -> Tipo; null si no se conoce. */
    Tipo tipoDeTexto(String texto) {
        int dims = 0;
        String base = texto;
        while (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
            dims++;
        }
        Tipo b;
        switch (base) {
            case "entero": b = Tipo.ENTERO; break;
            case "decimal": b = Tipo.DECIMAL; break;
            case "booleano": b = Tipo.BOOLEANO; break;
            case "caracter": b = Tipo.CARACTER; break;
            case "cadena": b = Tipo.CADENA; break;
            default: b = global.resolverTipo(base);
        }
        if (b == null) {
            return null;
        }
        return dims > 0 ? Tipo.arreglo(b, dims) : b;
    }

    /** Tipo C de un tipo del modelo. */
    static String cTipo(String tipo) {
        return "obj".equals(tipo) ? "char*" : tipo;
    }

    /** Contenidos de los grupos [..] de "a[i][j]" (admite corchetes anidados). */
    List<String> indices(String t) {
        List<String> out = new ArrayList<>();
        int i = t.indexOf('[');
        while (i >= 0 && i < t.length()) {
            int nivel = 0;
            int j = i;
            for (; j < t.length(); j++) {
                char ch = t.charAt(j);
                if (ch == '[') {
                    nivel++;
                } else if (ch == ']') {
                    nivel--;
                    if (nivel == 0) {
                        break;
                    }
                }
            }
            if (j >= t.length()) {
                throw new NoSoportado("indice mal formado en '" + t + "'");
            }
            out.add(t.substring(i + 1, j));
            i = j + 1;
            if (i < t.length() && t.charAt(i) != '[') {
                throw new NoSoportado("acceso no soportado '" + t + "'");
            }
        }
        return out;
    }

}
