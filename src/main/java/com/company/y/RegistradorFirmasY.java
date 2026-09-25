package com.company.y;

import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.GestorErrores;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloEstructura;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;

import java.util.ArrayList;
import java.util.List;

/**
 * Pasada 1 sobre un archivo Y?: registra en TablaSimbolosGlobal las
 * FIRMAS de sus estructuras (nombre + campos) y funciones (nombre +
 * parametros + tipo de retorno), sin mirar el CUERPO de ninguna funcion
 * todavia. Eso es lo que permite que una estructura se auto-referencie
 * (Nodo con un campo 'siguiente' de tipo Nodo) y que otro archivo (una
 * clase Zetariano, o el propio main.pig) use estos tipos sin importar en
 * que orden se procesen los imports.
 *
 * No extiende YParserBaseVisitor a proposito: solo toca los 2 lugares
 * fijos de nivel superior del programa (seccionEstructuras y
 * seccionFunciones), no hace falta el patron Visitor completo para eso.
 */
public class RegistradorFirmasY {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;

    public RegistradorFirmasY(TablaSimbolosGlobal global, GestorErrores errores) {
        this.global = global;
        this.errores = errores;
    }

    public void registrar(YParser.ProgramaContext ctx) {
        if (ctx.seccionEstructuras() != null) {
            List<YParser.DefinicionEstructuraContext> definiciones = ctx.seccionEstructuras().definicionEstructura();

            // Paso A: crear las estructuras VACIAS primero (solo el nombre),
            // para que cualquier campo de cualquier estructura pueda
            // referenciar a cualquier otra (incluida a si misma) sin
            // importar el orden en que aparecen en el archivo.
            for (YParser.DefinicionEstructuraContext def : definiciones) {
                String nombre = def.IDENTIFICADOR().getText();
                int linea = def.getStart().getLine();
                int columna = def.getStart().getCharPositionInLine();
                SimboloEstructura estructura = new SimboloEstructura(nombre, linea, columna);
                if (!global.registrarEstructura(estructura)) {
                    errores.reportar(linea, columna, "ya existe un tipo (estructura o clase) llamado '" + nombre + "'");
                }
            }

            // Paso B: ahora si, llenar los campos de cada estructura -
            // para esto ya necesitamos que TODAS existan (paso A).
            for (YParser.DefinicionEstructuraContext def : definiciones) {
                String nombre = def.IDENTIFICADOR().getText();
                SimboloEstructura estructura = global.buscarEstructura(nombre);
                if (estructura == null) {
                    continue; // no se pudo registrar en el paso A (nombre duplicado), ya se reporto el error
                }
                for (YParser.CampoEstructuraContext campoCtx : def.cuerpoCampos().campoEstructura()) {
                    registrarCampo(estructura, campoCtx);
                }
            }
        }

        for (YParser.DefinicionFuncionContext def : ctx.seccionFunciones().definicionFuncion()) {
            registrarFuncion(def);
        }
    }

    private void registrarCampo(SimboloEstructura estructura, YParser.CampoEstructuraContext campoCtx) {
        String nombreCampo = campoCtx.IDENTIFICADOR().getText();
        int linea = campoCtx.getStart().getLine();
        int columna = campoCtx.getStart().getCharPositionInLine();

        Tipo tipoBase = resolverTipo(campoCtx.tipo());
        Tipo tipoFinal = campoCtx.expresion() != null ? Tipo.arreglo(tipoBase, 1) : tipoBase;

        Simbolo campo = new Simbolo(nombreCampo, tipoFinal, CategoriaSimbolo.CAMPO, linea, columna);
        if (campoCtx.expresion() != null) {
            // El backend (GeneradorC) solo puede traducir este arreglo a un
            // 'tipo nombre[N];' real de C si N es un entero literal (no
            // constantes ni otra variable) - se guarda tal cual el texto
            // para que GeneradorC valide el formato.
            campo.setTamanioArreglo(campoCtx.expresion().getText());
        }
        if (!estructura.agregarCampo(campo)) {
            errores.reportar(linea, columna, "el campo '" + nombreCampo + "' ya existe en la estructura '" + estructura.getNombre() + "'");
        }
    }

    private void registrarFuncion(YParser.DefinicionFuncionContext def) {
        String nombre = def.IDENTIFICADOR().getText();
        int linea = def.getStart().getLine();
        int columna = def.getStart().getCharPositionInLine();

        List<Simbolo> parametros = new ArrayList<>();
        if (def.listaParametros() != null) {
            for (YParser.ParametroContext p : def.listaParametros().parametro()) {
                parametros.add(convertirParametro(p));
            }
        }

        Tipo tipoRetorno = def.tipo() != null ? resolverTipo(def.tipo()) : Tipo.VOID;

        SimboloInvocable funcion = new SimboloInvocable(nombre, CategoriaSimbolo.FUNCION, parametros, tipoRetorno, linea, columna);
        if (!global.registrarFuncion(funcion)) {
            // Y? no permite sobrecarga: las funciones son globales por nombre (a diferencia de los metodos de Zetariano).
            errores.reportar(linea, columna, "ya existe una funcion llamada '" + nombre + "' (Y? no permite sobrecarga)");
        }
    }

    private Simbolo convertirParametro(YParser.ParametroContext ctx) {
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        if (ctx instanceof YParser.ParametroArregloContext) {
            YParser.ParametroArregloContext c = (YParser.ParametroArregloContext) ctx;
            Tipo tipo = Tipo.arreglo(resolverTipo(c.tipo()), 1);
            return new Simbolo(c.IDENTIFICADOR().getText(), tipo, CategoriaSimbolo.PARAMETRO, linea, columna);
        }
        if (ctx instanceof YParser.ParametroEstructuraContext) {
            YParser.ParametroEstructuraContext c = (YParser.ParametroEstructuraContext) ctx;
            Tipo tipo = resolverTipo(c.tipo());
            return new Simbolo(c.IDENTIFICADOR().getText(), tipo, CategoriaSimbolo.PARAMETRO, linea, columna);
        }
        YParser.ParametroSimpleContext c = (YParser.ParametroSimpleContext) ctx;
        Tipo tipo = resolverTipo(c.tipo());
        return new Simbolo(c.IDENTIFICADOR().getText(), tipo, CategoriaSimbolo.PARAMETRO, linea, columna);
    }

    /**
     * 'tipo' es 'tipoPrimitivo' o el IDENTIFICADOR de una estructura ya
     * conocida en TablaSimbolosGlobal (por eso las estructuras se
     * registran vacias ANTES de resolver ningun tipo, ver el Paso A/B de
     * registrar()). Si el nombre no corresponde a ningun tipo conocido,
     * se reporta el error y se devuelve Tipo.ERROR para no propagar el
     * problema en cascada.
     */
    private Tipo resolverTipo(YParser.TipoContext ctx) {
        if (ctx.tipoPrimitivo() != null) {
            switch (ctx.tipoPrimitivo().getText()) {
                case "entero": return Tipo.ENTERO;
                case "cadena": return Tipo.CADENA;
                case "flotante": return Tipo.DECIMAL;
                case "caracter": return Tipo.CARACTER;
                case "bool": return Tipo.BOOLEANO;
                default: throw new IllegalStateException("tipoPrimitivo no reconocido: " + ctx.tipoPrimitivo().getText());
            }
        }
        String nombre = ctx.IDENTIFICADOR().getText();
        Tipo tipo = global.resolverTipo(nombre);
        if (tipo == null) {
            errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(),
                    "tipo desconocido: '" + nombre + "'");
            return Tipo.ERROR;
        }
        return tipo;
    }
}