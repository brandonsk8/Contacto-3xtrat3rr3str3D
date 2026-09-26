grammar PigLatin;

// ============================================================
// Lenguaje Pig Latin (.pig) - es el punto de entrada del programa.
// Importa estructuras/funciones de archivos .y y clases de archivos .z.
// Es case sensitive: 'finis' (minuscula, cierra si/dum) y 'FINIS'
// (mayuscula, cierra la seccion MAIOR>) son tokens DISTINTOS.
// ============================================================

// -------------------- PARSER --------------------

programa
    : listaImportaciones? seccionVariables? seccionPrincipal EOF
    ;

listaImportaciones
    : importacion+
    ;

// El spec no muestra ';' despues de un import, a diferencia del
// resto del lenguaje.
importacion
    : 'import' ruta
    ;

ruta
    : IDENTIFICADOR ('.' IDENTIFICADOR)*
    ;

seccionVariables
    : 'VARIABILES>' declaracion*
    ;

seccionPrincipal
    : 'MAIOR>' sentencia* 'FINIS' ';'
    ;

// ---- Declaraciones ----

declaracion
    : 'esto' IDENTIFICADOR ':' inicializador ';'                                  # declaracionSimple
    | 'series' IDENTIFICADOR '[' expresion ']' ':' tipoDato valoresArreglo? ';'    # declaracionArreglo
    ;

inicializador
    : tipoPrimitivo expresion              # inicializadorPrimitivo
    | ( 'verum' | 'falsus' )               # inicializadorBooleano
    | IDENTIFICADOR valoresEstructura      # inicializadorEstructura
    | 'novus' IDENTIFICADOR '(' listaArgumentos? ')'  # inicializadorObjeto
    ;

tipoPrimitivo
    : 'numerus'
    | 'textum'
    | 'decimalis'
    | 'littera'
    ;

// Tipo de un arreglo: primitivo o un tipo definido en .y/.z (estructura u objeto)
tipoDato
    : tipoPrimitivo
    | IDENTIFICADOR
    ;

valoresArreglo
    : '{' listaExpresiones? '}'
    ;

// Inicializador de estructura, admite anidar otra estructura como
// argumento: Persona { "Valeria", 25, {"Avenida Central", 500} }
valoresEstructura
    : '{' listaValoresEstructura? '}'
    ;

listaValoresEstructura
    : valorEstructura (',' valorEstructura)*
    ;

valorEstructura
    : expresion
    | valoresEstructura
    ;

listaExpresiones
    : expresion (',' expresion)*
    ;

listaArgumentos
    : expresion (',' expresion)*
    ;

// ---- Sentencias ----

sentencia
    : declaracion
    | asignacion
    | condicional
    | cicloMientras
    | cicloHacer
    | cicloPara
    | 'interrumpe' ';'
    | 'perge' ';'
    | imprimir
    | leer
    | incrementoDecremento ';'
    | llamadaSentencia
    ;

asignacion
    : acceso '=' expresion ';'
    ;

// acceso cubre: variable simple, campo.de.estructura, arreglo[i],
// y llamada a funcion/metodo como parte de una cadena de acceso
// (ej. mis_enteros[9].hablar(miObjeto.getNombre()))
acceso
    : IDENTIFICADOR sufijoAcceso*
    ;

sufijoAcceso
    : '.' IDENTIFICADOR
    | '[' expresion ']'
    | '(' listaArgumentos? ')'
    ;

// NOTA DE DISEÑO: cada rama usa 'cuerpo' (en vez de '{' sentencia* '}'
// escrito directo aqui) a proposito. Si las 3 apariciones de
// '{' sentencia* '}' fueran inline, ANTLR junta TODAS las 'sentencia'
// de las 3 ramas (si/aliter-si/aliter-else) en un solo
// List<SentenciaContext> sin ninguna marca de donde termina un bloque y
// empieza el siguiente - no hay forma de separarlas de vuelta en el
// Visitor. Envolver cada bloque en su propia regla 'cuerpo' evita eso:
// ANTLR da un List<CuerpoContext>, uno por rama, en orden, y cada
// CuerpoContext trae SU PROPIA lista de sentencias ya separada (mismo
// patron que 'cuerpo' en Y?/Zetariano).
condicional
    : 'si' '(' expresion ')' cuerpo
      ( 'aliter' '(' expresion ')' cuerpo )*
      ( 'aliter' cuerpo )?
      'finis' ';'
    ;

cuerpo
    : '{' sentencia* '}'
    ;

cicloMientras
    : 'dum' '(' expresion ')' cuerpo 'finis' ';'
    ;

cicloHacer
    : 'facere' cuerpo 'dum' '(' expresion ')' ';'
    ;

// El 'finis' final es OPCIONAL (a diferencia de 'si'/'dum'): el propio
// enunciado muestra el ciclo 'per' cerrando solo con '}', sin 'finis' ni
// ';' extra - pero se acepta si algun archivo lo trae igual, por si acaso.
cicloPara
    : 'per' '(' forInit? ';' expresion? ';' forActualizacion? ')' cuerpo ( 'finis' ';' )?
    ;

