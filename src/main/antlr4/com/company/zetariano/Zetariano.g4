grammar Zetariano;

// ============================================================
// Lenguaje Zetariano (.z) - orientado a objetos, estilo Java.
// Un archivo = una clase publica, y el archivo debe llamarse igual
// que la clase (eso lo valida el analizador semantico / la GUI al
// guardar, no el parser). No hay encapsulamiento, herencia ni
// polimorfismo en este proyecto (queda para la Parte 2): todos los
// atributos son publicos y no hay palabras clave de visibilidad mas
// alla de 'public' en constructores y metodos.
// ============================================================

// -------------------- PARSER --------------------

programa
    : 'public' 'class' IDENTIFICADOR '{' miembro* '}' EOF
    ;

miembro
    : declaracionCampo
    | constructor
    | metodo
    ;

// El spec original mostraba campos SIN modificador (siempre publicos,
// "el encapsulamiento se vera en la Parte 2"), pero un proyecto de
// referencia real usa 'private' en los campos, asi que el modificador
// es OPCIONAL: acepta ambos estilos. Que un campo empiece con
// 'public'/'private' no choca con constructor/metodo (que tambien
// empiezan con 'public'): ANTLR distingue mirando mas adelante si
// despues del nombre viene '(' (constructor/metodo) o ';'/'=' (campo).
declaracionCampo
    : modificadorVisibilidad? tipo IDENTIFICADOR ('=' inicializador)? ';'
    ;

modificadorVisibilidad
    : 'public'
    | 'private'
    ;

// Un constructor se reconoce porque, a diferencia de un metodo, no
// hay ningun tipo de retorno entre 'public' y el nombre: el nombre
// del constructor debe coincidir con el de la clase (eso tambien se
// valida en el analizador semantico, no aqui). Puede haber varios
// (sobrecarga de constructor).
constructor
    : 'public' IDENTIFICADOR '(' listaParametros? ')' bloque
    ;

// Metodos: publicos, con tipo de retorno explicito ('void' si no
// retornan nada). Tambien se pueden sobrecargar.
metodo
    : 'public' tipoRetorno IDENTIFICADOR '(' listaParametros? ')' bloque
    ;

tipoRetorno
    : 'void'
    | tipo
    ;

listaParametros
    : parametro (',' parametro)*
    ;

parametro
    : tipo IDENTIFICADOR
    ;

// entero[], entero[][], etc. - los corchetes van pegados al tipo, no
// al nombre de la variable (asi aparece en todos los ejemplos).
tipo
    : tipoBase ('[' ']')*
    ;

tipoBase
    : 'int'
    | 'double'
    | 'char'
    | 'boolean'
    | 'String'
    | IDENTIFICADOR   // tipo de otra clase (objeto), definida en otro .z
    ;

// ---- Cuerpos de bloques ----

bloque
    : '{' sentencia* '}'
    ;

// Confirmado en el spec: si el cuerpo de un if/else/for/while/do es
// una sola instruccion, las llaves son opcionales (igual que en Java).
cuerpo
    : bloque
    | sentencia
    ;

// ---- Sentencias ----

sentencia
    : declaracionVariable ';'
    | asignacion ';'
    | incrementoDecremento ';'
    | condicional
    | seleccion
    | cicloPara
    | cicloMientras
    | cicloHacer
    | 'break' ';'
    | 'continue' ';'
    | 'return' expresion? ';'
    | llamada ';'
    | bloque
    ;

declaracionVariable
    : tipo IDENTIFICADOR ('=' inicializador)?
    ;

inicializador
    : expresion
    | '{' listaExpresiones? '}'
    ;

listaExpresiones
    : expresion (',' expresion)*
    ;

asignacion
    : acceso opAsignacion expresion
    ;

// El spec solo muestra +=, -=, *= (tabla "De asignación"); no hay
// ejemplo de /= ni %=, asi que se dejan fuera por ahora.
opAsignacion
    : '=' | '+=' | '-=' | '*='
    ;

incrementoDecremento
    : acceso ('++' | '--')
    ;

// acceso cubre: variable simple, campo.de.objeto, arreglo[i], y
// cadenas de llamada como objeto.metodo() o System.out.println(...)
// (ambos estilos aparecen en el spec; los dos son solo una cadena de
// accesos con '.', asi que ninguno necesita regla especial). 'this'
// no aparecia en el spec original pero SI se usa en la practica
// (this.campo = ...), asi que se agrega como punto de partida valido,
// igual que un IDENTIFICADOR.
acceso
    : (IDENTIFICADOR | 'this') sufijoAcceso*
    ;

