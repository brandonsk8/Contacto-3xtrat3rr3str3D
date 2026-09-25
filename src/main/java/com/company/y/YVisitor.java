package com.company.y;

import com.company.ir.TablaCuartetas;
import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.GestorErrores;
import com.company.semantico.Operando;
import com.company.semantico.Simbolo;
import com.company.semantico.SimboloEstructura;
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
 * Pasada 2 sobre un archivo Y?: recorre el cuerpo de cada funcion ya
 * registrada por RegistradorFirmasY (pasada 1), valida tipos y llena la
 * TablaSimbolos de cada ambito, emitiendo Cuarteta's en TablaCuartetas
 * en el mismo recorrido.
 *
 * Regla seguida en todo el archivo: antes de usar el Tipo de un
 * Operando siempre se revisa Operando.esValor() primero, para no
 * reportar errores en cascada si ya hubo uno mas abajo en la expresion.
 */
public class YVisitor extends YParserBaseVisitor<Operando> {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final TablaCuartetas cuartetas;

    private TablaSimbolos ambitoActual;
    private SimboloInvocable funcionActual;

    // Cada elemento es {etiquetaContinuar, etiquetaFin} del ciclo/elegir mas
    // interno en el que estamos parados; etiquetaContinuar es null para un
    // 'elegir' (un 'continuar' ahi es invalido, un 'romper' si es valido).
    private final Deque<String[]> pilaCiclos = new ArrayDeque<>();

    public YVisitor(TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas) {
        this.global = global;
        this.errores = errores;
        this.cuartetas = cuartetas;
    }

    /** Punto de entrada: recorre el cuerpo de cada funcion de %funciones. */
    public void analizarPrograma(YParser.ProgramaContext ctx) {
        for (YParser.DefinicionFuncionContext def : ctx.seccionFunciones().definicionFuncion()) {
            visit(def);
        }
    }

    // ---------------- funciones ----------------

    @Override
    public Operando visitDefinicionFuncion(YParser.DefinicionFuncionContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();
        SimboloInvocable funcion = global.buscarFuncion(nombre);
        if (funcion == null) {
            return null; // no deberia pasar, RegistradorFirmasY ya la registro
        }

        SimboloInvocable funcionAnterior = funcionActual;
        TablaSimbolos ambitoAnterior = ambitoActual;

        funcionActual = funcion;
        ambitoActual = new TablaSimbolos(null); // Y? no tiene funciones anidadas ni closures
        for (Simbolo parametro : funcion.getParametros()) {
            if (!ambitoActual.declarar(parametro)) {
                reportarError(ctx, "el parametro '" + parametro.getNombre() + "' esta repetido en '" + nombre + "'");
            }
        }
        funcion.setAmbitoLocal(ambitoActual);

        cuartetas.agregar("label", null, null, "func_" + nombre);
        visit(ctx.cuerpo());

        funcionActual = funcionAnterior;
        ambitoActual = ambitoAnterior;
        return null;
    }

    // ---------------- sentencias ----------------

    @Override
    public Operando visitSentenciaCompuesta(YParser.SentenciaCompuestaContext ctx) {
        if (ctx.definicionEstructura() != null) {
            // La gramatica lo permite pero solo se soportan %estructuras a nivel de archivo
            reportarError(ctx, "no se soportan estructuras definidas dentro de una funcion (todavia no implementado)");
            return null;
        }
        return visitChildren(ctx);
    }

    @Override
    public Operando visitSentenciaSimple(YParser.SentenciaSimpleContext ctx) {
        if (ctx.declaracionVariable() != null) return visit(ctx.declaracionVariable());
        if (ctx.asignacion() != null) return visit(ctx.asignacion());
        if (ctx.incrementoDecremento() != null) return visit(ctx.incrementoDecremento());
        if (ctx.ROMPER() != null) return manejarRomper(ctx);
        if (ctx.CONTINUAR() != null) return manejarContinuar(ctx);
        if (ctx.RETORNAR() != null) return manejarRetornar(ctx);
        if (ctx.llamada() != null) {
            visit(ctx.llamada());
            return null;
        }
        return null;
    }

