package com.company.pig;

import com.company.ir.TablaCuartetas;
import com.company.semantico.GestorErrores;
import com.company.semantico.Operando;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloEstructura;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolos;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resuelve un 'acceso' completo de Pig Latin (variable, campo, indice
 * de arreglo, llamada a metodo encadenada, o llamada a una funcion
 * global suelta) y las llamadas que aparecen dentro de ese acceso.
 *
 * Como Pig Latin no tiene metodos propios (no hay 'this' implicito), la
 * unica ambiguedad extra a resolver es que un identificador base seguido
 * de '(' solo puede ser una funcion global, nunca un metodo.
 *
 * No extiende ningun Visitor de ANTLR: recibe una referencia al
 * PigLatinAnalizador que la crea para delegarle el visit(...) de
 * sub-expresiones (indices, argumentos de llamadas).
 */
public class ResolvedorAccesoPigLatin {

    private static final int SUFIJO_CAMPO = 0;
    private static final int SUFIJO_INDICE = 1;
    private static final int SUFIJO_LLAMADA = 2;

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final TablaCuartetas cuartetas;
    private final PigLatinAnalizador visitor;

    public ResolvedorAccesoPigLatin(TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas, PigLatinAnalizador visitor) {
        this.global = global;
        this.errores = errores;
        this.cuartetas = cuartetas;
        this.visitor = visitor;
    }

    private int tipoDeSufijo(PigLatinParser.SufijoAccesoContext ctx) {
        String primero = ctx.getChild(0).getText();
        if (primero.equals(".")) return SUFIJO_CAMPO;
        if (primero.equals("[")) return SUFIJO_INDICE;
        return SUFIJO_LLAMADA;
    }

    public Operando resolverAcceso(PigLatinParser.AccesoContext ctx, TablaSimbolos ambitoActual) {
        String nombreBase = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        List<PigLatinParser.SufijoAccesoContext> sufijos = ctx.sufijoAcceso();

        if (!sufijos.isEmpty() && tipoDeSufijo(sufijos.get(0)) == SUFIJO_LLAMADA) {
            if (sufijos.size() > 1) {
                reportarError(ctx, "no se puede seguir accediendo despues de llamar a una funcion: '" + nombreBase + "(...)'");
                return Operando.error();
            }
            return resolverLlamadaFuncionGlobal(nombreBase, sufijos.get(0), linea, columna);
        }

        Simbolo simbolo = ambitoActual.buscar(nombreBase);
        if (simbolo == null) {
            errores.reportar(linea, columna, "variable no declarada: '" + nombreBase + "'");
            return Operando.error();
        }

        Tipo tipoActual = simbolo.getTipo();
        String textoActual = nombreBase;

        int i = 0;
        while (i < sufijos.size()) {
            PigLatinParser.SufijoAccesoContext sufijo = sufijos.get(i);
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
                boolean siguienteEsLlamada = (i + 1 < sufijos.size()) && tipoDeSufijo(sufijos.get(i + 1)) == SUFIJO_LLAMADA;

                if (siguienteEsLlamada) {
                    if (tipoActual.getCategoria() != Tipo.Categoria.CLASE) {
                        reportarError(sufijo, "'" + textoActual + "' no es un objeto, no se le puede llamar '" + nombreMiembro + "(...)'");
                        return Operando.error();
                    }
                    SimboloClase clase = global.buscarClase(tipoActual.getNombreDefinido());
                    Operando resultado = resolverLlamadaMetodo(clase, nombreMiembro, textoActual, sufijos.get(i + 1), sufijo);
                    if (resultado.esError()) {
                        return resultado;
                    }
                    if (resultado.esSinValor() && i + 2 < sufijos.size()) {
                        reportarError(sufijo, "no se puede seguir accediendo despues de llamar a un metodo que no retorna nada");
                        return Operando.error();
                    }
                    tipoActual = resultado.esValor() ? resultado.getTipo() : Tipo.VOID;
                    textoActual = resultado.esValor() ? resultado.getTexto() : textoActual + "." + nombreMiembro + "(...)";
                    i += 2;
                } else {
                    if (tipoActual.getCategoria() != Tipo.Categoria.ESTRUCTURA && tipoActual.getCategoria() != Tipo.Categoria.CLASE) {
                        reportarError(sufijo, "'" + textoActual + "' no es una estructura ni un objeto, no tiene campo '" + nombreMiembro + "'");
                        return Operando.error();
                    }
                    Simbolo campo = buscarCampo(tipoActual, nombreMiembro);
                    if (campo == null) {
                        reportarError(sufijo, "'" + textoActual + "' no tiene un campo '" + nombreMiembro + "'");
                        return Operando.error();
                    }
                    tipoActual = campo.getTipo();
                    textoActual = textoActual + "." + nombreMiembro;
                    i++;
                }
            } else {
                reportarError(sufijo, "no se puede llamar a '" + textoActual + "' directamente (le falta un '.metodo' antes)");
                return Operando.error();
            }
        }

