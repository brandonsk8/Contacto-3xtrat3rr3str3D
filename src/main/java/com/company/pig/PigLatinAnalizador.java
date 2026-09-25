package com.company.pig;

import com.company.ir.TablaCuartetas;
import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.GestorErrores;
import com.company.semantico.Operando;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloEstructura;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolos;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import com.company.y.YVisitor;
import com.company.zetariano.ZetarianoAnalizador;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Pasada 2 de un programa Pig Latin: analiza el cuerpo del .pig, que es
 * el punto de entrada real del programa. Es un Visitor de ANTLR (mismo
 * caso que ZetarianoAnalizador: no se llama 'PigLatinVisitor' porque
 * ANTLR ya genera esa interfaz en este paquete).
 *
 * Un .pig no tiene funciones propias: es un solo cuerpo (VARIABILES> +
 * MAIOR>). Antes de analizarlo hay que resolver los 'import' - cada
 * archivo .y/.z importado necesita que se generen tambien las cuartetas
 * de su propio cuerpo, asi que analizarPrograma() corre YVisitor o
 * ZetarianoAnalizador sobre cada import antes de tocar el .pig.
 *
 * El 'acceso' de Pig Latin mezcla las dos reglas ya escritas: como Y?,
 * puede llegar a un campo de estructura o llamar una funcion global;
 * como Zetariano, puede llamar un metodo de una clase importada (sin
 * 'this' implicito, ya que Pig Latin no tiene metodos propios).
 */
public class PigLatinAnalizador extends PigLatinBaseVisitor<Operando> {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final TablaCuartetas cuartetas;

    // Resolucion de tipos y de 'acceso' delegada a sus propias clases;
    // esta se queda con el recorrido de declaraciones/sentencias/expresiones.
    private final ResolvedorTipoPigLatin resolvedorTipo;
    private final ResolvedorAccesoPigLatin resolvedorAcceso;

    private TablaSimbolos ambitoActual;

    // {etiquetaContinuar, etiquetaFin} del ciclo mas interno - mismo patron que YVisitor/ZetarianoAnalizador.
    private final Deque<String[]> pilaCiclos = new ArrayDeque<>();

    public PigLatinAnalizador(TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas) {
        this.global = global;
        this.errores = errores;
        this.cuartetas = cuartetas;
        this.resolvedorTipo = new ResolvedorTipoPigLatin(global, errores);
        this.resolvedorAcceso = new ResolvedorAccesoPigLatin(global, errores, cuartetas, this);
    }

    /** Punto de entrada. 'importados' trae el arbol ya parseado de cada 'import', listo para generarle sus cuartetas. */
    public void analizarPrograma(PigLatinParser.ProgramaContext ctx, List<ArchivoImportado> importados) {
        for (ArchivoImportado archivo : importados) {
            // Etiqueta cada error con su archivo de origen, si no un error de
            // Pila.z se veria como si viniera del propio main.pig.
            errores.setArchivoActual(archivo.getNombreArchivo());
            if (archivo.esZetariano()) {
                new ZetarianoAnalizador(global, errores, cuartetas).analizarPrograma(archivo.getArbolZetariano(), archivo.getClase());
            } else {
                new YVisitor(global, errores, cuartetas).analizarPrograma(archivo.getArbolY());
            }
        }
        errores.setArchivoActual(null);

        // Ambito "global" del propio .pig: VARIABILES> vive aqui, sin padre.
        ambitoActual = new TablaSimbolos(null);
        cuartetas.agregar("label", null, null, "main");
        if (ctx.seccionVariables() != null) {
            for (PigLatinParser.DeclaracionContext d : ctx.seccionVariables().declaracion()) {
                visit(d);
            }
        }

        for (PigLatinParser.SentenciaContext s : ctx.seccionPrincipal().sentencia()) {
            visit(s);
        }
    }

    // ---------------- declaraciones (VARIABILES> y forInit) ----------------