    @Override
    public Operando visitDeclaracionVariable(YParser.DeclaracionVariableContext ctx) {
        String nombre = ctx.IDENTIFICADOR().getText();

        Tipo tipoBase = resolverTipo(ctx.tipo());
        int dims = ctx.expresion().size(); // '[' expresion ']' * -> dimensiones del arreglo, no el inicializador
        Tipo tipoFinal = dims > 0 ? Tipo.arreglo(tipoBase, dims) : tipoBase;

        Simbolo variable = new Simbolo(nombre, tipoFinal, CategoriaSimbolo.VARIABLE, ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine());
        if (dims == 1) {
            // Igual que en un campo de estructura, GeneradorC solo puede
            // declararlo como un arreglo C real ('tipo nombre[N];') si N
            // es un entero literal - se guarda el texto tal cual.
            variable.setTamanioArreglo(ctx.expresion(0).getText());
        }
        if (!ambitoActual.declarar(variable)) {
            reportarError(ctx, "ya existe una variable llamada '" + nombre + "' en este ambito");
            return null;
        }

        if (ctx.inicializador() != null) {
            YParser.InicializadorContext init = ctx.inicializador();
            if (init.expresion() != null) {
                Operando valor = visit(init.expresion());
                if (valor.esValor() && !tipoFinal.esCompatibleCon(valor.getTipo())) {
                    reportarError(ctx, "no se puede inicializar '" + nombre + "' (" + tipoFinal + ") con un valor de tipo " + valor.getTipo());
                } else if (valor.esValor()) {
                    cuartetas.agregar("=", valor.getTexto(), null, nombre);
                }
            } else if (init.listaExpresiones() != null) {
                // Inicializador tipo lista '{a, b, c}' de un arreglo de 1
                // dimension: cada elemento se valida contra el tipo base y,
                // si es valido, se emite como 'nombre[i] = valor' (igual que
                // una asignacion normal a un indice).
                List<YParser.ExpresionContext> valores = init.listaExpresiones().expresion();
                for (int i = 0; i < valores.size(); i++) {
                    Operando v = visit(valores.get(i));
                    if (!v.esValor()) {
                        continue;
                    }
                    if (dims != 1 || !tipoBase.esCompatibleCon(v.getTipo())) {
                        reportarError(valores.get(i), "el elemento " + i + " de '" + nombre + "' deberia ser " + tipoBase + ", se recibio " + v.getTipo());
                        continue;
                    }
                    cuartetas.agregar("=", v.getTexto(), null, nombre + "[" + i + "]");
                }
            }
        }
        return null;
    }

