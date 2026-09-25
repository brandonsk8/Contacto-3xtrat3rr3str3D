package com.company.pig;

import com.company.semantico.GestorErrores;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Traduce un 'tipoPrimitivo'/'tipoDato' de Pig Latin (una de las 4
 * palabras en latin: numerus/textum/decimalis/littera, o el
 * IDENTIFICADOR de una estructura/clase importada) a un Tipo semantico.
 */
public class ResolvedorTipoPigLatin {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;

    public ResolvedorTipoPigLatin(TablaSimbolosGlobal global, GestorErrores errores) {
        this.global = global;
        this.errores = errores;
    }

    public Tipo mapearTipoPrimitivo(PigLatinParser.TipoPrimitivoContext ctx) {
        switch (ctx.getText()) {
            case "numerus": return Tipo.ENTERO;
            case "textum": return Tipo.CADENA;
            case "decimalis": return Tipo.DECIMAL;
            case "littera": return Tipo.CARACTER;
            default: throw new IllegalStateException("tipoPrimitivo no reconocido: " + ctx.getText());
        }
    }

    public Tipo resolverTipoDato(PigLatinParser.TipoDatoContext ctx) {
        if (ctx.tipoPrimitivo() != null) {
            return mapearTipoPrimitivo(ctx.tipoPrimitivo());
        }
        String nombre = ctx.IDENTIFICADOR().getText();
        Tipo tipo = global.resolverTipo(nombre);
        if (tipo == null) {
            reportarError(ctx, "tipo desconocido: '" + nombre + "' (revisa los 'import')");
            return Tipo.ERROR;
        }
        return tipo;
    }

    private void reportarError(ParserRuleContext ctx, String mensaje) {
        errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), mensaje);
    }
}