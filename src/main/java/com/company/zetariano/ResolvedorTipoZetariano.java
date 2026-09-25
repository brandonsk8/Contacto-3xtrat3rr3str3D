package com.company.zetariano;

import com.company.semantico.GestorErrores;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Traduce un 'tipo'/'tipoBase' de Zetariano (un primitivo de Java como
 * 'int'/'String', o el IDENTIFICADOR de otra clase ya conocida en
 * TablaSimbolosGlobal) a un Tipo semantico.
 *
 * La usan RegistradorFirmasZetariano (para campos/parametros/retorno) y
 * ZetarianoAnalizador (para variables locales y 'new tipo[...]').
 */
public class ResolvedorTipoZetariano {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;

    public ResolvedorTipoZetariano(TablaSimbolosGlobal global, GestorErrores errores) {
        this.global = global;
        this.errores = errores;
    }

    public Tipo resolverTipo(ZetarianoParser.TipoContext ctx) {
        Tipo base = resolverTipoBase(ctx.tipoBase());
        int dims = ctx.CORCHETE_ABRE().size();
        return dims > 0 ? Tipo.arreglo(base, dims) : base;
    }

    public Tipo resolverTipoBase(ZetarianoParser.TipoBaseContext ctx) {
        if (ctx.IDENTIFICADOR() != null) {
            String nombre = ctx.IDENTIFICADOR().getText();
            Tipo tipo = global.resolverTipo(nombre);
            if (tipo == null) {
                reportarError(ctx, "tipo desconocido: '" + nombre + "'");
                return Tipo.ERROR;
            }
            return tipo;
        }
        switch (ctx.getText()) {
            case "int": return Tipo.ENTERO;
            case "double": return Tipo.DECIMAL;
            case "char": return Tipo.CARACTER;
            case "boolean": return Tipo.BOOLEANO;
            case "String": return Tipo.CADENA;
            default: throw new IllegalStateException("tipoBase no reconocido: " + ctx.getText());
        }
    }

    private void reportarError(ParserRuleContext ctx, String mensaje) {
        errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), mensaje);
    }
}