package com.company.zetariano;

import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.GestorErrores;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;

import java.util.ArrayList;
import java.util.List;

/**
 * Pasada 1 sobre un archivo Zetariano: un archivo Zetariano es siempre
 * una sola clase publica, asi que esta clase registra UNA SimboloClase
 * con sus campos, constructores (con sobrecarga) y metodos (con
 * sobrecarga), sin mirar todavia el cuerpo de ningun constructor/metodo
 * (eso es ZetarianoAnalizador, pasada 2).
 *
 * No extiende ningun Visitor de ANTLR: solo recorre 'programa' y su
 * lista fija de 'miembro'.
 */
public class RegistradorFirmasZetariano {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final ResolvedorTipoZetariano resolvedorTipo;

    public RegistradorFirmasZetariano(TablaSimbolosGlobal global, GestorErrores errores) {
        this.global = global;
        this.errores = errores;
        this.resolvedorTipo = new ResolvedorTipoZetariano(global, errores);
    }

    /**
     * @return la SimboloClase registrada, para que quien orquesta la
     *         pasada 2 (ZetarianoAnalizador) no tenga que volver a
     *         buscarla en TablaSimbolosGlobal.
     */
    public SimboloClase registrar(ZetarianoParser.ProgramaContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        SimboloClase clase = new SimboloClase(nombre, linea, columna);
        if (!global.registrarClase(clase)) {
            errores.reportar(linea, columna, "ya existe un tipo (estructura o clase) llamado '" + nombre + "'");
        }

        for (ZetarianoParser.MiembroContext miembro : ctx.miembro()) {
            if (miembro.declaracionCampo() != null) {
                registrarCampo(clase, miembro.declaracionCampo());
            } else if (miembro.constructor() != null) {
                registrarConstructor(clase, miembro.constructor());
            } else if (miembro.metodo() != null) {
                registrarMetodo(clase, miembro.metodo());
            }
        }
        return clase;
    }

    private void registrarCampo(SimboloClase clase, ZetarianoParser.DeclaracionCampoContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        Tipo tipo = resolvedorTipo.resolverTipo(ctx.tipo());
        Simbolo campo = new Simbolo(nombre, tipo, CategoriaSimbolo.CAMPO, linea, columna);
        if (!clase.agregarCampo(campo)) {
            errores.reportar(linea, columna, "el campo '" + nombre + "' ya existe en la clase '" + clase.getNombre() + "'");
        }
    }

    private void registrarConstructor(SimboloClase clase, ZetarianoParser.ConstructorContext ctx) {
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        List<Simbolo> parametros = convertirParametros(ctx.listaParametros());

        SimboloInvocable constructor = new SimboloInvocable(clase.getNombre(), CategoriaSimbolo.CONSTRUCTOR, parametros, Tipo.VOID, linea, columna);
        if (!clase.agregarConstructor(constructor)) {
            errores.reportar(linea, columna, "ya existe un constructor de '" + clase.getNombre() + "' con esos mismos tipos de parametro");
        }
    }

    private void registrarMetodo(SimboloClase clase, ZetarianoParser.MetodoContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        List<Simbolo> parametros = convertirParametros(ctx.listaParametros());
        Tipo tipoRetorno = resolverTipoRetorno(ctx.tipoRetorno());

        SimboloInvocable metodo = new SimboloInvocable(nombre, CategoriaSimbolo.METODO, parametros, tipoRetorno, linea, columna);
        if (!clase.agregarMetodo(metodo)) {
            errores.reportar(linea, columna, "ya existe un metodo '" + nombre + "' en '" + clase.getNombre() + "' con esos mismos tipos de parametro");
        }
    }

    private List<Simbolo> convertirParametros(ZetarianoParser.ListaParametrosContext ctx) {
        List<Simbolo> parametros = new ArrayList<>();
        if (ctx != null) {
            for (ZetarianoParser.ParametroContext p : ctx.parametro()) {
                Tipo tipo = resolvedorTipo.resolverTipo(p.tipo());
                parametros.add(new Simbolo(p.IDENTIFICADOR().getText(), tipo, CategoriaSimbolo.PARAMETRO,
                        p.getStart().getLine(), p.getStart().getCharPositionInLine()));
            }
        }
        return parametros;
    }

    private Tipo resolverTipoRetorno(ZetarianoParser.TipoRetornoContext ctx) {
        return ctx.tipo() != null ? resolvedorTipo.resolverTipo(ctx.tipo()) : Tipo.VOID; // 'void'
    }
}