// OJO: a diferencia de 'declaracion' (usada en VARIABILES>), estas 2
// alternativas de declaracion NO llevan su propio ';' - el ';' que
// separa las 3 clausulas de 'per' ya lo pone cicloPara arriba, y si esta
// regla tambien trajera uno se estaria pidiendo un doble ';' pegado
// (esto i : numerus 0;; i < 10; ...), que ningun archivo real escribe.
forInit
    : 'esto' IDENTIFICADOR ':' inicializador                                 # forInitDeclaracionSimple
    | 'series' IDENTIFICADOR '[' expresion ']' ':' tipoDato valoresArreglo?  # forInitDeclaracionArreglo
    | asignacionSinFin                                                       # forInitAsignacion
    ;

forActualizacion
    : asignacionSinFin
    | incrementoDecremento
    ;

asignacionSinFin
    : acceso '=' expresion
    ;

incrementoDecremento
    : acceso ('++' | '--')
    ;

// >> "texto" ; o >> var1 >> var2 ;  (se pueden imprimir varias seguidas)
// El ';' final es OPCIONAL: en ejemplos reales aparece a veces y a
// veces no (confirmado contra un proyecto de referencia real, no solo
// el spec) - lo mas comun es omitirlo justo antes de un '}' de cierre.
// Cuidado: si dos '>>' seguidos NO llevan ';' entre ellos, el parser
// los junta en un solo nodo imprimir con varias expresiones (en vez
// de dos sentencias separadas) - mismo resultado en tokens consumidos,
// pero hay que tenerlo en cuenta al escribir el Visitor mas adelante.
imprimir
    : '>>' expresion ('>>' expresion)* ';'?
    ;

// << ;            -> lee y descarta
// variable << ;   -> lee y guarda en variable
// El ';' final tambien es opcional aqui (el spec original y los
// ejemplos reales de uso NUNCA lo llevan despues de '<<', asi que en
// la practica casi nunca vas a verlo, pero se permite por si acaso).
leer
    : acceso? '<<' ';'?
    ;

llamadaSentencia
    : acceso ';'
    ;

// ---- Expresiones ----
// En una regla ANTLR4 con recursion izquierda, el orden de las
// alternativas define la precedencia: la primera listada liga MAS
// fuerte. Orden aqui (de mas a menos fuerte): unario, * / %, + -,
// relacionales, igualdad, &&, ||. Los casos base (parentesis, acceso,
// literal) no recursan por la izquierda asi que van al final.
expresion
    : ('!'|'-') expresion                                  # expUnaria
    | expresion op=('*'|'/'|'%') expresion                 # expMultiplicativa
    | expresion op=('+'|'-') expresion                     # expAditiva
    | expresion op=('<'|'>'|'<='|'>=') expresion            # expRelacional
    | expresion op=('=='|'!=') expresion                    # expIgualdad
    | expresion '&&' expresion                              # expAnd
    | expresion '||' expresion                               # expOr
    | '(' expresion ')'                                      # expParentesis
    | acceso                                                 # expAcceso
    | literal                                                # expLiteral
    ;

literal
    : ENTERO
    | DECIMAL
    | CADENA
    | CARACTER
    | 'verum'
    | 'falsus'
    ;

// -------------------- LEXER --------------------

// Palabras reservadas (deben ir ANTES que IDENTIFICADOR para que ANTLR
// las priorice sobre el identificador generico)
IMPORT          : 'import';
ESTO            : 'esto';
SERIES          : 'series';
SI              : 'si';
ALITER          : 'aliter';
DUM             : 'dum';
FACERE          : 'facere';
PER             : 'per';
PERGE           : 'perge';
INTERRUMPE      : 'interrumpe';
NOVUS           : 'novus';
NUMERUS         : 'numerus';
TEXTUM          : 'textum';
DECIMALIS       : 'decimalis';
LITTERA         : 'littera';
VERUM           : 'verum';
FALSUS          : 'falsus';
FINIS_BLOQUE    : 'finis';
FINIS_PROGRAMA  : 'FINIS';
MARCADOR_VARS   : 'VARIABILES>';
MARCADOR_MAIN   : 'MAIOR>';

// Operadores y simbolos
OP_INC          : '++';
OP_DEC          : '--';
OP_AND          : '&&';
OP_OR           : '||';
OP_IGUAL        : '==';
OP_DISTINTO     : '!=';
OP_MENOR_IGUAL  : '<=';
OP_MAYOR_IGUAL  : '>=';
IMPRIMIR        : '>>';
LEER            : '<<';
OP_MENOR        : '<';
OP_MAYOR        : '>';
OP_NOT          : '!';
OP_MAS          : '+';
OP_MENOS        : '-';
OP_MULT         : '*';
OP_DIV          : '/';
OP_MOD          : '%';
OP_ASIGNA       : '=';
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
    : '"' ( '\\' . | ~["\\] )* '"'
    ;

CARACTER
    : '\'' ( '\\' . | ~['\\] ) '\''
    ;

// Comentarios: delimitados por ## en ambos extremos, incluye los
// encabezados de seccion tipo "##  Importaciones ... ##"
COMENTARIO
    : '##' .*? '##' -> skip
    ;

ESPACIO
    : [ \t\r\n]+ -> skip
    ;
