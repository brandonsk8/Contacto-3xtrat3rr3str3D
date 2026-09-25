package com.company.y;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;

/**
 * Clase base del lexer de Y?, generada como superclase de YLexer
 * (ver YLexer.g4: "options { superClass = YLexerBase; }").
 *
 * Y? usa indentacion significativa para delimitar bloques (como
 * Python), en vez de llaves. ANTLR4 no soporta esto de forma nativa:
 * la gramatica NO puede "ver" cuantos espacios hay al inicio de una
 * linea de forma declarativa. La solucion estandar (la misma que usa
 * la gramatica oficial de Python3 en el repositorio antlr/grammars-v4)
 * es esta: sobreescribir nextToken() para interceptar cada salto de
 * linea real (NEWLINE_RAW, ver YLexer.g4) y, segun cuanto cambio la
 * indentacion respecto a la linea anterior, insertar tokens
 * sinteticos NEWLINE, INDENT y/o DEDENT antes de seguir.
 *
 * El parser (Y.g4) nunca ve NEWLINE_RAW: solo ve NEWLINE, INDENT y
 * DEDENT, como si fueran tokens normales.
 */
public abstract class YLexerBase extends Lexer {

    /** Pila de niveles de indentacion vistos; siempre empieza en columna 0. */
    private final Deque<Integer> pilaIndentacion = new ArrayDeque<>();

    /** Cola de tokens ya calculados, pendientes de devolver uno por uno. */
    private final LinkedList<Token> tokensPendientes = new LinkedList<>();

    /** Profundidad de parentesis/corchetes/llaves abiertos sin cerrar. */
    private int profundidadParentesis = 0;

    /** Evita reprocesar el EOF si nextToken() se vuelve a llamar despues de el. */
    private boolean eofProcesado = false;

    /**
     * Tipo del ultimo token REALMENTE devuelto al parser (no cuenta lo
     * que haya quedado en tokensPendientes sin entregar todavia). Solo
     * se usa al llegar a EOF, para decidir si hace falta sintetizar un
     * NEWLINE de cierre (ver procesarEOF). Antes esto se resolvia con
     * una bandera "iniciandoArchivo" que quedo MAL: suprimia el primer
     * NEWLINE del archivo pensando que era una linea en blanco inicial,
     * pero en Y? el primer salto de linea real (el que separa
     * '%funciones' de la primera funcion) SI es un separador valido -
     * Y? nunca tiene lineas en blanco antes del primer token, asi que
     * ese caso ni existe. Este campo reemplaza esa logica por algo que
     * si es correcto.
     */
    private Integer ultimoTipoTokenDevuelto = null;

    protected YLexerBase(CharStream input) {
        super(input);
        pilaIndentacion.push(0);
    }

    @Override
    public void reset() {
        pilaIndentacion.clear();
        pilaIndentacion.push(0);
        tokensPendientes.clear();
        profundidadParentesis = 0;
        eofProcesado = false;
        ultimoTipoTokenDevuelto = null;
        super.reset();
    }

    @Override
    public Token nextToken() {
        Token resultado = obtenerSiguienteToken();
        ultimoTipoTokenDevuelto = resultado.getType();
        return resultado;
    }

    private Token obtenerSiguienteToken() {
        if (!tokensPendientes.isEmpty()) {
            return tokensPendientes.poll();
        }

        Token siguiente = super.nextToken();

        switch (siguiente.getType()) {
            case YLexer.PAR_ABRE:
            case YLexer.CORCHETE_ABRE:
            case YLexer.LLAVE_ABRE:
                profundidadParentesis++;
                return siguiente;

            case YLexer.PAR_CIERRA:
            case YLexer.CORCHETE_CIERRA:
            case YLexer.LLAVE_CIERRA:
                if (profundidadParentesis > 0) {
                    profundidadParentesis--;
                }
                return siguiente;

            case YLexer.NEWLINE_RAW:
                if (profundidadParentesis > 0) {
                    // Dentro de (), [] o {} un salto de linea no separa
                    // sentencias (igual que en Python); se descarta y se
                    // sigue leyendo como si no hubiera pasado nada.
                    return obtenerSiguienteToken();
                }
                return procesarNewline(siguiente);

            case Token.EOF:
                return procesarEOF(siguiente);

            default:
                return siguiente;
        }
    }