sufijoAcceso
    : '.' IDENTIFICADOR
    | '[' expresion ']'
    | '(' listaArgumentos? ')'
    ;

listaArgumentos
    : expresion (',' expresion)*
    ;

llamada
    : acceso
    ;

condicional
    : 'if' '(' expresion ')' cuerpo
      ( 'else' 'if' '(' expresion ')' cuerpo )*
      ( 'else' cuerpo )?
    ;

// El fallthrough (un caso sin 'break' cae al siguiente) es valido a
// proposito: 'break' ya es una sentencia normal, opcional, dentro de
// sentencia*, no hace falta modelarlo aparte.
seleccion
    : 'switch' '(' expresion ')' '{' casoSwitch+ '}'
    ;

casoSwitch
    : 'case' literal ':' sentencia*
    | 'default' ':' sentencia*
    ;

// Las 3 clausulas del for son opcionales (for ( ; ; ) es valido).
cicloPara
    : 'for' '(' forInit? ';' expresion? ';' forActualizacion? ')' cuerpo
    ;

forInit
    : declaracionVariable
    | asignacion
    ;

forActualizacion
    : asignacion
    | incrementoDecremento
    ;

cicloMientras
    : 'while' '(' expresion ')' cuerpo
    ;

// A diferencia de while/for, el do-while SIEMPRE lleva llaves en los
// ejemplos y termina en ';' despues del while(...).
cicloHacer
    : 'do' bloque 'while' '(' expresion ')' ';'
    ;

// ---- Expresiones ----
// Orden de mayor a menor precedencia (recursion izquierda de ANTLR4:
// la alternativa listada primero liga mas fuerte), calcado del orden
// real de Java ya que el spec sigue ese estilo explicitamente.
expresion
    : ('!'|'-') expresion                                          # expUnaria
    | expresion op=('*'|'/'|'%') expresion                         # expMultiplicativa
    | expresion op=('+'|'-') expresion                              # expAditiva
    | expresion op=('<'|'>'|'<='|'>=') expresion                     # expRelacional
    | expresion op=('=='|'!=') expresion                             # expIgualdad
    | expresion '&&' expresion                                       # expAnd
    | expresion '||' expresion                                        # expOr
    | expresion '?' expresion ':' expresion                           # expTernaria
    | 'new' IDENTIFICADOR '(' listaArgumentos? ')'                     # expNuevoObjeto
    | 'new' tipoBase ('[' expresion ']')+                               # expNuevoArreglo
    | '(' expresion ')'                                                  # expParentesis
    | acceso                                                             # expAcceso
    | literal                                                            # expLiteral
    ;

literal
    : ENTERO
    | DECIMAL
    | CADENA
    | CARACTER
    | 'true'
    | 'false'
    | 'null'
    ;

// -------------------- LEXER --------------------

// Palabras reservadas (deben ir ANTES que IDENTIFICADOR)
PUBLIC      : 'public';
PRIVATE     : 'private';
CLASS       : 'class';
VOID        : 'void';
NEW         : 'new';
IF          : 'if';
ELSE        : 'else';
SWITCH      : 'switch';
CASE        : 'case';
DEFAULT     : 'default';
FOR         : 'for';
WHILE       : 'while';
DO          : 'do';
BREAK       : 'break';
CONTINUE    : 'continue';
RETURN      : 'return';
TRUE        : 'true';
FALSE       : 'false';
NULL        : 'null';
THIS        : 'this';

INT_T       : 'int';
DOUBLE_T    : 'double';
CHAR_T      : 'char';
BOOLEAN_T   : 'boolean';
STRING_T    : 'String';

// Operadores y simbolos
OP_INC          : '++';
OP_DEC          : '--';
OP_AND          : '&&';
OP_OR           : '||';
OP_IGUAL        : '==';
OP_DISTINTO     : '!=';
OP_MENOR_IGUAL  : '<=';
OP_MAYOR_IGUAL  : '>=';
OP_MAS_ASIGNA   : '+=';
OP_MENOS_ASIGNA : '-=';
OP_MULT_ASIGNA  : '*=';
OP_MENOR        : '<';
OP_MAYOR        : '>';
OP_NOT          : '!';
OP_MAS          : '+';
OP_MENOS        : '-';
OP_MULT         : '*';
OP_DIV          : '/';
OP_MOD          : '%';
OP_ASIGNA       : '=';
INTERROGACION   : '?';
PUNTO_COMA      : ';';
DOS_PUNTOS      : ':';
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

COMENTARIO_LINEA
    : '//' ~[\r\n]* -> skip
    ;

COMENTARIO_BLOQUE
    : '/*' .*? '*/' -> skip
    ;

ESPACIO
    : [ \t\r\n]+ -> skip
    ;
