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

    public ResolvedorAccesoZetariano(TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas, ZetarianoAnalizador visitor) {
        this.global = global;
        this.errores = errores;
        this.cuartetas = cuartetas;
        this.visitor = visitor;
    }

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

        if (ctx.getChild(0).getText().equals("this")) {
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
                // No es variable/parametro: es una llamada a un metodo
                // PROPIO sin 'this.' explicito (equivale a this.nombreBase(...)).
                return resolverLlamadaImplicita(nombreBase, sufijos, linea, columna, claseActual);
            } else {
                // Tampoco es una llamada: puede ser un campo propio
                // referenciado sin 'this.' explicito.
                Simbolo campo = claseActual != null ? claseActual.buscarCampo(nombreBase) : null;
                if (campo == null) {
                    errores.reportar(linea, columna, "variable, campo o metodo no declarado: '" + nombreBase + "'");
                    return Operando.error();
                }
                tipoActual = campo.getTipo();
                textoActual = "this." + nombreBase;
            }
        }

        int i = 0;
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
                            sufijo.getStart().getLine(), sufijo.getStart().getCharPositionInLine());
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

                Simbolo campo = clase.buscarCampo(nombreMiembro);
                if (campo == null) {
                    reportarError(sufijo, "la clase '" + clase.getNombre() + "' no tiene un campo '" + nombreMiembro + "'");
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
        Operando resultado = resolverLlamadaMetodo(claseActual, nombreMetodo, "this", sufijos.get(0), linea, columna);
        if (resultado.esError() || sufijos.size() == 1) {
            return resultado;
        }
        // TODO: encadenar el resto de sufijos despues de una llamada implicita (this.metodo().campo...)
        return resultado;
    }

    private Operando resolverLlamadaMetodo(SimboloClase clase, String nombreMetodo, String textoBase,
                                           ZetarianoParser.SufijoAccesoContext sufijoLlamada, int linea, int columna) {
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
        SimboloInvocable metodo = buscarPorFirma(clase.buscarMetodos(nombreMetodo), tipos);
        if (metodo == null) {
            errores.reportar(linea, columna, "'" + clase.getNombre() + "' no tiene un metodo '" + nombreMetodo + "' con esos argumentos");
            return Operando.error();
        }

        for (Operando a : args) {
            cuartetas.agregar("param", a.getTexto(), null, null);
        }
        cuartetas.agregar("param", textoBase, null, null); // el objeto sobre el que se llama (el 'this' del metodo)

        if (metodo.getTipoRetorno().getCategoria() == Tipo.Categoria.VOID) {
            cuartetas.agregar("call", textoBase + "." + nombreMetodo, String.valueOf(args.size()), null);
            return Operando.sinValor();
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar("call", textoBase + "." + nombreMetodo, String.valueOf(args.size()), temporal);
        return new Operando(metodo.getTipoRetorno(), temporal);
    }

    private boolean contieneError(List<Operando> operandos) {
        for (Operando o : operandos) {
            if (o.esError()) {
                return true;
            }
        }
        return false;
    }

    private SimboloInvocable buscarPorFirma(List<SimboloInvocable> candidatos, List<Tipo> tipos) {
        for (SimboloInvocable c : candidatos) {
            if (c.mismaFirma(tipos)) {
                return c;
            }
        }
        return null;
    }

    private void reportarError(org.antlr.v4.runtime.ParserRuleContext ctx, String mensaje) {
        errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), mensaje);
    }

    private void reportarErrorEn(int linea, int columna, String mensaje) {
        errores.reportar(linea, columna, mensaje);
    }
}