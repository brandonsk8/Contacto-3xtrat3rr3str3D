package com.company.zetariano;

import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.GestorErrores;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import com.company.semantico.Visibilidad;

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

    // Registra clases, campos, constructores y metodos de un .z.
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
        SimboloClase clase = declarar(ctx);
        registrarMiembros(ctx, clase);
        return clase;
    }

    /**
     * Etapa 1: solo el NOMBRE de la clase y su superclase. Se separa de los
     * miembros para que los imports no dependan del orden: antes de leer los
     * campos/parametros de cualquier clase, todas las clases ya existen.
     */
    public SimboloClase declarar(ZetarianoParser.ProgramaContext ctx) {
        String nombre = ctx.IDENTIFICADOR(0).getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        SimboloClase clase = new SimboloClase(nombre, linea, columna);
        if (!global.registrarClase(clase)) {
            errores.reportar(linea, columna, "ya existe un tipo (estructura o clase) llamado '" + nombre + "'");
        }

        // 'extends' opcional: IDENTIFICADOR trae 1 elemento (clase) o 2 (clase + superclase).
        if (ctx.IDENTIFICADOR().size() > 1) {
            String nombreSuper = ctx.IDENTIFICADOR(1).getText();
            if (nombreSuper.equals(nombre)) {
                errores.reportar(linea, columna, "la clase '" + nombre + "' no puede heredar de si misma");
            } else {
                clase.setNombreSuperclase(nombreSuper);
            }
        }
        return clase;
    }

    /** Etapa 2: campos, constructores y metodos (la superclase debe estar ya con sus miembros para validar '@Override'). */
    public void registrarMiembros(ZetarianoParser.ProgramaContext ctx, SimboloClase clase) {
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        if (clase.getNombreSuperclase() != null && clase.getSuperclase(global) == null) {
            errores.reportar(linea, columna, "la superclase '" + clase.getNombreSuperclase() + "' de '" + clase.getNombre() + "' no esta definida (falta el 'import')");
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
        if (clase.getConstructores().isEmpty()) {
            // Igual que Java: una clase sin constructores tiene uno por defecto, vacio y publico.
            SimboloInvocable porDefecto = new SimboloInvocable(clase.getNombre(), CategoriaSimbolo.CONSTRUCTOR,
                    new ArrayList<>(), Tipo.VOID, linea, columna);
            porDefecto.setVisibilidad(Visibilidad.PUBLIC);
            clase.agregarConstructor(porDefecto);
        }
    }

    // Registra un campo con su visibilidad.
    private void registrarCampo(SimboloClase clase, ZetarianoParser.DeclaracionCampoContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        Tipo tipo = resolvedorTipo.resolverTipo(ctx.tipo());
        Simbolo campo = new Simbolo(nombre, tipo, CategoriaSimbolo.CAMPO, linea, columna);
        campo.setVisibilidad(resolverVisibilidad(ctx.modificadorVisibilidad()));
        if (!clase.agregarCampo(campo)) {
            errores.reportar(linea, columna, "el campo '" + nombre + "' ya existe en la clase '" + clase.getNombre() + "'");
        }
    }

    // Registra un constructor.
    private void registrarConstructor(SimboloClase clase, ZetarianoParser.ConstructorContext ctx) {
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        List<Simbolo> parametros = convertirParametros(ctx.listaParametros());

        SimboloInvocable constructor = new SimboloInvocable(clase.getNombre(), CategoriaSimbolo.CONSTRUCTOR, parametros, Tipo.VOID, linea, columna);
        constructor.setVisibilidad(resolverVisibilidad(ctx.modificadorVisibilidad()));
        if (!clase.agregarConstructor(constructor)) {
            errores.reportar(linea, columna, "ya existe un constructor de '" + clase.getNombre() + "' con esos mismos tipos de parametro");
        }
    }

    // Registra un metodo y valida @Override obligatorio.
    private void registrarMetodo(SimboloClase clase, ZetarianoParser.MetodoContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        List<Simbolo> parametros = convertirParametros(ctx.listaParametros());
        Tipo tipoRetorno = resolverTipoRetorno(ctx.tipoRetorno());

        SimboloInvocable metodo = new SimboloInvocable(nombre, CategoriaSimbolo.METODO, parametros, tipoRetorno, linea, columna);
        metodo.setVisibilidad(resolverVisibilidad(ctx.modificadorVisibilidad()));

        boolean marcadoOverride = false;
        if (ctx.anotacion() != null) {
            String nombreAnotacion = ctx.anotacion().IDENTIFICADOR().getText();
            if (!nombreAnotacion.equals("Override")) {
                errores.reportar(linea, columna, "unica anotacion reconocida: '@Override' (se escribio '@" + nombreAnotacion + "')");
            } else {
                marcadoOverride = true;
            }
        }
        metodo.setMarcadoOverride(marcadoOverride);

        if (!clase.agregarMetodo(metodo)) {
            errores.reportar(linea, columna, "ya existe un metodo '" + nombre + "' en '" + clase.getNombre() + "' con esos mismos tipos de parametro");
            return;
        }

        // '@Override' exige que exista un metodo heredado con la misma firma
        // (solo se valida si la superclase ya esta registrada: orden de imports).
        SimboloClase padre = clase.getSuperclase(global);
        if (padre != null) {
            List<Tipo> tipos = tiposDe(metodo);
            boolean heredaMetodoIgual = padre.buscarMetodosHeredados(nombre, global).stream()
                    .anyMatch(m -> m.mismaFirma(tipos));
            boolean sobreescribeHeredadoVisible = padre.buscarMetodosHeredados(nombre, global).stream()
                    .anyMatch(m -> m.mismaFirma(tipos) && m.getVisibilidad() != Visibilidad.PRIVATE);
            if (!marcadoOverride && sobreescribeHeredadoVisible) {
                errores.reportar(linea, columna, "'" + nombre + "' sobreescribe un metodo heredado de '" + padre.getNombre()
                        + "' y debe llevar la anotacion '@Override'");
            }
            if (marcadoOverride && !heredaMetodoIgual) {
                errores.reportar(linea, columna, "'" + nombre + "' tiene '@Override' pero '" + clase.getNombre()
                        + "' (o su superclase '" + padre.getNombre() + "') no tiene ningun metodo heredado con esa misma firma para sobreescribir");
            }
        } else if (marcadoOverride) {
            errores.reportar(linea, columna, "'" + nombre + "' tiene '@Override' pero '" + clase.getNombre() + "' no hereda de ninguna clase ('extends')");
        }
    }

    /** Sin modificador escrito = DEFAULT (se trata como PUBLIC, ver Visibilidad.java). */
    private Visibilidad resolverVisibilidad(ZetarianoParser.ModificadorVisibilidadContext ctx) {
        if (ctx == null) {
            return Visibilidad.DEFAULT;
        }
        String texto = ctx.getText();
        if (texto.equals("private")) return Visibilidad.PRIVATE;
        if (texto.equals("protected")) return Visibilidad.PROTECTED;
        return Visibilidad.PUBLIC;
    }

    // Lista de tipos de los parametros.
    private static List<Tipo> tiposDe(SimboloInvocable invocable) {
        List<Tipo> tipos = new ArrayList<>();
        for (Simbolo p : invocable.getParametros()) {
            tipos.add(p.getTipo());
        }
        return tipos;
    }

    // Convierte parametros a simbolos.
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

    // Tipo de retorno del metodo.
    private Tipo resolverTipoRetorno(ZetarianoParser.TipoRetornoContext ctx) {
        return ctx.tipo() != null ? resolvedorTipo.resolverTipo(ctx.tipo()) : Tipo.VOID; // 'void'
    }
}