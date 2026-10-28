package com.company.zetariano;

import com.company.ir.TablaCuartetas;
import com.company.semantico.GestorErrores;
import com.company.semantico.Operando;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolos;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import com.company.semantico.Visibilidad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resuelve un 'acceso' completo de Zetariano (variable, 'this', campo,
 * indice de arreglo, llamada a metodo encadenada o no, con o sin
 * 'this.' explicito) y las llamadas a metodo dentro de ese acceso.
 *
 * No extiende ningun Visitor de ANTLR: para visitar sub-expresiones
 * (el indice de un '[...]', los argumentos de una llamada) recibe una
 * referencia al ZetarianoAnalizador que la crea y le delega esos
 * visit(...). 'ambitoActual'/'claseActual' se reciben en cada llamada
 * en vez de guardarse como campo, porque cambian durante el recorrido.
 */
public class ResolvedorAccesoZetariano {

    private static final int SUFIJO_CAMPO = 0;
    private static final int SUFIJO_INDICE = 1;
    private static final int SUFIJO_LLAMADA = 2;

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final TablaCuartetas cuartetas;
    private final ZetarianoAnalizador visitor;

    // Resuelve accesos a campos, metodos, this/super y .length.
    public ResolvedorAccesoZetariano(TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas, ZetarianoAnalizador visitor) {
        this.global = global;
        this.errores = errores;
        this.cuartetas = cuartetas;
        this.visitor = visitor;
    }

    // Clasifica un sufijo: campo, indice o llamada.
    private int tipoDeSufijo(ZetarianoParser.SufijoAccesoContext ctx) {
        String primero = ctx.getChild(0).getText();
        if (primero.equals(".")) return SUFIJO_CAMPO;
        if (primero.equals("[")) return SUFIJO_INDICE;
        return SUFIJO_LLAMADA; // "("
    }