    @Override
    public Operando visitDeclaracionSimple(PigLatinParser.DeclaracionSimpleContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        Operando resultado = visit(ctx.inicializador());
        Tipo tipo = resultado.getTipo() != null ? resultado.getTipo() : Tipo.ERROR;

        Simbolo variable = new Simbolo(nombre, tipo, CategoriaSimbolo.VARIABLE, linea, columna);
        if (!ambitoActual.declarar(variable)) {
            reportarError(ctx, "ya existe una variable llamada '" + nombre + "' en este ambito");
            return null;
        }

        if (ctx.inicializador() instanceof PigLatinParser.InicializadorEstructuraContext && tipo.getCategoria() == Tipo.Categoria.ESTRUCTURA) {
            SimboloEstructura estructura = global.buscarEstructura(tipo.getNombreDefinido());
            PigLatinParser.InicializadorEstructuraContext initEstructura = (PigLatinParser.InicializadorEstructuraContext) ctx.inicializador();
            inicializarValoresEstructura(estructura, initEstructura.valoresEstructura(), nombre, ctx);
        } else if (resultado.getTexto() != null) {
            cuartetas.agregar("=", resultado.getTexto(), null, nombre);
        }
        return null;
    }

    @Override
    public Operando visitInicializadorPrimitivo(PigLatinParser.InicializadorPrimitivoContext ctx) {
        Tipo tipo = resolvedorTipo.mapearTipoPrimitivo(ctx.tipoPrimitivo());
        Operando valor = visit(ctx.expresion());
        if (!valor.esValor()) {
            return new Operando(tipo, null);
        }
        if (!tipo.esCompatibleCon(valor.getTipo())) {
            reportarError(ctx, "se esperaba un valor de tipo " + tipo + ", se recibio " + valor.getTipo());
            return new Operando(tipo, null);
        }
        return new Operando(tipo, valor.getTexto());
    }

    @Override
    public Operando visitInicializadorBooleano(PigLatinParser.InicializadorBooleanoContext ctx) {
        return new Operando(Tipo.BOOLEANO, ctx.getText());
    }

    @Override
    public Operando visitInicializadorEstructura(PigLatinParser.InicializadorEstructuraContext ctx) {
        String nombreTipo = ctx.IDENTIFICADOR().getText();
        SimboloEstructura estructura = global.buscarEstructura(nombreTipo);
        if (estructura == null) {
            reportarError(ctx, "'" + nombreTipo + "' no es una estructura conocida (revisa los 'import')");
            return Operando.error();
        }
        // Texto en null a proposito: emitir 'destino.campo = valor' necesita
        // el nombre de la variable destino, que se resuelve en visitDeclaracionSimple.
        return new Operando(Tipo.estructura(nombreTipo), null);
    }

    @Override
    public Operando visitInicializadorObjeto(PigLatinParser.InicializadorObjetoContext ctx) {
        String nombreClase = ctx.IDENTIFICADOR().getText();
        SimboloClase clase = global.buscarClase(nombreClase);
        if (clase == null) {
            reportarError(ctx, "'" + nombreClase + "' no es una clase conocida (revisa los 'import')");
            return Operando.error();
        }
        String temporal = resolverNuevoObjeto(clase, ctx.listaArgumentos(), ctx);
        return new Operando(Tipo.clase(nombreClase), temporal);
    }

