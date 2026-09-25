parser grammar YParser;

options { tokenVocab = YLexer; }

// ============================================================
// Lenguaje Y? (.y) - solo define estructuras y funciones (no hay
// variables globales). Sensible a mayusculas/minusculas y a la
// indentacion: NEWLINE/INDENT/DEDENT los sintetiza YLexerBase a
// partir de la indentacion real del archivo fuente.
//
// Distincion clave: una sentenciaSimple consume su propio NEWLINE
// final; una sentenciaCompuesta (si/elegir/ciclos/estructura anidada)
// NO, porque ya termina en el DEDENT de su propio cuerpo indentado, y
// ese DEDENT ya "contiene" el salto de linea que la cierra. Mezclar
// estos dos casos es la fuente mas comun de errores en gramaticas con
// indentacion: cuidado si se modifica esta parte.
// ============================================================

programa
    : seccionEstructuras? seccionFunciones EOF
    ;

seccionEstructuras
    : '%estructuras' NEWLINE definicionEstructura+
    ;

seccionFunciones
    : '%funciones' NEWLINE definicionFuncion+
    ;

definicionEstructura
    : 'estructura' IDENTIFICADOR ':' NEWLINE cuerpoCampos
    ;

cuerpoCampos
    : INDENT campoEstructura+ DEDENT
    ;

// Los campos de una estructura solo admiten un nivel de arreglo,
// segun el spec ("entero miArray[10]"); no se ve el caso multi-dim
// dentro de una estructura.
campoEstructura
    : tipo IDENTIFICADOR ('[' expresion ']')? NEWLINE
    ;

definicionFuncion
    : 'definir' IDENTIFICADOR '(' listaParametros? ')' ('->' tipo)? ':' NEWLINE cuerpo
    ;

listaParametros
    : parametro (',' parametro)*
    ;

// [] tipo nombre   -> parametro arreglo, pasado por referencia
// {} tipo nombre   -> parametro estructura, pasado por referencia
parametro
    : '[' ']' tipo IDENTIFICADOR      # parametroArreglo
    | '{' '}' tipo IDENTIFICADOR      # parametroEstructura
    | tipo IDENTIFICADOR              # parametroSimple
    ;

// Un tipo es primitivo o el nombre de una estructura definida en
// %estructuras (no se valida aqui si existe; eso es trabajo del
// analizador semantico, no del parser).
tipo
    : tipoPrimitivo
    | IDENTIFICADOR
    ;

tipoPrimitivo
    : 'entero'
    | 'cadena'
    | 'flotante'
    | 'caracter'
    | 'bool'
    ;

// ---- Cuerpos de bloques (funciones, si, elegir, ciclos) ----

cuerpo
    : INDENT sentencia+ DEDENT
    ;

sentencia
    : sentenciaSimple NEWLINE
    | sentenciaCompuesta
    ;

sentenciaSimple
    : declaracionVariable
    | asignacion
    | incrementoDecremento
    | 'romper'
    | 'continuar'
    | 'retornar' expresion?
    | llamada
    ;

sentenciaCompuesta
    : definicionEstructura
    | condicional
    | seleccion
    | cicloPara
    | cicloMientras
    | cicloHacer
    ;

declaracionVariable
    : tipo IDENTIFICADOR ('[' expresion ']')* ('=' inicializador)?
    ;

inicializador
    : expresion
    | '{' listaExpresiones? '}'
    ;

listaExpresiones
    : expresion (',' expresion)*
    ;

asignacion
    : acceso '=' expresion
    ;

// acceso cubre: variable simple, campo.de.estructura, arreglo[i], y
// llamada a funcion como parte de una cadena de acceso.
acceso
    : IDENTIFICADOR sufijoAcceso*
    ;

sufijoAcceso
    : '.' IDENTIFICADOR
    | '[' expresion ']'
    | '(' listaArgumentos? ')'
    ;

listaArgumentos
    : expresion (',' expresion)*
    ;

// Llamada usada como sentencia independiente, p.ej. imprimir("...")
// o leer(). imprimir/leer NO son palabras reservadas: siguen la
// misma sintaxis de llamada que cualquier funcion definida por el
// usuario, asi que el parser las trata igual; la validacion de que
// son funciones del sistema queda para el analizador semantico.
llamada
    : acceso
    ;

condicional
    : 'si' '(' expresion ')' 'entonces' NEWLINE cuerpo
      ( 'sino' '(' expresion ')' 'entonces' NEWLINE cuerpo )*
      ( 'contrario' NEWLINE cuerpo )?
    ;

seleccion
    : 'elegir' '(' expresion ')' ':' NEWLINE INDENT casoSeleccion+ DEDENT
    ;

casoSeleccion
    : 'caso' literal ':' NEWLINE cuerpo
    | 'siempre' ':' NEWLINE cuerpo
    ;

cicloPara
    : 'para' '(' forInit? ';' expresion? ';' forActualizacion? ')' ':' NEWLINE cuerpo
    ;

forInit
    : declaracionVariable
    | asignacion
    ;

forActualizacion
    : asignacion
    | incrementoDecremento
    ;

// ++ / -- : el spec los usa tanto en el encabezado de un 'para' (i++)
// como sueltos dentro del cuerpo de un ciclo (ej. "contador++",
// "intentos++"), asi que se permiten en ambos lugares.
incrementoDecremento
    : acceso ('++' | '--')
    ;

// El spec NO lleva ':' despues de 'hacer' aqui (a diferencia de
// 'hacer:' del ciclo do-while, que si lleva colon) - confirmado
// contra el documento original.
cicloMientras
    : 'mientras' '(' expresion ')' 'hacer' NEWLINE cuerpo
    ;

// Caso especial entre las sentencias compuestas: a diferencia de
// si/elegir/para/mientras (que terminan exactamente en el DEDENT de
// su cuerpo), cicloHacer sigue con la clausula 'mientras(expr)'
// DESPUES de ese DEDENT, asi que -a diferencia de las demas- si
// necesita consumir su propio NEWLINE final (si no, el DEDENT que le
// sigue en el flujo de tokens no lo consume nadie).
cicloHacer
    : 'hacer' ':' NEWLINE cuerpo 'mientras' '(' expresion ')' NEWLINE
    ;

// ---- Expresiones ----
// Orden de mayor a menor precedencia (recursion izquierda de ANTLR4:
// la alternativa listada primero liga mas fuerte). Solo se incluyen
// los operadores que el spec de Y? lista explicitamente: no hay <=,
// >=, %, ni asignaciones compuestas para este lenguaje en particular.
expresion
    : ('!'|'-') expresion                          # expUnaria
    | expresion op=('*'|'/') expresion              # expMultiplicativa
    | expresion op=('+'|'-') expresion               # expAditiva
    | expresion op=('<'|'>') expresion                # expRelacional
    | expresion op=('=='|'!=') expresion               # expIgualdad
    | expresion '&&' expresion                          # expAnd
    | expresion '||' expresion                           # expOr
    | '(' expresion ')'                                   # expParentesis
    | acceso                                              # expAcceso
    | literal                                             # expLiteral
    ;

literal
    : ENTERO
    | DECIMAL
    | CADENA
    | CARACTER
    | 'verdadero'
    | 'falso'
    ;