    @Override
    public Operando visitAsignacion(YParser.AsignacionContext ctx) {
        Operando destino = resolverAcceso(ctx.acceso());
        Operando valor = visit(ctx.expresion());
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
    public Operando visitIncrementoDecremento(YParser.IncrementoDecrementoContext ctx) {
        Operando destino = resolverAcceso(ctx.acceso());
        if (!destino.esValor()) {
            return null;
        }
        String operador = ctx.getChild(1).getText(); // '++' o '--'
        if (!destino.getTipo().esNumerico()) {
            reportarError(ctx, "'" + operador + "' solo aplica a valores numericos, no a " + destino.getTipo());
            return null;
        }
        cuartetas.agregar(operador, destino.getTexto(), null, destino.getTexto());
        return null;
    }

    private Operando manejarRomper(YParser.SentenciaSimpleContext ctx) {
        if (pilaCiclos.isEmpty()) {
            reportarError(ctx, "'romper' fuera de un ciclo o de un 'elegir'");
        } else {
            cuartetas.agregar("goto", null, null, pilaCiclos.peek()[1]);
        }
        return null;
    }

    private Operando manejarContinuar(YParser.SentenciaSimpleContext ctx) {
        if (pilaCiclos.isEmpty()) {
            reportarError(ctx, "'continuar' fuera de un ciclo");
        } else if (pilaCiclos.peek()[0] == null) {
            reportarError(ctx, "'continuar' no es valido dentro de un 'elegir'");
        } else {
            cuartetas.agregar("goto", null, null, pilaCiclos.peek()[0]);
        }
        return null;
    }

    private Operando manejarRetornar(YParser.SentenciaSimpleContext ctx) {
        Tipo esperado = funcionActual.getTipoRetorno();
        if (ctx.expresion() != null) {
            Operando valor = visit(ctx.expresion());
            if (esperado.getCategoria() == Tipo.Categoria.VOID) {
                reportarError(ctx, "'" + funcionActual.getNombre() + "' no retorna nada, no puede llevar 'retornar <valor>'");
            } else if (valor.esValor() && !esperado.esCompatibleCon(valor.getTipo())) {
                reportarError(ctx, "'retornar' espera " + esperado + ", se recibio " + valor.getTipo());
            }
            if (valor.esValor()) {
                cuartetas.agregar("return", valor.getTexto(), null, null);
            }
        } else {
            if (esperado.getCategoria() != Tipo.Categoria.VOID) {
                reportarError(ctx, "'" + funcionActual.getNombre() + "' debe retornar un valor de tipo " + esperado);
            }
            cuartetas.agregar("return", null, null, null);
        }
        return null;
    }

    // ---------------- condicional / ciclos / seleccion ----------------

    @Override
    public Operando visitCondicional(YParser.CondicionalContext ctx) {
        List<YParser.ExpresionContext> condiciones = ctx.expresion();
        List<YParser.CuerpoContext> cuerpos = ctx.cuerpo();
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
    public Operando visitCicloMientras(YParser.CicloMientrasContext ctx) {
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
    public Operando visitCicloHacer(YParser.CicloHacerContext ctx) {
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
    public Operando visitCicloPara(YParser.CicloParaContext ctx) {
        TablaSimbolos ambitoAnterior = ambitoActual;
        ambitoActual = new TablaSimbolos(ambitoAnterior); // el header del 'para' (ej. su variable de control) es su propio ambito

        if (ctx.forInit() != null) {
            YParser.ForInitContext init = ctx.forInit();
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

        // 'continuar' salta a la etapa de actualizacion, no al inicio: el
        // incremento del 'para' debe correr igual aunque se haga continuar.
        pilaCiclos.push(new String[]{etiquetaActualizar, etiquetaFin});
        visitConNuevoAmbito(ctx.cuerpo());
        pilaCiclos.pop();

        cuartetas.agregar("label", null, null, etiquetaActualizar);
        if (ctx.forActualizacion() != null) {
            YParser.ForActualizacionContext act = ctx.forActualizacion();
            if (act.asignacion() != null) visit(act.asignacion());
            else visit(act.incrementoDecremento());
        }
        cuartetas.agregar("goto", null, null, etiquetaInicio);
        cuartetas.agregar("label", null, null, etiquetaFin);

        ambitoActual = ambitoAnterior;
        return null;
    }

    @Override
    public Operando visitSeleccion(YParser.SeleccionContext ctx) {
        Operando selector = visit(ctx.expresion());
        List<YParser.CasoSeleccionContext> casos = ctx.casoSeleccion();

        String etiquetaFin = cuartetas.nuevaEtiqueta();
        String[] etiquetasCuerpo = new String[casos.size()];
        for (int i = 0; i < casos.size(); i++) {
            etiquetasCuerpo[i] = cuartetas.nuevaEtiqueta();
        }

        // Compara el selector contra cada 'caso' en orden; 'siempre' hace match incondicional
        for (int i = 0; i < casos.size(); i++) {
            YParser.CasoSeleccionContext caso = casos.get(i);
            if (caso.literal() != null) {
                Operando valorCaso = literalAOperando(caso.literal());
                if (selector.esValor() && !selector.getTipo().esCompatibleCon(valorCaso.getTipo())) {
                    reportarError(caso, "el valor de 'caso' no es compatible con el tipo de 'elegir(...)'");
                }
                if (selector.esValor()) {
                    String comparacion = cuartetas.nuevoTemporal();
                    cuartetas.agregar("==", selector.getTexto(), valorCaso.getTexto(), comparacion);
                    cuartetas.agregar("if_true", comparacion, null, etiquetasCuerpo[i]);
                }
            } else {
                cuartetas.agregar("goto", null, null, etiquetasCuerpo[i]); // 'siempre'
            }
        }
        cuartetas.agregar("goto", null, null, etiquetaFin); // ningun 'caso' hizo match y no hay 'siempre'

        // 'romper' dentro de un 'elegir' sale del 'elegir'; 'continuar' no es valido aqui (etiquetaContinuar = null).
        pilaCiclos.push(new String[]{null, etiquetaFin});
        for (int i = 0; i < casos.size(); i++) {
            cuartetas.agregar("label", null, null, etiquetasCuerpo[i]);
            visitConNuevoAmbito(casos.get(i).cuerpo());
            // Sin 'goto' hacia etiquetaFin aca: el fallthrough entre casos es intencional
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

    private void visitConNuevoAmbito(YParser.CuerpoContext cuerpo) {
        TablaSimbolos anterior = ambitoActual;
        ambitoActual = new TablaSimbolos(anterior);
        visit(cuerpo);
        ambitoActual = anterior;
    }

    // ---------------- expresiones ----------------

    @Override
    public Operando visitExpUnaria(YParser.ExpUnariaContext ctx) {
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
    public Operando visitExpMultiplicativa(YParser.ExpMultiplicativaContext ctx) {
        return operacionAritmetica(ctx, ctx.expresion(0), ctx.expresion(1), ctx.op.getText());
    }

    @Override
    public Operando visitExpAditiva(YParser.ExpAditivaContext ctx) {
        return operacionAritmetica(ctx, ctx.expresion(0), ctx.expresion(1), ctx.op.getText());
    }

    private Operando operacionAritmetica(ParserRuleContext ctx, YParser.ExpresionContext ei, YParser.ExpresionContext ed, String op) {
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
    public Operando visitExpRelacional(YParser.ExpRelacionalContext ctx) {
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
    public Operando visitExpIgualdad(YParser.ExpIgualdadContext ctx) {
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
    public Operando visitExpAnd(YParser.ExpAndContext ctx) {
        return operacionLogica(ctx, ctx.expresion(0), ctx.expresion(1), "&&");
    }

    @Override
    public Operando visitExpOr(YParser.ExpOrContext ctx) {
        return operacionLogica(ctx, ctx.expresion(0), ctx.expresion(1), "||");
    }

    private Operando operacionLogica(ParserRuleContext ctx, YParser.ExpresionContext ei, YParser.ExpresionContext ed, String op) {
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
    public Operando visitExpParentesis(YParser.ExpParentesisContext ctx) {
        return visit(ctx.expresion());
    }

    @Override
    public Operando visitExpAcceso(YParser.ExpAccesoContext ctx) {
        return resolverAcceso(ctx.acceso());
    }

    @Override
    public Operando visitExpLiteral(YParser.ExpLiteralContext ctx) {
        return literalAOperando(ctx.literal());
    }

    @Override
    public Operando visitLlamada(YParser.LlamadaContext ctx) {
        return resolverAcceso(ctx.acceso());
    }

    private Operando literalAOperando(YParser.LiteralContext ctx) {
        if (ctx.ENTERO() != null) return new Operando(Tipo.ENTERO, ctx.ENTERO().getText());
        if (ctx.DECIMAL() != null) return new Operando(Tipo.DECIMAL, ctx.DECIMAL().getText());
        if (ctx.CADENA() != null) return new Operando(Tipo.CADENA, ctx.CADENA().getText());
        if (ctx.CARACTER() != null) return new Operando(Tipo.CARACTER, ctx.CARACTER().getText());
        return new Operando(Tipo.BOOLEANO, ctx.getText()); // 'verdadero' / 'falso'
    }

    // ---------------- acceso (variable / campo / indice / llamada) ----------------

    private static final int SUFIJO_CAMPO = 0;
    private static final int SUFIJO_INDICE = 1;
    private static final int SUFIJO_LLAMADA = 2;

    private int tipoDeSufijo(YParser.SufijoAccesoContext ctx) {
        String primero = ctx.getChild(0).getText();
        if (primero.equals(".")) return SUFIJO_CAMPO;
        if (primero.equals("[")) return SUFIJO_INDICE;
        return SUFIJO_LLAMADA; // "("
    }

    /**
     * Resuelve una cadena 'acceso' completa: variable simple, indexacion
     * de arreglo, acceso a campo de estructura, o llamada a funcion. En
     * Y? una llamada solo puede ser el unico sufijo (no hay metodos
     * encadenables como en Zetariano).
     */
    private Operando resolverAcceso(YParser.AccesoContext ctx) {
        String nombreBase = ctx.IDENTIFICADOR().getText();
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();
        List<YParser.SufijoAccesoContext> sufijos = ctx.sufijoAcceso();

        if (!sufijos.isEmpty() && tipoDeSufijo(sufijos.get(0)) == SUFIJO_LLAMADA) {
            if (sufijos.size() > 1) {
                reportarError(ctx, "no se puede seguir accediendo despues de llamar a una funcion en Y?: '" + nombreBase + "(...)'");
                return Operando.error();
            }
            return resolverLlamadaFuncion(nombreBase, sufijos.get(0), linea, columna);
        }

        Simbolo simbolo = ambitoActual.buscar(nombreBase);
        if (simbolo == null) {
            errores.reportar(linea, columna, "variable no declarada: '" + nombreBase + "'");
            return Operando.error();
        }

        Tipo tipoActual = simbolo.getTipo();
        String textoActual = nombreBase;

        for (YParser.SufijoAccesoContext sufijo : sufijos) {
            int tipoSufijo = tipoDeSufijo(sufijo);
            if (tipoSufijo == SUFIJO_INDICE) {
                if (tipoActual.getCategoria() != Tipo.Categoria.ARREGLO) {
                    reportarError(sufijo, "'" + textoActual + "' no es un arreglo, no se puede indexar");
                    return Operando.error();
                }
                Operando indice = visit(sufijo.expresion());
                if (indice.esValor() && indice.getTipo().getCategoria() != Tipo.Categoria.ENTERO) {
                    reportarError(sufijo, "el indice debe ser entero, no " + indice.getTipo());
                }
                Tipo base = tipoActual.getTipoBase();
                tipoActual = tipoActual.getDimensiones() > 1
                        ? Tipo.arreglo(base, tipoActual.getDimensiones() - 1)
                        : base;
                textoActual = textoActual + "[" + (indice.esValor() ? indice.getTexto() : "?") + "]";
            } else if (tipoSufijo == SUFIJO_CAMPO) {
                String nombreCampo = sufijo.IDENTIFICADOR().getText();
                if (tipoActual.getCategoria() != Tipo.Categoria.ESTRUCTURA) {
                    reportarError(sufijo, "'" + textoActual + "' no es una estructura, no tiene campo '" + nombreCampo + "'");
                    return Operando.error();
                }
                SimboloEstructura estructura = global.buscarEstructura(tipoActual.getNombreDefinido());
                Simbolo campo = estructura != null ? estructura.buscarCampo(nombreCampo) : null;
                if (campo == null) {
                    reportarError(sufijo, "la estructura '" + tipoActual.getNombreDefinido() + "' no tiene un campo '" + nombreCampo + "'");
                    return Operando.error();
                }
                tipoActual = campo.getTipo();
                textoActual = textoActual + "." + nombreCampo;
            } else {
                reportarError(sufijo, "no se puede llamar a '" + textoActual + "': no es una funcion");
                return Operando.error();
            }
        }

        return new Operando(tipoActual, textoActual);
    }

    /**
     * 'imprimir' y 'leer' son funciones del sistema, no estan en
     * %funciones, asi que se resuelven aqui como casos especiales antes
     * de buscar en la tabla global.
     */
    private Operando resolverLlamadaFuncion(String nombre, YParser.SufijoAccesoContext sufijoLlamada, int linea, int columna) {
        List<YParser.ExpresionContext> argsCtx = sufijoLlamada.listaArgumentos() != null
                ? sufijoLlamada.listaArgumentos().expresion()
                : Collections.<YParser.ExpresionContext>emptyList();

        List<Operando> args = new ArrayList<>();
        for (YParser.ExpresionContext a : argsCtx) {
            args.add(visit(a));
        }

        if (nombre.equals("imprimir")) {
            if (args.isEmpty()) {
                errores.reportar(linea, columna, "'imprimir' necesita al menos un argumento");
            }
            for (Operando arg : args) {
                if (arg.esValor()) {
                    cuartetas.agregar("print", arg.getTexto(), null, null);
                }
            }
            return Operando.sinValor();
        }
        if (nombre.equals("leer")) {
            if (!args.isEmpty()) {
                errores.reportar(linea, columna, "'leer' no recibe argumentos");
            }
            String temporal = cuartetas.nuevoTemporal();
            cuartetas.agregar("read", null, null, temporal);
            return new Operando(Tipo.CADENA, temporal); // se asume CADENA (lectura de texto crudo)
        }

        SimboloInvocable funcion = global.buscarFuncion(nombre);
        if (funcion == null) {
            errores.reportar(linea, columna, "funcion no declarada: '" + nombre + "'");
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

    // ---------------- utilidades ----------------

    /** 'tipo' es 'tipoPrimitivo' o el IDENTIFICADOR de una estructura ya conocida. */
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
            reportarError(ctx, "tipo desconocido: '" + nombre + "'");
            return Tipo.ERROR;
        }
        return tipo;
    }

    private void reportarError(ParserRuleContext ctx, String mensaje) {
        errores.reportar(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), mensaje);
    }
}