    private void inicializarValoresEstructura(SimboloEstructura estructura, PigLatinParser.ValoresEstructuraContext ctx,
                                              String destinoBase, ParserRuleContext ctxError) {
        if (estructura == null) {
            return;
        }
        List<Simbolo> campos = new ArrayList<>(estructura.getCampos().values());
        List<PigLatinParser.ValorEstructuraContext> valores = ctx.listaValoresEstructura() != null
                ? ctx.listaValoresEstructura().valorEstructura()
                : Collections.<PigLatinParser.ValorEstructuraContext>emptyList();

        if (valores.size() != campos.size()) {
            reportarError(ctxError, "'" + estructura.getNombre() + "' tiene " + campos.size() + " campo(s), se dieron " + valores.size() + " valor(es)");
            return;
        }
        for (int i = 0; i < campos.size(); i++) {
            Simbolo campo = campos.get(i);
            PigLatinParser.ValorEstructuraContext valor = valores.get(i);
            String destinoCampo = destinoBase + "." + campo.getNombre();

            if (valor.valoresEstructura() != null) {
                if (campo.getTipo().getCategoria() != Tipo.Categoria.ESTRUCTURA) {
                    reportarError(valor, "el campo '" + campo.getNombre() + "' no es una estructura, no se le puede dar '{...}'");
                    continue;
                }
                inicializarValoresEstructura(global.buscarEstructura(campo.getTipo().getNombreDefinido()), valor.valoresEstructura(), destinoCampo, valor);
            } else {
                Operando v = visit(valor.expresion());
                if (v.esValor()) {
                    if (!campo.getTipo().esCompatibleCon(v.getTipo())) {
                        reportarError(valor, "el campo '" + campo.getNombre() + "' espera " + campo.getTipo() + ", se recibio " + v.getTipo());
                    } else {
                        cuartetas.agregar("=", v.getTexto(), null, destinoCampo);
                    }
                }
            }
        }
    }

    private String resolverNuevoObjeto(SimboloClase clase, PigLatinParser.ListaArgumentosContext ctxArgs, ParserRuleContext ctxError) {
        List<PigLatinParser.ExpresionContext> argsCtx = ctxArgs != null ? ctxArgs.expresion() : Collections.<PigLatinParser.ExpresionContext>emptyList();
        List<Operando> args = new ArrayList<>();
        for (PigLatinParser.ExpresionContext a : argsCtx) {
            args.add(visit(a));
        }
        if (contieneError(args)) {
            return null;
        }
        SimboloInvocable constructor = buscarPorFirma(clase.getConstructores(), tiposDe(args));
        if (constructor == null) {
            reportarError(ctxError, "no hay un constructor de '" + clase.getNombre() + "' que reciba esos " + args.size() + " argumento(s)");
            return null;
        }
        for (Operando a : args) {
            cuartetas.agregar("param", a.getTexto(), null, null);
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar("new", clase.getNombre(), String.valueOf(args.size()), temporal);
        return temporal;
    }

    @Override
    public Operando visitDeclaracionArreglo(PigLatinParser.DeclaracionArregloContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        Operando tamano = visit(ctx.expresion());
        if (tamano.esValor() && tamano.getTipo().getCategoria() != Tipo.Categoria.ENTERO) {
            reportarError(ctx, "el tamano del arreglo debe ser entero, no " + tamano.getTipo());
        }

        Tipo tipoElemento = resolvedorTipo.resolverTipoDato(ctx.tipoDato());
        Tipo tipoArreglo = Tipo.arreglo(tipoElemento, 1);

        Simbolo variable = new Simbolo(nombre, tipoArreglo, CategoriaSimbolo.VARIABLE, linea, columna);
        if (!ambitoActual.declarar(variable)) {
            reportarError(ctx, "ya existe una variable llamada '" + nombre + "' en este ambito");
            return null;
        }

        cuartetas.agregar("new_array", tipoElemento.toString(), tamano.esValor() ? tamano.getTexto() : "0", nombre);

        if (ctx.valoresArreglo() != null && ctx.valoresArreglo().listaExpresiones() != null) {
            List<PigLatinParser.ExpresionContext> valores = ctx.valoresArreglo().listaExpresiones().expresion();
            for (int i = 0; i < valores.size(); i++) {
                Operando v = visit(valores.get(i));
                if (v.esValor()) {
                    if (!tipoElemento.esCompatibleCon(v.getTipo())) {
                        reportarError(valores.get(i), "el elemento " + i + " de '" + nombre + "' deberia ser " + tipoElemento + ", se recibio " + v.getTipo());
                    } else {
                        cuartetas.agregar("=", v.getTexto(), null, nombre + "[" + i + "]");
                    }
                }
            }
        }
        return null;
    }

    // ---------------- sentencias ----------------

    @Override
    public Operando visitSentencia(PigLatinParser.SentenciaContext ctx) {
        if (ctx.declaracion() != null) return visit(ctx.declaracion());
        if (ctx.asignacion() != null) return visit(ctx.asignacion());
        if (ctx.condicional() != null) return visit(ctx.condicional());
        if (ctx.cicloMientras() != null) return visit(ctx.cicloMientras());
        if (ctx.cicloHacer() != null) return visit(ctx.cicloHacer());
        if (ctx.cicloPara() != null) return visit(ctx.cicloPara());
        if (ctx.INTERRUMPE() != null) return manejarInterrumpe(ctx);
        if (ctx.PERGE() != null) return manejarPerge(ctx);
        if (ctx.imprimir() != null) return visit(ctx.imprimir());
        if (ctx.leer() != null) return visit(ctx.leer());
        if (ctx.llamadaSentencia() != null) return visit(ctx.llamadaSentencia());
        return null;
    }

    @Override
    public Operando visitAsignacion(PigLatinParser.AsignacionContext ctx) {
        return manejarAsignacion(ctx, ctx.acceso(), ctx.expresion());
    }

    @Override
    public Operando visitAsignacionSinFin(PigLatinParser.AsignacionSinFinContext ctx) {
        return manejarAsignacion(ctx, ctx.acceso(), ctx.expresion());
    }

    private Operando manejarAsignacion(ParserRuleContext ctx, PigLatinParser.AccesoContext accesoCtx, PigLatinParser.ExpresionContext expCtx) {
        Operando destino = resolvedorAcceso.resolverAcceso(accesoCtx, ambitoActual);
        Operando valor = visit(expCtx);
        if (!destino.esValor() || !valor.esValor()) {
            return null;
        }
        if (!destino.getTipo().esCompatibleCon(valor.getTipo())) {
            reportarError(ctx, "no se puede asignar un valor de tipo " + valor.getTipo() + " a '" + destino.getTexto() + "' (" + destino.getTipo() + ")");
            return null;
        }
        cuartetas.agregar("=", valor.getTexto(), null, destino.getTexto());
        return null;
    }

    @Override
    public Operando visitIncrementoDecremento(PigLatinParser.IncrementoDecrementoContext ctx) {
        Operando destino = resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual);
        if (!destino.esValor()) {
            return null;
        }
        String operador = ctx.getChild(1).getText();
        if (!destino.getTipo().esNumerico()) {
            reportarError(ctx, "'" + operador + "' solo aplica a valores numericos, no a " + destino.getTipo());
            return null;
        }
        cuartetas.agregar(operador, destino.getTexto(), null, destino.getTexto());
        return null;
    }