        return new Operando(tipoActual, textoActual);
    }

    private Simbolo buscarCampo(Tipo tipoActual, String nombreMiembro) {
        if (tipoActual.getCategoria() == Tipo.Categoria.ESTRUCTURA) {
            SimboloEstructura estructura = global.buscarEstructura(tipoActual.getNombreDefinido());
            return estructura != null ? estructura.buscarCampo(nombreMiembro) : null;
        }
        if (tipoActual.getCategoria() == Tipo.Categoria.CLASE) {
            SimboloClase clase = global.buscarClase(tipoActual.getNombreDefinido());
            return clase != null ? clase.buscarCampo(nombreMiembro) : null;
        }
        return null;
    }

    private Operando resolverLlamadaMetodo(SimboloClase clase, String nombreMetodo, String textoBase,
                                           PigLatinParser.SufijoAccesoContext sufijoLlamada, ParserRuleContext ctxError) {
        if (clase == null) {
            reportarError(ctxError, "no se pudo resolver la clase de '" + textoBase + "'");
            return Operando.error();
        }
        List<PigLatinParser.ExpresionContext> argsCtx = sufijoLlamada.listaArgumentos() != null
                ? sufijoLlamada.listaArgumentos().expresion()
                : Collections.<PigLatinParser.ExpresionContext>emptyList();
        List<Operando> args = new ArrayList<>();
        for (PigLatinParser.ExpresionContext a : argsCtx) {
            args.add(visitor.visit(a));
        }
        if (contieneError(args)) {
            return Operando.error();
        }
        SimboloInvocable metodo = buscarPorFirma(clase.buscarMetodos(nombreMetodo), tiposDe(args));
        if (metodo == null) {
            reportarError(ctxError, "'" + clase.getNombre() + "' no tiene un metodo '" + nombreMetodo + "' que reciba esos " + args.size() + " argumento(s)");
            return Operando.error();
        }
        for (Operando a : args) {
            cuartetas.agregar("param", a.getTexto(), null, null);
        }
        cuartetas.agregar("param", textoBase, null, null); // receptor implicito
        if (metodo.getTipoRetorno().getCategoria() == Tipo.Categoria.VOID) {
            cuartetas.agregar("call", textoBase + "." + nombreMetodo, String.valueOf(args.size()), null);
            return Operando.sinValor();
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar("call", textoBase + "." + nombreMetodo, String.valueOf(args.size()), temporal);
        return new Operando(metodo.getTipoRetorno(), temporal);
    }

    private Operando resolverLlamadaFuncionGlobal(String nombre, PigLatinParser.SufijoAccesoContext sufijoLlamada, int linea, int columna) {
        List<PigLatinParser.ExpresionContext> argsCtx = sufijoLlamada.listaArgumentos() != null
                ? sufijoLlamada.listaArgumentos().expresion()
                : Collections.<PigLatinParser.ExpresionContext>emptyList();
        List<Operando> args = new ArrayList<>();
        for (PigLatinParser.ExpresionContext a : argsCtx) {
            args.add(visitor.visit(a));
        }

        SimboloInvocable funcion = global.buscarFuncion(nombre);
        if (funcion == null) {
            errores.reportar(linea, columna, "funcion no declarada: '" + nombre + "' (revisa los 'import')");
            return Operando.error();
        }
        if (args.size() != funcion.getParametros().size()) {
            errores.reportar(linea, columna, "'" + nombre + "' espera " + funcion.getParametros().size()
                    + " argumento(s), se recibieron " + args.size());
            return Operando.error();
        }
        for (int i = 0; i < args.size(); i++) {
            Operando arg = args.get(i);
            Tipo esperado = funcion.getParametros().get(i).getTipo();
            if (arg.esValor() && !esperado.esCompatibleCon(arg.getTipo())) {
                errores.reportar(linea, columna, "argumento " + (i + 1) + " de '" + nombre + "' deberia ser "
                        + esperado + ", se recibio " + arg.getTipo());
            }
        }
        for (Operando arg : args) {
            if (arg.esValor()) {
                cuartetas.agregar("param", arg.getTexto(), null, null);
            }
        }
        if (funcion.getTipoRetorno().getCategoria() == Tipo.Categoria.VOID) {
            cuartetas.agregar("call", nombre, String.valueOf(args.size()), null);
            return Operando.sinValor();
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar("call", nombre, String.valueOf(args.size()), temporal);
        return new Operando(funcion.getTipoRetorno(), temporal);
    }

    private boolean contieneError(List<Operando> args) {
        for (Operando a : args) {
            if (a.esError()) return true;
        }
        return false;
    }

    private List<Tipo> tiposDe(List<Operando> args) {
        List<Tipo> tipos = new ArrayList<>();
        for (Operando a : args) {
            tipos.add(a.getTipo());
        }
        return tipos;
    }

    private SimboloInvocable buscarPorFirma(List<SimboloInvocable> candidatos, List<Tipo> tipos) {
        for (SimboloInvocable c : candidatos) {
            if (c.mismaFirma(tipos)) {
                return c;
            }
        }
        return null;
    }

    private void reportarError(ParserRuleContext ctx, String mensaje) {
        errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), mensaje);
    }
}