    /** Una llamada a metodo puede aparecer en medio de la cadena y seguir encadenandose (obj.metodo().otro()). */
    public Operando resolverAcceso(ZetarianoParser.AccesoContext ctx, TablaSimbolos ambitoActual, SimboloClase claseActual) {
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        List<ZetarianoParser.SufijoAccesoContext> sufijos = ctx.sufijoAcceso();

        Tipo tipoActual;
        String textoActual;
        int inicioSufijos = 0;

        if (ctx.getChild(0).getText().equals("super")) {
            SimboloClase padre = claseActual != null ? claseActual.getSuperclase(global) : null;
            if (padre == null) {
                reportarError(ctx, "'super' solo se puede usar dentro de una clase que hereda de otra ('extends')");
                return Operando.error();
            }
            if (sufijos.isEmpty() || tipoDeSufijo(sufijos.get(0)) != SUFIJO_CAMPO) {
                reportarError(ctx, "'super(...)' solo puede ser la primera sentencia de un constructor");
                return Operando.error();
            }
            tipoActual = Tipo.clase(padre.getNombre());
            textoActual = "this";
            boolean esLlamadaASuper = sufijos.size() >= 2 && tipoDeSufijo(sufijos.get(1)) == SUFIJO_LLAMADA;
            if (esLlamadaASuper) {
                // super.metodo(...): llama a la version del padre, sin despacho dinamico
                Operando resultado = resolverLlamadaMetodo(padre, sufijos.get(0).IDENTIFICADOR().getText(), "this", sufijos.get(1),
                        linea, columna, claseActual, true);
                if (resultado.esError() || !resultado.esValor()) {
                    if (resultado.esValor() == false && !resultado.esError() && sufijos.size() > 2) {
                        reportarError(sufijos.get(2), "no se puede seguir accediendo despues de llamar a un metodo 'void'");
                        return Operando.error();
                    }
                    return resultado;
                }
                tipoActual = resultado.getTipo();
                textoActual = resultado.getTexto();
                inicioSufijos = 2;
            }
        } else if (ctx.getChild(0).getText().equals("this")) {
            if (claseActual == null) {
                reportarError(ctx, "'this' no se puede usar aqui");
                return Operando.error();
            }
            tipoActual = Tipo.clase(claseActual.getNombre());
            textoActual = "this";
        } else {
            String nombreBase = ctx.IDENTIFICADOR().getText();
            Simbolo variable = ambitoActual.buscar(nombreBase);
            if (variable != null) {
                tipoActual = variable.getTipo();
                textoActual = nombreBase;
            } else if (!sufijos.isEmpty() && tipoDeSufijo(sufijos.get(0)) == SUFIJO_LLAMADA) {
                // 'println'/'print' son funciones globales de impresion (igual
                // que 'imprimir' en Y?), no un metodo de la clase - se
                // resuelven ANTES de asumir que es this.nombreBase(...).
                if (nombreBase.equals("println") || nombreBase.equals("print")) {
                    if (sufijos.size() > 1) {
                        reportarErrorEn(linea, columna, "no se puede seguir accediendo despues de '" + nombreBase + "(...)'");
                        return Operando.error();
                    }
                    return resolverLlamadaImpresion(sufijos.get(0), linea, columna, nombreBase.equals("println"));
                }
                // No es variable/parametro: es una llamada a un metodo
                // PROPIO sin 'this.' explicito (equivale a this.nombreBase(...)).
                return resolverLlamadaImplicita(nombreBase, sufijos, linea, columna, claseActual);
            } else {
                // Tampoco es una llamada: puede ser un campo propio
                // referenciado sin 'this.' explicito.
                Simbolo campo = claseActual != null ? claseActual.buscarCampoHeredado(nombreBase, global) : null;
                if (campo == null) {
                    errores.reportar(linea, columna, "variable, campo o metodo no declarado: '" + nombreBase + "'");
                    return Operando.error();
                }
                tipoActual = campo.getTipo();
                textoActual = "this." + nombreBase;
            }
        }

        int i = inicioSufijos;
        while (i < sufijos.size()) {
            ZetarianoParser.SufijoAccesoContext sufijo = sufijos.get(i);
            int tipoSufijo = tipoDeSufijo(sufijo);

            if (tipoSufijo == SUFIJO_INDICE) {
                if (tipoActual.getCategoria() != Tipo.Categoria.ARREGLO) {
                    reportarError(sufijo, "'" + textoActual + "' no es un arreglo, no se puede indexar");
                    return Operando.error();
                }
                Operando indice = visitor.visit(sufijo.expresion());
                if (indice.esValor() && indice.getTipo().getCategoria() != Tipo.Categoria.ENTERO) {
                    reportarError(sufijo, "el indice debe ser entero, no " + indice.getTipo());
                }
                Tipo base = tipoActual.getTipoBase();
                tipoActual = tipoActual.getDimensiones() > 1 ? Tipo.arreglo(base, tipoActual.getDimensiones() - 1) : base;
                textoActual = textoActual + "[" + (indice.esValor() ? indice.getTexto() : "?") + "]";
                i++;
            } else if (tipoSufijo == SUFIJO_CAMPO) {
                String nombreMiembro = sufijo.IDENTIFICADOR().getText();
                boolean sigueLlamada = i + 1 < sufijos.size() && tipoDeSufijo(sufijos.get(i + 1)) == SUFIJO_LLAMADA;
                if (tipoActual.getCategoria() == Tipo.Categoria.ARREGLO && nombreMiembro.equals("length") && !sigueLlamada) {
                    // arr.length (dimension 0) o arr[i].length (dimension 1, ...): se cuentan los indices ya aplicados
                    int indicesAplicados = 0;
                    int prof = 0;
                    for (char ch : textoActual.toCharArray()) {
                        if (ch == '[') {
                            if (prof == 0) {
                                indicesAplicados++;
                            }
                            prof++;
                        } else if (ch == ']') {
                            prof--;
                        }
                    }
                    int corte = textoActual.indexOf('[');
                    String baseArreglo = corte < 0 ? textoActual : textoActual.substring(0, corte);
                    String temporal = cuartetas.nuevoTemporal();
                    cuartetas.agregar("len", baseArreglo, String.valueOf(indicesAplicados), temporal);
                    tipoActual = Tipo.ENTERO;
                    textoActual = temporal;
                    i++;
                    continue;
                }
                if (tipoActual.getCategoria() != Tipo.Categoria.CLASE) {
                    reportarError(sufijo, "'" + textoActual + "' no es un objeto, no tiene miembro '" + nombreMiembro + "'");
                    return Operando.error();
                }
                SimboloClase clase = global.buscarClase(tipoActual.getNombreDefinido());
                if (clase == null) {
                    return Operando.error(); // no deberia pasar
                }

                boolean siguienteEsLlamada = i + 1 < sufijos.size() && tipoDeSufijo(sufijos.get(i + 1)) == SUFIJO_LLAMADA;
                if (siguienteEsLlamada) {
                    Operando resultado = resolverLlamadaMetodo(clase, nombreMiembro, textoActual, sufijos.get(i + 1),
                            sufijo.getStart().getLine(), sufijo.getStart().getCharPositionInLine(), claseActual);
                    if (resultado.esError()) {
                        return resultado;
                    }
                    if (!resultado.esValor()) { // metodo void: no se puede seguir encadenando nada despues
                        if (i + 2 < sufijos.size()) {
                            reportarError(sufijos.get(i + 2), "no se puede seguir accediendo despues de llamar a un metodo 'void'");
                            return Operando.error();
                        }
                        return resultado;
                    }
                    tipoActual = resultado.getTipo();
                    textoActual = resultado.getTexto();
                    i += 2;
                    continue;
                }

                Simbolo campo = clase.buscarCampoHeredado(nombreMiembro, global);
                if (campo == null) {
                    reportarError(sufijo, "la clase '" + clase.getNombre() + "' no tiene un campo '" + nombreMiembro + "'");
                    return Operando.error();
                }
                SimboloClase declarante = declaranteDeCampo(clase, nombreMiembro);
                if (!accesoPermitido(campo, declarante, claseActual)) {
                    reportarError(sufijo, "el campo '" + nombreMiembro + "' es " + campo.getVisibilidad().name().toLowerCase()
                            + " en '" + declarante.getNombre() + "', no se puede acceder desde aqui");
                    return Operando.error();
                }
                tipoActual = campo.getTipo();
                textoActual = textoActual + "." + nombreMiembro;
                i++;
            } else {
                reportarError(sufijo, "no se puede llamar a '" + textoActual + "' aqui");
                return Operando.error();
            }
        }

        return new Operando(tipoActual, textoActual);
    }

