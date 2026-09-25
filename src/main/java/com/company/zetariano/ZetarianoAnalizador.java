package com.company.zetariano;

import com.company.ir.TablaCuartetas;
import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.GestorErrores;
import com.company.semantico.Operando;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.TablaSimbolos;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Pasada 2 sobre un archivo Zetariano: recorre cada constructor y metodo
 * ya registrado por RegistradorFirmasZetariano, valida tipos, llena la
 * TablaSimbolos de cada ambito y emite las cuartetas correspondientes.
 * Es un Visitor de ANTLR (extends ZetarianoBaseVisitor) - se llama
 * "Analizador" y no "Visitor" porque ANTLR ya genera una interfaz
 * ZetarianoVisitor en este mismo paquete y el nombre chocaria.
 * tambien esta clase depende del parser generado por antlr
 *
 * A diferencia de Y?, aqui hay 'this' y campos de instancia, los
 * metodos se pueden sobrecargar, las llamadas se pueden encadenar
 * (obj.metodo().otro()) y los campos pueden tener inicializador (corre
 * antes del cuerpo del constructor, como en Java).
 */
public class ZetarianoAnalizador extends ZetarianoBaseVisitor<Operando> {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final TablaCuartetas cuartetas;

    // Resolucion de tipos y de 'acceso' delegada a sus propias clases;
    // esta se queda con el recorrido de sentencias/expresiones.
    private final ResolvedorTipoZetariano resolvedorTipo;
    private final ResolvedorAccesoZetariano resolvedorAcceso;

    private TablaSimbolos ambitoActual;
    private SimboloClase claseActual;
    private SimboloInvocable invocableActual;
    private List<ZetarianoParser.DeclaracionCampoContext> camposConInicializador = Collections.emptyList();

    // {etiquetaContinuar, etiquetaFin} del ciclo/switch mas interno;
    // etiquetaContinuar es null para un 'switch' ('continue' ahi es invalido).
    private final Deque<String[]> pilaCiclos = new ArrayDeque<>();

    public ZetarianoAnalizador(TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas) {
        this.global = global;
        this.errores = errores;
        this.cuartetas = cuartetas;
        this.resolvedorTipo = new ResolvedorTipoZetariano(global, errores);
        this.resolvedorAcceso = new ResolvedorAccesoZetariano(global, errores, cuartetas, this);
    }

    /** Punto de entrada: recorre todos los constructores y metodos de la clase. */
    public void analizarPrograma(ZetarianoParser.ProgramaContext ctx, SimboloClase clase) {
        this.claseActual = clase;

        List<ZetarianoParser.ConstructorContext> constructores = new ArrayList<>();
        List<ZetarianoParser.MetodoContext> metodos = new ArrayList<>();
        List<ZetarianoParser.DeclaracionCampoContext> camposConInit = new ArrayList<>();

        for (ZetarianoParser.MiembroContext miembro : ctx.miembro()) {
            if (miembro.declaracionCampo() != null) {
                if (miembro.declaracionCampo().inicializador() != null) {
                    camposConInit.add(miembro.declaracionCampo());
                }
            } else if (miembro.constructor() != null) {
                constructores.add(miembro.constructor());
            } else if (miembro.metodo() != null) {
                metodos.add(miembro.metodo());
            }
        }
        this.camposConInicializador = camposConInit;

        for (ZetarianoParser.ConstructorContext c : constructores) {
            analizarConstructor(c);
        }
        for (ZetarianoParser.MetodoContext m : metodos) {
            analizarMetodo(m);
        }
    }

    // ---------------- constructores / metodos ----------------

    private void analizarConstructor(ZetarianoParser.ConstructorContext ctx) {
        List<Tipo> tipos = tiposDeParametros(ctx.listaParametros());
        SimboloInvocable constructor = buscarPorFirma(claseActual.getConstructores(), tipos);
        if (constructor == null) {
            return; // no deberia pasar: RegistradorFirmasZetariano ya lo registro
        }

        SimboloInvocable invocableAnterior = invocableActual;
        TablaSimbolos ambitoAnterior = ambitoActual;

        invocableActual = constructor;
        ambitoActual = new TablaSimbolos(null);
        for (Simbolo p : constructor.getParametros()) {
            if (!ambitoActual.declarar(p)) {
                reportarError(ctx, "el parametro '" + p.getNombre() + "' esta repetido en el constructor de '" + claseActual.getNombre() + "'");
            }
        }
        constructor.setAmbitoLocal(ambitoActual);

        cuartetas.agregar("label", null, null, "ctor_" + claseActual.getNombre() + "_" + tipos.size());
        emitirInicializacionesDeCampos();
        visit(ctx.bloque());

        invocableActual = invocableAnterior;
        ambitoActual = ambitoAnterior;
    }