    private Operando manejarInterrumpe(PigLatinParser.SentenciaContext ctx) {
        if (pilaCiclos.isEmpty()) {
            reportarError(ctx, "'interrumpe' fuera de un ciclo");
        } else {
            cuartetas.agregar("goto", null, null, pilaCiclos.peek()[1]);
        }
        return null;
    }

    private Operando manejarPerge(PigLatinParser.SentenciaContext ctx) {
        if (pilaCiclos.isEmpty()) {
            reportarError(ctx, "'perge' fuera de un ciclo");
        } else {
            cuartetas.agregar("goto", null, null, pilaCiclos.peek()[0]);
        }
        return null;
    }

    @Override
    public Operando visitLlamadaSentencia(PigLatinParser.LlamadaSentenciaContext ctx) {
        resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual);
        return null;
    }

    /** 'imprimir' de Pig Latin es una sentencia propia del lenguaje ('>>'), no una funcion como en Y?. */
    @Override
    public Operando visitImprimir(PigLatinParser.ImprimirContext ctx) {
        for (PigLatinParser.ExpresionContext e : ctx.expresion()) {
            Operando valor = visit(e);
            if (valor.esValor()) {
                cuartetas.agregar("print", valor.getTexto(), null, null);
            }
        }
        return null;
    }

    /** 'leer' ('<<') no valida el tipo del destino: es una lectura cruda, la conversion real la hace el backend C. */
    @Override
    public Operando visitLeer(PigLatinParser.LeerContext ctx) {
        if (ctx.acceso() != null) {
            Operando destino = resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual);
            if (destino.esValor()) {
                cuartetas.agregar("read", null, null, destino.getTexto());
            }
        } else {
            cuartetas.agregar("read", null, null, cuartetas.nuevoTemporal()); // leer y descartar
        }
        return null;
    }

    // ---------------- condicional / ciclos ----------------

    @Override
    public Operando visitCondicional(PigLatinParser.CondicionalContext ctx) {
        List<PigLatinParser.ExpresionContext> condiciones = ctx.expresion();
        List<PigLatinParser.CuerpoContext> cuerpos = ctx.cuerpo();
        boolean hayContrario = cuerpos.size() > condiciones.size();

        String etiquetaFin = cuartetas.nuevaEtiqueta();
        for (int i = 0; i < condiciones.size(); i++) {
            Operando cond = visit(condiciones.get(i));
            exigirBooleano(condiciones.get(i), cond);

            String etiquetaSiguiente = cuartetas.nuevaEtiqueta();
            if (cond.esValor()) {
                cuartetas.agregar("if_false", cond.getTexto(), null, etiquetaSiguiente);
            }
            visitConNuevoAmbito(cuerpos.get(i));
            cuartetas.agregar("goto", null, null, etiquetaFin);
            cuartetas.agregar("label", null, null, etiquetaSiguiente);
        }
        if (hayContrario) {
            visitConNuevoAmbito(cuerpos.get(cuerpos.size() - 1));
        }
        cuartetas.agregar("label", null, null, etiquetaFin);
        return null;
    }

    @Override
    public Operando visitCicloMientras(PigLatinParser.CicloMientrasContext ctx) {
        String etiquetaInicio = cuartetas.nuevaEtiqueta();
        String etiquetaFin = cuartetas.nuevaEtiqueta();

        cuartetas.agregar("label", null, null, etiquetaInicio);
        Operando cond = visit(ctx.expresion());
        exigirBooleano(ctx.expresion(), cond);
        if (cond.esValor()) {
            cuartetas.agregar("if_false", cond.getTexto(), null, etiquetaFin);
        }

        pilaCiclos.push(new String[]{etiquetaInicio, etiquetaFin});
        visitConNuevoAmbito(ctx.cuerpo());
        pilaCiclos.pop();

        cuartetas.agregar("goto", null, null, etiquetaInicio);
        cuartetas.agregar("label", null, null, etiquetaFin);
        return null;
    }

    @Override
    public Operando visitCicloHacer(PigLatinParser.CicloHacerContext ctx) {
        String etiquetaInicio = cuartetas.nuevaEtiqueta();
        String etiquetaContinuar = cuartetas.nuevaEtiqueta();
        String etiquetaFin = cuartetas.nuevaEtiqueta();

        cuartetas.agregar("label", null, null, etiquetaInicio);
        pilaCiclos.push(new String[]{etiquetaContinuar, etiquetaFin});
        visitConNuevoAmbito(ctx.cuerpo());
        pilaCiclos.pop();

        cuartetas.agregar("label", null, null, etiquetaContinuar);
        Operando cond = visit(ctx.expresion());
        exigirBooleano(ctx.expresion(), cond);
        if (cond.esValor()) {
            cuartetas.agregar("if_true", cond.getTexto(), null, etiquetaInicio);
        }
        cuartetas.agregar("label", null, null, etiquetaFin);
        return null;
    }

    @Override
    public Operando visitCicloPara(PigLatinParser.CicloParaContext ctx) {
        TablaSimbolos ambitoAnterior = ambitoActual;
        ambitoActual = new TablaSimbolos(ambitoAnterior);

        if (ctx.forInit() != null) {
            PigLatinParser.ForInitContext init = ctx.forInit();
            if (init.declaracion() != null) visit(init.declaracion());
            else visit(init.asignacionSinFin());
        }

        String etiquetaInicio = cuartetas.nuevaEtiqueta();
        String etiquetaActualizar = cuartetas.nuevaEtiqueta();
        String etiquetaFin = cuartetas.nuevaEtiqueta();

        cuartetas.agregar("label", null, null, etiquetaInicio);
        if (ctx.expresion() != null) {
            Operando cond = visit(ctx.expresion());
            exigirBooleano(ctx.expresion(), cond);
            if (cond.esValor()) {
                cuartetas.agregar("if_false", cond.getTexto(), null, etiquetaFin);
            }
        }

        pilaCiclos.push(new String[]{etiquetaActualizar, etiquetaFin});
        visitConNuevoAmbito(ctx.cuerpo());
        pilaCiclos.pop();

        cuartetas.agregar("label", null, null, etiquetaActualizar);
        if (ctx.forActualizacion() != null) {
            PigLatinParser.ForActualizacionContext act = ctx.forActualizacion();
            if (act.asignacionSinFin() != null) visit(act.asignacionSinFin());
            else visit(act.incrementoDecremento());
        }
        cuartetas.agregar("goto", null, null, etiquetaInicio);
        cuartetas.agregar("label", null, null, etiquetaFin);

        ambitoActual = ambitoAnterior;
        return null;
    }

    private void exigirBooleano(ParserRuleContext ctx, Operando cond) {
        if (cond.esValor() && cond.getTipo().getCategoria() != Tipo.Categoria.BOOLEANO) {
            reportarError(ctx, "la condicion debe ser booleana, no " + cond.getTipo());
        }
    }

    private void visitConNuevoAmbito(PigLatinParser.CuerpoContext cuerpo) {
        TablaSimbolos anterior = ambitoActual;
        ambitoActual = new TablaSimbolos(anterior);
        for (PigLatinParser.SentenciaContext s : cuerpo.sentencia()) {
            visit(s);
        }
        ambitoActual = anterior;
    }

    // ---------------- expresiones ----------------

    @Override
    public Operando visitExpUnaria(PigLatinParser.ExpUnariaContext ctx) {
        Operando operando = visit(ctx.expresion());
        if (!operando.esValor()) {
            return Operando.error();
        }
        String op = ctx.getChild(0).getText();
        if (op.equals("!")) {
            if (operando.getTipo().getCategoria() != Tipo.Categoria.BOOLEANO) {
                reportarError(ctx, "'!' solo aplica a valores booleanos, no a " + operando.getTipo());
                return Operando.error();
            }
            return emitirUnaria("!", operando, Tipo.BOOLEANO);
        }
        if (!operando.getTipo().esNumerico()) {
            reportarError(ctx, "'-' unario solo aplica a valores numericos, no a " + operando.getTipo());
            return Operando.error();
        }
        return emitirUnaria("-u", operando, operando.getTipo());
    }

    private Operando emitirUnaria(String operador, Operando operando, Tipo tipoResultado) {
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar(operador, operando.getTexto(), null, temporal);
        return new Operando(tipoResultado, temporal);
    }

    @Override
    public Operando visitExpMultiplicativa(PigLatinParser.ExpMultiplicativaContext ctx) {
        return operacionAritmetica(ctx, ctx.expresion(0), ctx.expresion(1), ctx.op.getText());
    }

    @Override
    public Operando visitExpAditiva(PigLatinParser.ExpAditivaContext ctx) {
        return operacionAritmetica(ctx, ctx.expresion(0), ctx.expresion(1), ctx.op.getText());
    }

    private Operando operacionAritmetica(ParserRuleContext ctx, PigLatinParser.ExpresionContext ei, PigLatinParser.ExpresionContext ed, String op) {
        Operando izq = visit(ei);
        Operando der = visit(ed);
        if (!izq.esValor() || !der.esValor()) {
            return Operando.error();
        }
        if (!izq.getTipo().esNumerico() || !der.getTipo().esNumerico()) {
            reportarError(ctx, "'" + op + "' solo aplica entre valores numericos, no " + izq.getTipo() + " y " + der.getTipo());
            return Operando.error();
        }
        Tipo resultado = (izq.getTipo().getCategoria() == Tipo.Categoria.DECIMAL || der.getTipo().getCategoria() == Tipo.Categoria.DECIMAL)
                ? Tipo.DECIMAL : Tipo.ENTERO;
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar(op, izq.getTexto(), der.getTexto(), temporal);
        return new Operando(resultado, temporal);
    }

    @Override
    public Operando visitExpRelacional(PigLatinParser.ExpRelacionalContext ctx) {
        Operando izq = visit(ctx.expresion(0));
        Operando der = visit(ctx.expresion(1));
        if (!izq.esValor() || !der.esValor()) {
            return Operando.error();
        }
        if (!izq.getTipo().esNumerico() || !der.getTipo().esNumerico()) {
            reportarError(ctx, "'" + ctx.op.getText() + "' solo aplica entre valores numericos, no " + izq.getTipo() + " y " + der.getTipo());
            return Operando.error();
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar(ctx.op.getText(), izq.getTexto(), der.getTexto(), temporal);
        return new Operando(Tipo.BOOLEANO, temporal);
    }

    @Override
    public Operando visitExpIgualdad(PigLatinParser.ExpIgualdadContext ctx) {
        Operando izq = visit(ctx.expresion(0));
        Operando der = visit(ctx.expresion(1));
        if (!izq.esValor() || !der.esValor()) {
            return Operando.error();
        }
        if (!izq.getTipo().esCompatibleCon(der.getTipo()) && !der.getTipo().esCompatibleCon(izq.getTipo())) {
            reportarError(ctx, "no se puede comparar " + izq.getTipo() + " con " + der.getTipo());
            return Operando.error();
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar(ctx.op.getText(), izq.getTexto(), der.getTexto(), temporal);
        return new Operando(Tipo.BOOLEANO, temporal);
    }

    @Override
    public Operando visitExpAnd(PigLatinParser.ExpAndContext ctx) {
        return operacionLogica(ctx, ctx.expresion(0), ctx.expresion(1), "&&");
    }

    @Override
    public Operando visitExpOr(PigLatinParser.ExpOrContext ctx) {
        return operacionLogica(ctx, ctx.expresion(0), ctx.expresion(1), "||");
    }

    private Operando operacionLogica(ParserRuleContext ctx, PigLatinParser.ExpresionContext ei, PigLatinParser.ExpresionContext ed, String op) {
        Operando izq = visit(ei);
        Operando der = visit(ed);
        if (!izq.esValor() || !der.esValor()) {
            return Operando.error();
        }
        if (izq.getTipo().getCategoria() != Tipo.Categoria.BOOLEANO || der.getTipo().getCategoria() != Tipo.Categoria.BOOLEANO) {
            reportarError(ctx, "'" + op + "' solo aplica entre valores booleanos, no " + izq.getTipo() + " y " + der.getTipo());
            return Operando.error();
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar(op, izq.getTexto(), der.getTexto(), temporal);
        return new Operando(Tipo.BOOLEANO, temporal);
    }

    @Override
    public Operando visitExpParentesis(PigLatinParser.ExpParentesisContext ctx) {
        return visit(ctx.expresion());
    }

    @Override
    public Operando visitExpAcceso(PigLatinParser.ExpAccesoContext ctx) {
        return resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual);
    }

    @Override
    public Operando visitExpLiteral(PigLatinParser.ExpLiteralContext ctx) {
        return literalAOperando(ctx.literal());
    }

    private Operando literalAOperando(PigLatinParser.LiteralContext ctx) {
        if (ctx.ENTERO() != null) return new Operando(Tipo.ENTERO, ctx.ENTERO().getText());
        if (ctx.DECIMAL() != null) return new Operando(Tipo.DECIMAL, ctx.DECIMAL().getText());
        if (ctx.CADENA() != null) return new Operando(Tipo.CADENA, ctx.CADENA().getText());
        if (ctx.CARACTER() != null) return new Operando(Tipo.CARACTER, ctx.CARACTER().getText());
        return new Operando(Tipo.BOOLEANO, ctx.getText()); // 'verum' / 'falsus'
    }

    // ---------------- utilidades ----------------

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