    /**
     * 'println'/'print' - funcion global de impresion de Zetariano (no es
     * un metodo de ninguna clase). Cada argumento genera su propia cuarteta
     * "print", igual que 'imprimir()' en Y? - por eso, igual que alla, no
     * hay distincion real entre 'print' (sin salto de linea) y 'println'
     * (con salto): el backend (EmisorSentencias, caso "print") siempre
     * emite un printf con '\n' al final. Es una limitacion conocida: varias
     * llamadas a 'print' seguidas van a salir en lineas separadas en vez de
     * concatenadas en una sola linea.
     */
    private Operando resolverLlamadaImpresion(ZetarianoParser.SufijoAccesoContext sufijoLlamada, int linea, int columna, boolean conSalto) {
        List<ZetarianoParser.ExpresionContext> argsCtx = sufijoLlamada.listaArgumentos() != null
                ? sufijoLlamada.listaArgumentos().expresion() : Collections.<ZetarianoParser.ExpresionContext>emptyList();
        if (argsCtx.isEmpty()) {
            reportarErrorEn(linea, columna, "'println'/'print' necesita al menos un argumento");
            return Operando.error();
        }
        for (int i = 0; i < argsCtx.size(); i++) {
            Operando arg = visitor.visit(argsCtx.get(i));
            if (!arg.esValor()) {
                return Operando.error();
            }
            // println termina la linea en su ultimo argumento ("nl"); print no.
            cuartetas.agregar("print", arg.getTexto(), null, conSalto && i == argsCtx.size() - 1 ? "nl" : null);
        }
        return Operando.sinValor();
    }