    private void analizarMetodo(ZetarianoParser.MetodoContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        List<Tipo> tipos = tiposDeParametros(ctx.listaParametros());
        SimboloInvocable metodo = buscarPorFirma(claseActual.buscarMetodos(nombre), tipos);
        if (metodo == null) {
            return;
        }

        SimboloInvocable invocableAnterior = invocableActual;
        TablaSimbolos ambitoAnterior = ambitoActual;

        invocableActual = metodo;
        ambitoActual = new TablaSimbolos(null);
        for (Simbolo p : metodo.getParametros()) {
            if (!ambitoActual.declarar(p)) {
                reportarError(ctx, "el parametro '" + p.getNombre() + "' esta repetido en '" + nombre + "'");
            }
        }
        metodo.setAmbitoLocal(ambitoActual);

        cuartetas.agregar("label", null, null, "metodo_" + claseActual.getNombre() + "_" + nombre + "_" + tipos.size());
        visit(ctx.bloque());

        invocableActual = invocableAnterior;
        ambitoActual = ambitoAnterior;
    }

    private void emitirInicializacionesDeCampos() {
        for (ZetarianoParser.DeclaracionCampoContext campoCtx : camposConInicializador) {
            String nombreCampo = campoCtx.IDENTIFICADOR().getText();
            Simbolo campo = claseActual.buscarCampo(nombreCampo);
            if (campo == null) {
                continue; // no deberia pasar
            }

            ZetarianoParser.InicializadorContext init = campoCtx.inicializador();
            if (init.expresion() != null) {
                Operando valor = visit(init.expresion());
                if (valor.esValor() && !campo.getTipo().esCompatibleCon(valor.getTipo())) {
                    reportarError(campoCtx, "no se puede inicializar el campo '" + nombreCampo + "' (" + campo.getTipo() + ") con un valor de tipo " + valor.getTipo());
                } else if (valor.esValor()) {
                    cuartetas.agregar("=", valor.getTexto(), null, "this." + nombreCampo);
                }
            } else if (init.listaExpresiones() != null) {
                // Se validan los elementos por separado, no la lista completa.
                for (ZetarianoParser.ExpresionContext e : init.listaExpresiones().expresion()) {
                    visit(e);
                }
            }
        }
    }

