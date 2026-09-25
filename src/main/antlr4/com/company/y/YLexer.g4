lexer grammar YLexer;

options {
    superClass = YLexerBase;
}

// NEWLINE, INDENT y DEDENT no los reconoce ninguna regla de abajo
// directamente: se SINTETIZAN en YLexerBase.nextToken() a partir de
// NEWLINE_RAW (ver esa clase). Se declaran aqui solo para que existan
// como tipos de token validos que el parser (Y.g4) pueda referenciar.
tokens { NEWLINE, INDENT, DEDENT }

// ============================================================
// Lexer de Y? (.y) - lenguaje case sensitive, con indentacion
// significativa (como Python): la indentacion es la que delimita
// los bloques, no hay llaves para eso. Ver YLexerBase.java para la
// logica de conteo de indentacion.
// ============================================================

// Palabras reservadas (deben ir ANTES que IDENTIFICADOR para que
// ANTLR les de prioridad sobre el identificador generico)
PORC_ESTRUCTURAS : '%estructuras';
PORC_FUNCIONES   : '%funciones';
ESTRUCTURA  : 'estructura';
DEFINIR     : 'definir';
RETORNAR    : 'retornar';
SI          : 'si';
ENTONCES    : 'entonces';
SINO        : 'sino';
CONTRARIO   : 'contrario';
ELEGIR      : 'elegir';
CASO        : 'caso';
SIEMPRE     : 'siempre';
ROMPER      : 'romper';
CONTINUAR   : 'continuar';
PARA        : 'para';
MIENTRAS    : 'mientras';
HACER       : 'hacer';
VERDADERO   : 'verdadero';
FALSO       : 'falso';

ENTERO_T    : 'entero';
CADENA_T    : 'cadena';
FLOTANTE_T  : 'flotante';
CARACTER_T  : 'caracter';
BOOL_T      : 'bool';

// Operadores y simbolos
FLECHA          : '->';
OP_AND          : '&&';
OP_OR           : '||';
OP_IGUAL        : '==';
OP_DISTINTO     : '!=';
OP_INC          : '++';
OP_DEC          : '--';
OP_MENOR        : '<';
OP_MAYOR        : '>';
OP_NOT          : '!';
OP_MAS          : '+';
OP_MENOS        : '-';
OP_MULT         : '*';
OP_DIV          : '/';
OP_ASIGNA       : '=';
DOS_PUNTOS      : ':';
// El resto de Y? nunca usa ';' (las sentencias terminan con NEWLINE),
// pero el encabezado de 'para(...)' SI lo usa como separador de sus
// 3 clausulas: "para(entero i = 0; i < 10; i++):"
PUNTO_COMA      : ';';
COMA            : ',';
PUNTO           : '.';
PAR_ABRE        : '(';
PAR_CIERRA      : ')';
LLAVE_ABRE      : '{';
LLAVE_CIERRA    : '}';
CORCHETE_ABRE   : '[';
CORCHETE_CIERRA : ']';

IDENTIFICADOR
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

ENTERO
    : [0-9]+
    ;

DECIMAL
    : [0-9]+ '.' [0-9]+
    ;

CADENA
    : '"' ( '\\' . | ~["\\\r\n] )* '"'
    ;

CARACTER
    : '\'' ( '\\' . | ~['\\\r\n] ) '\''
    ;

// Comentario que aparece despues de codigo real en la misma linea.
// Un comentario que ocupa una linea completa queda cubierto dentro
// de NEWLINE_RAW (mas abajo), para que no genere un NEWLINE espurio
// entre dos sentencias.
COMENTARIO_LINEA
    : '//' ~[\r\n]* -> skip
    ;

COMENTARIO_BLOQUE
    : '/*' .*? '*/' -> skip
    ;

// Espacios/tabs DENTRO de una linea (no al inicio de linea: eso lo
// captura NEWLINE_RAW, que es quien mide la indentacion)
ESPACIO_LINEA
    : [ \t]+ -> skip
    ;

// Captura un salto de linea junto con TODA la indentacion, y
// opcionalmente lineas en blanco o de puro comentario que le sigan,
// hasta topar con la siguiente linea con contenido real. Al usar '+'
// se colapsan varias lineas en blanco/comentario en un solo token,
// para que YLexerBase.calcularIndentacion (que solo mira lo que hay
// despues del ULTIMO '\n' del texto) no genere NEWLINE/INDENT/DEDENT
// espurios por cada linea en blanco o comentario suelto.
NEWLINE_RAW
    : ( '\r'? '\n' [ \t]*
        ( '//' ~[\r\n]*
        | '/*' .*? '*/' [ \t]*
        )?
      )+
    ;