    private Operando resolverLlamadaImplicita(String nombreMetodo, List<ZetarianoParser.SufijoAccesoContext> sufijos,
                                              int linea, int columna, SimboloClase claseActual) {
        if (sufijos.size() > 1 && tipoDeSufijo(sufijos.get(1)) != SUFIJO_CAMPO) {
            reportarErrorEn(linea, columna, "no se puede seguir accediendo asi despues de llamar a '" + nombreMetodo + "(...)'");
            return Operando.error();
        }
        if (claseActual == null) {
            errores.reportar(linea, columna, "no se puede llamar a '" + nombreMetodo + "' aqui");
            return Operando.error();
        }
        Operando resultado = resolverLlamadaMetodo(claseActual, nombreMetodo, "this", sufijos.get(0), linea, columna, claseActual);
        if (resultado.esError() || sufijos.size() == 1) {
            return resultado;
        }
        // TODO: encadenar el resto de sufijos despues de una llamada implicita (this.metodo().campo...)
        return resultado;
    }

    private Operando resolverLlamadaMetodo(SimboloClase clase, String nombreMetodo, String textoBase,
                                           ZetarianoParser.SufijoAccesoContext sufijoLlamada, int linea, int columna, SimboloClase claseActual) {
        return resolverLlamadaMetodo(clase, nombreMetodo, textoBase, sufijoLlamada, linea, columna, claseActual, false);
    }