    private List<Tipo> tiposDeParametros(ZetarianoParser.ListaParametrosContext ctx) {
        List<Tipo> tipos = new ArrayList<>();
        if (ctx != null) {
            for (ZetarianoParser.ParametroContext p : ctx.parametro()) {
                tipos.add(resolvedorTipo.resolverTipo(p.tipo()));
            }
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

    // ---------------- bloques / sentencias ----------------

    @Override
    public Operando visitBloque(ZetarianoParser.BloqueContext ctx) {
        // Cada '{' ... '}' es su propio ambito, en cualquier lugar donde
        // aparezca (cuerpo de metodo, de un 'if', un bloque suelto, etc.)
        TablaSimbolos anterior = ambitoActual;
        ambitoActual = new TablaSimbolos(anterior);
        for (ZetarianoParser.SentenciaContext s : ctx.sentencia()) {
            visit(s);
        }
        ambitoActual = anterior;
        return null;
    }

    @Override
    public Operando visitSentencia(ZetarianoParser.SentenciaContext ctx) {
        if (ctx.declaracionVariable() != null) return visit(ctx.declaracionVariable());
        if (ctx.asignacion() != null) return visit(ctx.asignacion());
        if (ctx.incrementoDecremento() != null) return visit(ctx.incrementoDecremento());
        if (ctx.condicional() != null) return visit(ctx.condicional());
        if (ctx.seleccion() != null) return visit(ctx.seleccion());
        if (ctx.cicloPara() != null) return visit(ctx.cicloPara());
        if (ctx.cicloMientras() != null) return visit(ctx.cicloMientras());
        if (ctx.cicloHacer() != null) return visit(ctx.cicloHacer());
        if (ctx.BREAK() != null) return manejarBreak(ctx);
        if (ctx.CONTINUE() != null) return manejarContinue(ctx);
        if (ctx.RETURN() != null) return manejarReturn(ctx);
        if (ctx.llamada() != null) {
            visit(ctx.llamada());
            return null;
        }
        if (ctx.bloque() != null) {
            visit(ctx.bloque());
            return null;
        }
        return null;
    }

    @Override
    public Operando visitDeclaracionVariable(ZetarianoParser.DeclaracionVariableContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        Tipo tipo = resolvedorTipo.resolverTipo(ctx.tipo());

        Simbolo variable = new Simbolo(nombre, tipo, CategoriaSimbolo.VARIABLE, ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine());
        if (!ambitoActual.declarar(variable)) {
            reportarError(ctx, "ya existe una variable llamada '" + nombre + "' en este ambito");
            return null;
        }

        if (ctx.inicializador() != null) {
            ZetarianoParser.InicializadorContext init = ctx.inicializador();
            if (init.expresion() != null) {
                Operando valor = visit(init.expresion());
                if (valor.esValor() && !tipo.esCompatibleCon(valor.getTipo())) {
                    reportarError(ctx, "no se puede inicializar '" + nombre + "' (" + tipo + ") con un valor de tipo " + valor.getTipo());
                } else if (valor.esValor()) {
                    cuartetas.agregar("=", valor.getTexto(), null, nombre);
                }
            } else if (init.listaExpresiones() != null) {
                for (ZetarianoParser.ExpresionContext e : init.listaExpresiones().expresion()) {
                    visit(e);
                }
            }
        }
        return null;
    }

    @Override
    public Operando visitAsignacion(ZetarianoParser.AsignacionContext ctx) {
        Operando destino = resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual, claseActual);
        Operando valor = visit(ctx.expresion());
        if (!destino.esValor() || !valor.esValor()) {
            return null;
        }

        String op = ctx.opAsignacion().getText();
        // '+=' sobre una cadena es concatenacion, el lado derecho puede ser de cualquier tipo.
        boolean esConcatenacion = op.equals("+=") && destino.getTipo().getCategoria() == Tipo.Categoria.CADENA;

        if (!esConcatenacion && !destino.getTipo().esCompatibleCon(valor.getTipo())) {
            reportarError(ctx, "no se puede asignar un valor de tipo " + valor.getTipo() + " a '" + destino.getTexto() + "' (" + destino.getTipo() + ")");
            return null;
        }
        if (!esConcatenacion && !op.equals("=") && !destino.getTipo().esNumerico()) {
            reportarError(ctx, "'" + op + "' solo aplica a valores numericos (o a una CADENA con '+=', para concatenar)");
            return null;
        }

        if (op.equals("=")) {
            cuartetas.agregar("=", valor.getTexto(), null, destino.getTexto());
        } else {
            String temporal = cuartetas.nuevoTemporal();
            if (esConcatenacion) {
                cuartetas.agregar("concat", destino.getTexto(), valor.getTexto(), temporal);
            } else {
                String operadorBase = op.substring(0, 1); // '+=' -> '+', '-=' -> '-', '*=' -> '*'
                cuartetas.agregar(operadorBase, destino.getTexto(), valor.getTexto(), temporal);
            }
            cuartetas.agregar("=", temporal, null, destino.getTexto());
        }
        return null;
    }

    @Override
    public Operando visitIncrementoDecremento(ZetarianoParser.IncrementoDecrementoContext ctx) {
        Operando destino = resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual, claseActual);
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

    private Operando manejarBreak(ZetarianoParser.SentenciaContext ctx) {
        if (pilaCiclos.isEmpty()) {
            reportarError(ctx, "'break' fuera de un ciclo o de un 'switch'");
        } else {
            cuartetas.agregar("goto", null, null, pilaCiclos.peek()[1]);
        }
        return null;
    }

    private Operando manejarContinue(ZetarianoParser.SentenciaContext ctx) {
        if (pilaCiclos.isEmpty()) {
            reportarError(ctx, "'continue' fuera de un ciclo");
        } else if (pilaCiclos.peek()[0] == null) {
            reportarError(ctx, "'continue' no es valido dentro de un 'switch'");
        } else {
            cuartetas.agregar("goto", null, null, pilaCiclos.peek()[0]);
        }
        return null;
    }

    private Operando manejarReturn(ZetarianoParser.SentenciaContext ctx) {
        Tipo esperado = invocableActual.getTipoRetorno();
        if (ctx.expresion() != null) {
            Operando valor = visit(ctx.expresion());
            if (esperado.getCategoria() == Tipo.Categoria.VOID) {
                reportarError(ctx, "esto no retorna nada, no puede llevar 'return <valor>'");
            } else if (valor.esValor() && !esperado.esCompatibleCon(valor.getTipo())) {
                reportarError(ctx, "'return' espera " + esperado + ", se recibio " + valor.getTipo());
            }
            if (valor.esValor()) {
                cuartetas.agregar("return", valor.getTexto(), null, null);
            }
        } else {
            if (esperado.getCategoria() != Tipo.Categoria.VOID) {
                reportarError(ctx, "debe retornar un valor de tipo " + esperado);
            }
            cuartetas.agregar("return", null, null, null);
        }
        return null;
    }

    // ---------------- condicional / ciclos / seleccion ----------------

    @Override
    public Operando visitCondicional(ZetarianoParser.CondicionalContext ctx) {
        List<ZetarianoParser.ExpresionContext> condiciones = ctx.expresion();
        List<ZetarianoParser.CuerpoContext> cuerpos = ctx.cuerpo();
        boolean hayElse = cuerpos.size() > condiciones.size();

        String etiquetaFin = cuartetas.nuevaEtiqueta();
        for (int i = 0; i < condiciones.size(); i++) {
            Operando cond = visit(condiciones.get(i));
            exigirBooleano(condiciones.get(i), cond);

            String etiquetaSiguiente = cuartetas.nuevaEtiqueta();
            if (cond.esValor()) {
                cuartetas.agregar("if_false", cond.getTexto(), null, etiquetaSiguiente);
            }
            visit(cuerpos.get(i));
            cuartetas.agregar("goto", null, null, etiquetaFin);
            cuartetas.agregar("label", null, null, etiquetaSiguiente);
        }
        if (hayElse) {
            visit(cuerpos.get(cuerpos.size() - 1));
        }
        cuartetas.agregar("label", null, null, etiquetaFin);
        return null;
    }

    @Override
    public Operando visitCicloMientras(ZetarianoParser.CicloMientrasContext ctx) {
        String etiquetaInicio = cuartetas.nuevaEtiqueta();
        String etiquetaFin = cuartetas.nuevaEtiqueta();

        cuartetas.agregar("label", null, null, etiquetaInicio);
        Operando cond = visit(ctx.expresion());
        exigirBooleano(ctx.expresion(), cond);
        if (cond.esValor()) {
            cuartetas.agregar("if_false", cond.getTexto(), null, etiquetaFin);
        }

        pilaCiclos.push(new String[]{etiquetaInicio, etiquetaFin});
        visit(ctx.cuerpo());
        pilaCiclos.pop();

        cuartetas.agregar("goto", null, null, etiquetaInicio);
        cuartetas.agregar("label", null, null, etiquetaFin);
        return null;
    }

    @Override
    public Operando visitCicloHacer(ZetarianoParser.CicloHacerContext ctx) {
        String etiquetaInicio = cuartetas.nuevaEtiqueta();
        String etiquetaContinuar = cuartetas.nuevaEtiqueta();
        String etiquetaFin = cuartetas.nuevaEtiqueta();

        cuartetas.agregar("label", null, null, etiquetaInicio);
        pilaCiclos.push(new String[]{etiquetaContinuar, etiquetaFin});
        visit(ctx.bloque()); // cicloHacer siempre lleva 'bloque' (con llaves), no 'cuerpo' - ver Zetariano.g4
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
    public Operando visitCicloPara(ZetarianoParser.CicloParaContext ctx) {
        TablaSimbolos ambitoAnterior = ambitoActual;
        ambitoActual = new TablaSimbolos(ambitoAnterior);

        if (ctx.forInit() != null) {
            ZetarianoParser.ForInitContext init = ctx.forInit();
            if (init.declaracionVariable() != null) visit(init.declaracionVariable());
            else visit(init.asignacion());
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
        visit(ctx.cuerpo());
        pilaCiclos.pop();

        cuartetas.agregar("label", null, null, etiquetaActualizar);
        if (ctx.forActualizacion() != null) {
            ZetarianoParser.ForActualizacionContext act = ctx.forActualizacion();
            if (act.asignacion() != null) visit(act.asignacion());
            else visit(act.incrementoDecremento());
        }
        cuartetas.agregar("goto", null, null, etiquetaInicio);
        cuartetas.agregar("label", null, null, etiquetaFin);

        ambitoActual = ambitoAnterior;
        return null;
    }

    @Override
    public Operando visitSeleccion(ZetarianoParser.SeleccionContext ctx) {
        Operando selector = visit(ctx.expresion());
        List<ZetarianoParser.CasoSwitchContext> casos = ctx.casoSwitch();

        String etiquetaFin = cuartetas.nuevaEtiqueta();
        String[] etiquetasCuerpo = new String[casos.size()];
        for (int i = 0; i < casos.size(); i++) {
            etiquetasCuerpo[i] = cuartetas.nuevaEtiqueta();
        }

        for (int i = 0; i < casos.size(); i++) {
            ZetarianoParser.CasoSwitchContext caso = casos.get(i);
            if (caso.literal() != null) {
                Operando valorCaso = literalAOperando(caso.literal());
                if (selector.esValor() && !selector.getTipo().esCompatibleCon(valorCaso.getTipo())) {
                    reportarError(caso, "el valor de 'case' no es compatible con el tipo de 'switch(...)'");
                }
                if (selector.esValor()) {
                    String comparacion = cuartetas.nuevoTemporal();
                    cuartetas.agregar("==", selector.getTexto(), valorCaso.getTexto(), comparacion);
                    cuartetas.agregar("if_true", comparacion, null, etiquetasCuerpo[i]);
                }
            } else {
                cuartetas.agregar("goto", null, null, etiquetasCuerpo[i]); // 'default'
            }
        }
        cuartetas.agregar("goto", null, null, etiquetaFin);

        // 'break' dentro de un 'switch' sale de el; 'continue' no es valido aqui (etiquetaContinuar = null).
        pilaCiclos.push(new String[]{null, etiquetaFin});
        for (int i = 0; i < casos.size(); i++) {
            cuartetas.agregar("label", null, null, etiquetasCuerpo[i]);
            TablaSimbolos anterior = ambitoActual;
            ambitoActual = new TablaSimbolos(anterior);
            for (ZetarianoParser.SentenciaContext s : casos.get(i).sentencia()) {
                visit(s);
            }
            ambitoActual = anterior;
            // Sin 'goto' hacia etiquetaFin aca: el fallthrough entre casos es intencional (ver Zetariano.g4).
        }
        pilaCiclos.pop();
        cuartetas.agregar("label", null, null, etiquetaFin);
        return null;
    }

    private void exigirBooleano(ParserRuleContext ctx, Operando cond) {
        if (cond.esValor() && cond.getTipo().getCategoria() != Tipo.Categoria.BOOLEANO) {
            reportarError(ctx, "la condicion debe ser booleana, no " + cond.getTipo());
        }
    }

    // ---------------- expresiones ----------------

    @Override
    public Operando visitExpUnaria(ZetarianoParser.ExpUnariaContext ctx) {
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
    public Operando visitExpAditiva(ZetarianoParser.ExpAditivaContext ctx) {
        Operando izq = visit(ctx.expresion(0));
        Operando der = visit(ctx.expresion(1));
        if (!izq.esValor() || !der.esValor()) {
            return Operando.error();
        }
        String op = ctx.op.getText();

        // Solo '+' esta sobrecargado para concatenar: si cualquiera de los
        // dos lados es cadena, el resultado es cadena (el otro se convierte).
        if (op.equals("+") && (izq.getTipo().getCategoria() == Tipo.Categoria.CADENA || der.getTipo().getCategoria() == Tipo.Categoria.CADENA)) {
            String temporal = cuartetas.nuevoTemporal();
            cuartetas.agregar("concat", izq.getTexto(), der.getTexto(), temporal);
            return new Operando(Tipo.CADENA, temporal);
        }

        return operacionAritmetica(ctx, izq, der, op);
    }

    @Override
    public Operando visitExpMultiplicativa(ZetarianoParser.ExpMultiplicativaContext ctx) {
        return operacionAritmetica(ctx, visit(ctx.expresion(0)), visit(ctx.expresion(1)), ctx.op.getText());
    }

    private Operando operacionAritmetica(ParserRuleContext ctx, Operando izq, Operando der, String op) {
        if (!izq.esValor() || !der.esValor()) {
            return Operando.error();
        }
        if (!izq.getTipo().esNumerico() || !der.getTipo().esNumerico()) {
            reportarError(ctx, "'" + op + "' solo aplica entre valores numericos (o cadenas para concatenar con '+'), no "
                    + izq.getTipo() + " y " + der.getTipo());
            return Operando.error();
        }
        Tipo resultado = (izq.getTipo().getCategoria() == Tipo.Categoria.DECIMAL || der.getTipo().getCategoria() == Tipo.Categoria.DECIMAL)
                ? Tipo.DECIMAL : Tipo.ENTERO;
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar(op, izq.getTexto(), der.getTexto(), temporal);
        return new Operando(resultado, temporal);
    }

    @Override
    public Operando visitExpRelacional(ZetarianoParser.ExpRelacionalContext ctx) {
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
    public Operando visitExpIgualdad(ZetarianoParser.ExpIgualdadContext ctx) {
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
    public Operando visitExpAnd(ZetarianoParser.ExpAndContext ctx) {
        return operacionLogica(ctx, ctx.expresion(0), ctx.expresion(1), "&&");
    }

    @Override
    public Operando visitExpOr(ZetarianoParser.ExpOrContext ctx) {
        return operacionLogica(ctx, ctx.expresion(0), ctx.expresion(1), "||");
    }

    private Operando operacionLogica(ParserRuleContext ctx, ZetarianoParser.ExpresionContext ei, ZetarianoParser.ExpresionContext ed, String op) {
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
    public Operando visitExpTernaria(ZetarianoParser.ExpTernariaContext ctx) {
        Operando cond = visit(ctx.expresion(0));
        Operando siVerdadero = visit(ctx.expresion(1));
        Operando siFalso = visit(ctx.expresion(2));
        if (!cond.esValor() || !siVerdadero.esValor() || !siFalso.esValor()) {
            return Operando.error();
        }
        if (cond.getTipo().getCategoria() != Tipo.Categoria.BOOLEANO) {
            reportarError(ctx, "la condicion del operador ternario debe ser booleana, no " + cond.getTipo());
            return Operando.error();
        }
        boolean compatibles = siVerdadero.getTipo().esCompatibleCon(siFalso.getTipo()) || siFalso.getTipo().esCompatibleCon(siVerdadero.getTipo());
        if (!compatibles) {
            reportarError(ctx, "las dos ramas del operador ternario deben ser de tipos compatibles (" + siVerdadero.getTipo() + " vs " + siFalso.getTipo() + ")");
            return Operando.error();
        }
        Tipo resultado = siVerdadero.getTipo().esCompatibleCon(siFalso.getTipo()) ? siVerdadero.getTipo() : siFalso.getTipo();

        // 'cond ? a : b' se traduce con saltos, como un if/else que carga un temporal.
        String temporal = cuartetas.nuevoTemporal();
        String etiquetaFalso = cuartetas.nuevaEtiqueta();
        String etiquetaFin = cuartetas.nuevaEtiqueta();
        cuartetas.agregar("if_false", cond.getTexto(), null, etiquetaFalso);
        cuartetas.agregar("=", siVerdadero.getTexto(), null, temporal);
        cuartetas.agregar("goto", null, null, etiquetaFin);
        cuartetas.agregar("label", null, null, etiquetaFalso);
        cuartetas.agregar("=", siFalso.getTexto(), null, temporal);
        cuartetas.agregar("label", null, null, etiquetaFin);
        return new Operando(resultado, temporal);
    }

    @Override
    public Operando visitExpNuevoObjeto(ZetarianoParser.ExpNuevoObjetoContext ctx) {
        String nombreClase = ctx.IDENTIFICADOR().getText();
        SimboloClase clase = global.buscarClase(nombreClase);
        if (clase == null) {
            reportarError(ctx, "no existe una clase llamada '" + nombreClase + "'");
            return Operando.error();
        }

        List<ZetarianoParser.ExpresionContext> argsCtx = ctx.listaArgumentos() != null
                ? ctx.listaArgumentos().expresion() : Collections.<ZetarianoParser.ExpresionContext>emptyList();
        List<Operando> args = new ArrayList<>();
        for (ZetarianoParser.ExpresionContext a : argsCtx) {
            args.add(visit(a));
        }
        if (contieneError(args)) {
            return Operando.error();
        }

        List<Tipo> tipos = new ArrayList<>();
        for (Operando a : args) {
            tipos.add(a.getTipo());
        }
        SimboloInvocable constructor = buscarPorFirma(clase.getConstructores(), tipos);
        if (constructor == null) {
            reportarError(ctx, "'" + nombreClase + "' no tiene un constructor que reciba esos argumentos");
            return Operando.error();
        }

        for (Operando a : args) {
            cuartetas.agregar("param", a.getTexto(), null, null);
        }
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar("new", nombreClase, String.valueOf(args.size()), temporal);
        return new Operando(Tipo.clase(nombreClase), temporal);
    }

    @Override
    public Operando visitExpNuevoArreglo(ZetarianoParser.ExpNuevoArregloContext ctx) {
        Tipo base = resolvedorTipo.resolverTipoBase(ctx.tipoBase());
        List<ZetarianoParser.ExpresionContext> dimsCtx = ctx.expresion();

        List<String> textosTamanios = new ArrayList<>();
        boolean tamaniosValidos = true;
        for (ZetarianoParser.ExpresionContext d : dimsCtx) {
            Operando tam = visit(d);
            if (!tam.esValor() || tam.getTipo().getCategoria() != Tipo.Categoria.ENTERO) {
                reportarError(d, "el tamano de un arreglo debe ser entero");
                tamaniosValidos = false;
            } else {
                textosTamanios.add(tam.getTexto());
            }
        }
        if (!tamaniosValidos) {
            return Operando.error();
        }

        Tipo tipoResultado = Tipo.arreglo(base, dimsCtx.size());
        String temporal = cuartetas.nuevoTemporal();
        cuartetas.agregar("new_array", base.toString(), String.join(",", textosTamanios), temporal);
        return new Operando(tipoResultado, temporal);
    }

    @Override
    public Operando visitExpParentesis(ZetarianoParser.ExpParentesisContext ctx) {
        return visit(ctx.expresion());
    }

    @Override
    public Operando visitExpAcceso(ZetarianoParser.ExpAccesoContext ctx) {
        return resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual, claseActual);
    }

    @Override
    public Operando visitExpLiteral(ZetarianoParser.ExpLiteralContext ctx) {
        return literalAOperando(ctx.literal());
    }

    @Override
    public Operando visitLlamada(ZetarianoParser.LlamadaContext ctx) {
        return resolvedorAcceso.resolverAcceso(ctx.acceso(), ambitoActual, claseActual);
    }

    private Operando literalAOperando(ZetarianoParser.LiteralContext ctx) {
        if (ctx.ENTERO() != null) return new Operando(Tipo.ENTERO, ctx.ENTERO().getText());
        if (ctx.DECIMAL() != null) return new Operando(Tipo.DECIMAL, ctx.DECIMAL().getText());
        if (ctx.CADENA() != null) return new Operando(Tipo.CADENA, ctx.CADENA().getText());
        if (ctx.CARACTER() != null) return new Operando(Tipo.CARACTER, ctx.CARACTER().getText());
        if (ctx.getText().equals("null")) return new Operando(Tipo.NULO, "null");
        return new Operando(Tipo.BOOLEANO, ctx.getText()); // 'true' / 'false'
    }

    private boolean contieneError(List<Operando> operandos) {
        for (Operando o : operandos) {
            if (o.esError()) {
                return true;
            }
        }
        return false;
    }

    private void reportarError(ParserRuleContext ctx, String mensaje) {
        errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), mensaje);
    }
}