    /**
     * Convierte un NEWLINE_RAW (que trae, ademas del salto de linea, toda
     * la indentacion de la siguiente linea con contenido real) en la
     * secuencia de tokens sinteticos que le corresponde: siempre un
     * NEWLINE logico y, si la indentacion subio o bajo respecto a la
     * linea anterior, ademas un INDENT o una serie de DEDENT.
     */
    private Token procesarNewline(Token tokenNewlineRaw) {
        int indentacion = calcularIndentacion(tokenNewlineRaw.getText());

        tokensPendientes.add(crearToken(tokenNewlineRaw, YLexer.NEWLINE, "\\n"));

        int nivelActual = pilaIndentacion.peek();
        if (indentacion > nivelActual) {
            pilaIndentacion.push(indentacion);
            tokensPendientes.add(crearToken(tokenNewlineRaw, YLexer.INDENT, "<INDENT>"));
        } else if (indentacion < nivelActual) {
            while (pilaIndentacion.peek() > indentacion) {
                pilaIndentacion.pop();
                tokensPendientes.add(crearToken(tokenNewlineRaw, YLexer.DEDENT, "<DEDENT>"));
            }
            // Si la indentacion resultante no calza exactamente con
            // ningun nivel previo de la pila, es un error de indentacion
            // inconsistente en el archivo fuente. No se reporta aqui
            // como error lexico dedicado: el parser lo va a rechazar como
            // token inesperado en cuanto intente encajar la secuencia de
            // INDENT/DEDENT resultante, lo cual alcanza para detectarlo.
        }

        // Con el NEWLINE que se acaba de agregar arriba, la cola nunca
        // queda vacia aqui.
        return tokensPendientes.poll();
    }

    /**
     * Al llegar al final del archivo hay que cerrar "en cascada" todos
     * los bloques que hayan quedado abiertos (uno o mas DEDENT). Si el
     * archivo no terminaba con un salto de linea real -es decir, el
     * ultimo token devuelto al parser no fue ya un NEWLINE/INDENT/
     * DEDENT- hace falta sintetizar un NEWLINE de cierre antes, para
     * que la ultima sentencia quede bien terminada (los archivos de Y?
     * no necesariamente terminan con un salto de linea explicito). Si
     * SI terminaba con salto de linea, ese NEWLINE ya se emitio en la
     * llamada a procesarNewline correspondiente y no hay que repetirlo.
     */
    private Token procesarEOF(Token tokenEOF) {
        if (eofProcesado) {
            return tokenEOF;
        }
        eofProcesado = true;

        boolean archivoTerminoSinSaltoDeLinea = ultimoTipoTokenDevuelto != null
                && ultimoTipoTokenDevuelto != YLexer.NEWLINE
                && ultimoTipoTokenDevuelto != YLexer.INDENT
                && ultimoTipoTokenDevuelto != YLexer.DEDENT;
        if (archivoTerminoSinSaltoDeLinea) {
            tokensPendientes.add(crearToken(tokenEOF, YLexer.NEWLINE, "\\n"));
        }
        while (pilaIndentacion.peek() > 0) {
            pilaIndentacion.pop();
            tokensPendientes.add(crearToken(tokenEOF, YLexer.DEDENT, "<DEDENT>"));
        }
        tokensPendientes.add(tokenEOF);

        return tokensPendientes.poll();
    }

    /**
     * El texto de un NEWLINE_RAW es uno o mas saltos de linea seguidos,
     * cada uno con su propia indentacion y, opcionalmente, un
     * comentario de linea completa (ver la regla en YLexer.g4). Solo
     * importa la indentacion de la ULTIMA linea del grupo -- por eso se
     * mide desde el ultimo '\n' del texto, no desde el principio; asi,
     * lineas en blanco o de puro comentario intercaladas no generan
     * NEWLINE/INDENT/DEDENT espurios.
     */
    private int calcularIndentacion(String textoNewlineRaw) {
        int ultimoSalto = textoNewlineRaw.lastIndexOf('\n');
        int columna = 0;
        for (int i = ultimoSalto + 1; i < textoNewlineRaw.length(); i++) {
            char c = textoNewlineRaw.charAt(i);
            if (c == '\t') {
                // Un tab avanza hasta la siguiente columna multiplo de 8
                // (misma convencion que usa el lexer de referencia de
                // Python para mezclar tabs y espacios).
                columna += 8 - (columna % 8);
            } else if (c == ' ') {
                columna++;
            } else {
                // Llegamos a un caracter que no es espacio/tab (el resto
                // de un comentario que haya quedado embebido en el
                // token): no cuenta para la indentacion.
                break;
            }
        }
        return columna;
    }

    private Token crearToken(Token modelo, int tipo, String texto) {
        CommonToken token = new CommonToken(modelo);
        token.setType(tipo);
        token.setText(texto);
        return token;
    }
}