    private Operando resolverLlamadaMetodo(SimboloClase clase, String nombreMetodo, String textoBase,
                                           ZetarianoParser.SufijoAccesoContext sufijoLlamada, int linea, int columna, SimboloClase claseActual,
                                           boolean esSuper) {
        List<ZetarianoParser.ExpresionContext> argsCtx = sufijoLlamada.listaArgumentos() != null
                ? sufijoLlamada.listaArgumentos().expresion() : Collections.<ZetarianoParser.ExpresionContext>emptyList();
        List<Operando> args = new ArrayList<>();
        for (ZetarianoParser.ExpresionContext a : argsCtx) {
            args.add(visitor.visit(a));
        }
        if (contieneError(args)) {
            return Operando.error();
        }

        List<Tipo> tipos = new ArrayList<>();
        for (Operando a : args) {
            tipos.add(a.getTipo());
        }
        SimboloInvocable metodo = buscarPorFirma(clase.buscarMetodosHeredados(nombreMetodo, global), tipos);
        if (metodo == null) {
            errores.reportar(linea, columna, "'" + clase.getNombre() + "' no tiene un metodo '" + nombreMetodo + "' con esos argumentos");
            return Operando.error();
        }
        SimboloClase declarante = declaranteDeMetodo(clase, nombreMetodo, tipos);
        if (declarante != null && !accesoPermitido(metodo, declarante, claseActual)) {
            errores.reportar(linea, columna, "el metodo '" + nombreMetodo + "' es " + metodo.getVisibilidad().name().toLowerCase()
                    + " en '" + declarante.getNombre() + "', no se puede llamar desde aqui");
            return Operando.error();
        }

        for (Operando a : args) {
            cuartetas.agregar("param", a.getTexto(), null, null);
        }
        cuartetas.agregar("param", textoBase, null, null); // el objeto sobre el que se llama (el 'this' del metodo)

        String destinoLlamada = (esSuper ? "super" : textoBase) + "." + nombreMetodo + "#" + com.company.semantico.Etiquetas.firma(metodo);
        if (metodo.getTipoRetorno().getCategoria() == Tipo.Categoria.VOID) {
            cuartetas.agregar("call", destinoLlamada, String.valueOf(args.size()), null);
            return Operando.sinValor();
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar("call", destinoLlamada, String.valueOf(args.size()), temporal);
        return new Operando(metodo.getTipoRetorno(), temporal);
    }

    /**
     * 'super(args)' dentro de un constructor: resuelve el constructor del padre
     * por firma, valida su visibilidad y emite  param args..., param this, call super.ctor n.
     */
    public boolean resolverLlamadaSuperConstructor(ZetarianoParser.ListaArgumentosContext listaArgs, SimboloClase claseActual,
                                                   int linea, int columna) {
        SimboloClase padre = claseActual.getSuperclase(global);
        if (padre == null) {
            errores.reportar(linea, columna, "'super(...)' solo se puede usar en una clase que hereda de otra ('extends')");
            return false;
        }
        List<ZetarianoParser.ExpresionContext> argsCtx = listaArgs != null ? listaArgs.expresion()
                : Collections.<ZetarianoParser.ExpresionContext>emptyList();
        List<Operando> args = new ArrayList<>();
        for (ZetarianoParser.ExpresionContext a : argsCtx) {
            args.add(visitor.visit(a));
        }
        if (contieneError(args)) {
            return false;
        }
        List<Tipo> tipos = new ArrayList<>();
        for (Operando a : args) {
            tipos.add(a.getTipo());
        }
        return emitirLlamadaConstructorPadre(padre, args, tipos, claseActual, linea, columna);
    }

    /**
     * 'this(args)' como primera sentencia de un constructor: delega en otro constructor de la
     * MISMA clase (resuelto por firma). Emite  param args..., param this, call this.<init>#firma n.
     */
    public boolean resolverLlamadaThisConstructor(ZetarianoParser.ListaArgumentosContext listaArgs, SimboloClase claseActual,
                                                  SimboloInvocable constructorActual, int linea, int columna) {
        List<ZetarianoParser.ExpresionContext> argsCtx = listaArgs != null ? listaArgs.expresion()
                : Collections.<ZetarianoParser.ExpresionContext>emptyList();
        List<Operando> args = new ArrayList<>();
        for (ZetarianoParser.ExpresionContext a : argsCtx) {
            args.add(visitor.visit(a));
        }
        if (contieneError(args)) {
            return false;
        }
        List<Tipo> tipos = new ArrayList<>();
        for (Operando a : args) {
            tipos.add(a.getTipo());
        }
        SimboloInvocable ctor = buscarPorFirma(claseActual.getConstructores(), tipos);
        if (ctor == null) {
            errores.reportar(linea, columna, "'" + claseActual.getNombre() + "' no tiene un constructor que reciba " + tipos.size()
                    + " argumento(s) de esos tipos (usa 'this(...)' con los argumentos correctos)");
            return false;
        }
        if (ctor == constructorActual) {
            errores.reportar(linea, columna, "un constructor no puede llamarse a si mismo con 'this(...)'");
            return false;
        }
        for (Operando a : args) {
            cuartetas.agregar("param", a.getTexto(), null, null);
        }
        cuartetas.agregar("param", "this", null, null);
        cuartetas.agregar("call", "this.<init>#" + com.company.semantico.Etiquetas.firma(ctor), String.valueOf(args.size()), null);
        return true;
    }

    /** 'super()' implicito: el padre debe tener un constructor sin parametros (o el de por defecto). */
    public boolean resolverSuperImplicito(SimboloClase claseActual, int linea, int columna) {
        SimboloClase padre = claseActual.getSuperclase(global);
        if (padre == null) {
            return true;
        }
        if (buscarPorFirma(padre.getConstructores(), new ArrayList<>()) == null) {
            return true; // el padre no tiene constructor sin parametros: no hay 'super()' implicito que llamar
        }
        return emitirLlamadaConstructorPadre(padre, new ArrayList<>(), new ArrayList<>(), claseActual, linea, columna);
    }

    private boolean emitirLlamadaConstructorPadre(SimboloClase padre, List<Operando> args, List<Tipo> tipos,
                                                  SimboloClase claseActual, int linea, int columna) {
        SimboloInvocable ctor = buscarPorFirma(padre.getConstructores(), tipos);
        if (ctor == null) {
            errores.reportar(linea, columna, "'" + padre.getNombre() + "' no tiene un constructor que reciba " + tipos.size()
                    + " argumento(s) de esos tipos (usa 'super(...)' con los argumentos correctos)");
            return false;
        }
        if (!accesoPermitido(ctor, padre, claseActual)) {
            errores.reportar(linea, columna, "el constructor de '" + padre.getNombre() + "' es "
                    + ctor.getVisibilidad().name().toLowerCase() + ", no se puede llamar desde '" + claseActual.getNombre() + "'");
            return false;
        }
        for (Operando a : args) {
            cuartetas.agregar("param", a.getTexto(), null, null);
        }
        cuartetas.agregar("param", "this", null, null);
        cuartetas.agregar("call", "super.<init>#" + com.company.semantico.Etiquetas.firma(ctor), String.valueOf(args.size()), null);
        return true;
    }

    /** La clase que realmente declara el campo (la propia o la primera superclase que lo tenga). */
    private SimboloClase declaranteDeCampo(SimboloClase clase, String nombre) {
        SimboloClase actual = clase;
        while (actual != null) {
            if (actual.buscarCampo(nombre) != null) {
                return actual;
            }
            actual = actual.getSuperclase(global);
        }
        return null;
    }

    /** La clase que realmente declara el metodo con esa firma. */
    private SimboloClase declaranteDeMetodo(SimboloClase clase, String nombre, List<Tipo> tipos) {
        SimboloClase actual = clase;
        while (actual != null) {
            for (SimboloInvocable m : actual.buscarMetodos(nombre)) {
                if (m.mismaFirma(tipos)) {
                    return actual;
                }
            }
            actual = actual.getSuperclase(global);
        }
        return null;
    }

    /** PRIVATE: solo la clase declarante; PROTECTED: declarante y subclases; PUBLIC/DEFAULT: siempre. */
    private boolean accesoPermitido(Simbolo miembro, SimboloClase declarante, SimboloClase claseActual) {
        if (declarante == null) {
            return true;
        }
        Visibilidad v = miembro.getVisibilidad();
        if (claseActual == null) {
            return v == Visibilidad.PUBLIC;
        }
        boolean mismaClase = claseActual.getNombre().equals(declarante.getNombre());
        if (v == Visibilidad.PRIVATE) {
            return mismaClase;
        }
        boolean mismoPaquete = claseActual.getPaquete().equals(declarante.getPaquete());
        if (v == Visibilidad.PROTECTED) {
            // mismo paquete, o subclase (aunque este en otro paquete)
            return mismoPaquete || claseActual.esSubclaseDeOIgual(declarante.getNombre(), global);
        }
        if (v == Visibilidad.DEFAULT) {
            // sin modificador: solo dentro del mismo paquete
            return mismoPaquete;
        }
        return true;
    }

    // True si algun operando ya es error.
    private boolean contieneError(List<Operando> operandos) {
        for (Operando o : operandos) {
            if (o.esError()) {
                return true;
            }
        }
        return false;
    }

    // Elige el metodo/constructor segun tipos (sobrecarga).
    private SimboloInvocable buscarPorFirma(List<SimboloInvocable> candidatos, List<Tipo> tipos) {
        for (SimboloInvocable c : candidatos) {
            if (c.mismaFirma(tipos)) {
                return c;
            }
        }
        for (SimboloInvocable c : candidatos) {
            if (c.aceptaArgumentos(tipos, global)) {
                return c;
            }
        }
        return null;
    }

    // Registra un error semantico.
    private void reportarError(org.antlr.v4.runtime.ParserRuleContext ctx, String mensaje) {
        errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), mensaje);
    }

    // Registra un error en linea/columna.
    private void reportarErrorEn(int linea, int columna, String mensaje) {
        errores.reportar(linea, columna, mensaje);
    }
}