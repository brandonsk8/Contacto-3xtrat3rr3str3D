# Contacto 3xtrat3rr3str3D — Proyecto 1 (Compiladores 2, CUNOC)

Compilador que valida 3 lenguajes (Y?, Zetariano, Pig Latin), genera
codigo de tres direcciones en forma de cuartetas, y las traduce a
bytecode ejecutable compilable en C.

## Cómo abrir esto en IntelliJ

1. `IntelliJ IDEA -> File -> Open...` y selecciona la carpeta
   `compilador2-proyecto1` (la que tiene el `pom.xml`).
2. IntelliJ detecta que es un proyecto Maven y te va a preguntar si
   quieres importarlo — dile que sí ("Load Maven Project" /
   "Trust project").
3. Instala el **plugin ANTLR4** de IntelliJ (Settings -> Plugins ->
   Marketplace -> "ANTLR v4") si no lo tienes ya. Esto te da
   resaltado y generación de parser en vivo mientras editas los `.g4`.
4. Corre `mvn clean compile` desde la terminal de IntelliJ (o el panel
   Maven a la derecha) para que se generen los lexers/parsers de los 3
   lenguajes en `target/generated-sources/antlr4`. IntelliJ los marca
   automáticamente como "Generated Sources Root".
5. Corre `Main.java` — si ves el mensaje de las 3 gramáticas
   compilando, el pipeline está listo.

## Estructura del proyecto

```
compilador2-proyecto1/
├── pom.xml
├── src/main/antlr4/com/company/
│   ├── y/
│   │   ├── YLexer.g4    # lexer de Y? (declara NEWLINE/INDENT/DEDENT)
│   │   └── YParser.g4    # parser de Y? (grammar name "YParser" -> genera YParser.java)
│   ├── zetariano/Zetariano.g4  # gramática de Zetariano (.z) — placeholder
│   └── pig/PigLatin.g4         # gramática de Pig Latin (.pig)
├── src/main/java/com/company/
│   ├── Main.java                # arranque temporal / smoke test
│   ├── y/
│   │   └── YLexerBase.java      # logica de indentacion (INDENT/DEDENT) de Y?
│   ├── zetariano/                # Listener/Visitor de Zetariano (por escribir)
│   ├── pig/                      # Listener/Visitor de Pig Latin (por escribir)
│   ├── ir/
│   │   ├── Cuarteta.java         # modelo de una cuarteta C3D
│   │   └── TablaCuartetas.java   # contenedor + generador de temporales/etiquetas
│   ├── codegen/                  # cuartetas -> bytecode/C (por escribir)
│   └── gui/                      # editor Swing, árbol de trabajo (por escribir)
└── docs/                         # manual técnico, manual de usuario, diagramas
```

## Por qué esta estructura

- **Un solo módulo Maven**, no uno por lenguaje: el
  `antlr4-maven-plugin` genera un lexer/parser distinto por cada `.g4`,
  y como cada gramática vive en su propia carpeta (`y/`, `zetariano/`,
  `pig/`), cada una cae en su propio paquete Java
  (`com.company.y`, `com.company.zetariano`,
  `com.company.pig`) sin chocar entre sí.
- **`ir/` es el punto de encuentro de los 3 lenguajes**: cada
  Listener/Visitor termina escribiendo `Cuarteta`s a través de
  `TablaCuartetas`. El generador de C (`codegen/`) solo necesita
  conocer `ir/`, nunca los AST de los 3 lenguajes por separado.
- **`gui/` separado**: la interfaz Swing consume los resultados
  (errores, AST, cuartetas) pero no conoce las gramáticas
  directamente.

## Y? — el lenguaje con indentación significativa

A diferencia de Zetariano y Pig Latin, Y? no usa llaves para delimitar
bloques: usa la indentación, igual que Python. ANTLR4 **no soporta
esto de forma nativa** — una gramática declarativa no puede "contar
espacios". La solución (la misma técnica que usa la gramática oficial
de Python3 en `antlr/grammars-v4`) son dos piezas separadas:

- **`YLexer.g4`** (gramática de lexer pura) declara `NEWLINE`, `INDENT`
  y `DEDENT` como tokens sintéticos (`tokens { NEWLINE, INDENT, DEDENT }`,
  sin regla que los reconozca directo) y una regla `NEWLINE_RAW` que
  captura cada salto de línea junto con la indentación de la siguiente
  línea con contenido real.
- **`YLexerBase.java`** es la superclase de `YLexer` (`options {
  superClass = YLexerBase; }`). Sobreescribe `nextToken()`: cada vez
  que ve un `NEWLINE_RAW`, mide la indentación, la compara contra una
  pila de niveles vistos, y sintetiza `NEWLINE` + `INDENT` (si subió) o
  `NEWLINE` + uno o más `DEDENT` (si bajó) antes de seguir. El parser
  (`YParser.g4`) nunca ve `NEWLINE_RAW`, solo ve `NEWLINE`/`INDENT`/`DEDENT`
  como si fueran tokens normales.
- Un tab cuenta como avance a la siguiente columna múltiplo de 8 (la
  misma convención del lexer de referencia de Python), por si se
  mezclan tabs y espacios.
- Dentro de `()`, `[]` o `{}` un salto de línea no cuenta como
  separador de sentencias (se lleva la cuenta con
  `profundidadParentesis`), igual que en Python.

**No pude compilar/probar esta gramática dentro de este entorno**
(no tengo acceso a Maven Central para bajar el jar de ANTLR4 aquí), así
que la revisé a mano con cuidado pero un lexer de indentación es
notoriamente delicado de ajustar. Corre `mvn clean compile` y después
prueba el parser con un archivo `.y` real (o con el ANTLR TestRig del
plugin de IntelliJ) cuanto antes — si algo no cuadra, pégame el error
exacto y lo ajusto.

### Decisiones de diseño tomadas para Y? (revísalas)

`extract-text` (la primera pasada que usé para leer el docx) aplana el
contenido de las celdas de tabla y pierde los saltos de línea reales
— justo la información que más importa en un lenguaje con indentación
significativa. Para diseñar esta gramática releí el XML crudo del
`.docx` (que sí conserva los `<w:br/>` y los espacios de indentación
de cada línea de código), así que estos puntos ya están verificados
contra el documento real, no adivinados:

1. `imprimir(...)` y `leer()` **no son palabras reservadas**: siguen
   la misma sintaxis de llamada a función que cualquier función del
   usuario. La validación de que son funciones del sistema queda para
   el analizador semántico, no el parser.
2. `&&` / `||` se interpretan como AND / OR — la tabla del spec dice
   "And y Not" en esa fila, que leo como un error de tipeo (`!` ya
   tiene su propia fila como "Negación").
3. **No** incluí `<=`, `>=`, `%` (módulo) ni asignaciones compuestas
   (`+=`, etc.): la tabla de operadores de Y? solo lista `==, !=, <, >`
   y `+, -, *, /`. Si tu catedrático espera más operadores, decime y
   los agrego.
4. `mientras(cond) hacer` **no lleva `:`** al final (a diferencia de
   `hacer:` del ciclo do-while, que sí lleva) — confirmado línea por
   línea contra el docx.
5. `++` / `--` se permiten tanto en el encabezado de un `para(...)`
   como sueltos dentro del cuerpo de un ciclo (`contador++`,
   `intentos++` aparecen así en los ejemplos reales).
6. El ejemplo de `para(...)` trae un par de `}` sueltos, y uno de los
   dos ejemplos de `mientras` trae `;` sueltos — **ambos están
   realmente en el documento**, no son un artefacto de mi lectura.
   Pero son inconsistentes con el resto del lenguaje (que en ningún
   otro lado usa `;` ni `{}`, y la sección de Y? dice explícitamente
   que la indentación es la que define los bloques), así que los trato
   como residuo de copiar/pegar de la sección de Zetariano y no los
   soporta la gramática. Si tu catedrático los quiere soportar
   literalmente, avisame.
7. Dato menor, no afecta la gramática: en el ejemplo de "Uso de
   estructuras" el documento declara `estructura Puntos:` (plural)
   pero luego la usa como `Punto p1 = {...}` (singular) — probablemente
   un typo del documento. El parser no valida nombres de tipo (eso es
   semántico), así que no rompe nada, pero probá no copiar ese
   ejemplo literal si escribís un `.y` de prueba.
8. Un comentario que ocupa su propia línea entre dos sentencias
   (misma indentación) debería funcionar bien — de hecho el ejemplo
   real de `%funciones` en el spec (con un comentario de bloque grande
   seguido de un comentario de línea, ambos antes del primer
   `definir`) es exactamente el caso que `NEWLINE_RAW` está diseñado
   para colapsar en un solo salto lógico. Aun así es el borde más
   frágil de este tipo de lexer — si ves errores de parseo cerca de un
   comentario, es el primer sospechoso.

## Zetariano — orientado a objetos, estilo Java

Mucho más simple que Y? porque usa llaves y `;` explícitos (nada de
indentación significativa), así que es una gramática combinada normal
(`Zetariano.g4`, lexer + parser en un solo archivo, como `PigLatin.g4`).

Igual que con Y?, releí el XML crudo del `.docx` antes de diseñarla
(no la versión aplanada) para confirmar detalles finos.

### Decisiones de diseño tomadas para Zetariano (revísalas)

1. **Un `if`/`while`/`for`/`do` sin llaves cuando el cuerpo es una
   sola instrucción está permitido** (confirmado explícitamente en el
   spec: "las llaves {} son opcionales" si el bloque es una sola
   línea) — igual que en Java real. Por eso existe la regla `cuerpo`
   separada de `bloque`.
2. `println(...)`, `print(...)`, `readln()` **no son palabras
   reservadas** (misma decisión que tomé para `imprimir`/`leer` en
   Y?): son llamadas normales. Algunos ejemplos del spec usan en su
   lugar `System.out.println(...)` — no hace falta ninguna regla
   especial para eso: mi regla `acceso` ya soporta cadenas arbitrarias
   `identificador.identificador.identificador(...)`, así que ambos
   estilos ya parsean igual, sin cambios. Cuál de los dos es "válido"
   para tu proyecto es una decisión semántica, no sintáctica.
3. Constructores vs. métodos se distinguen porque un constructor NO
   lleva tipo de retorno (`public NombreClase(...)`) y un método sí
   (`public tipo nombre(...)`) — ANTLR los distingue solo con la
   cantidad de tokens entre `public` y `(`, sin ambigüedad real.
4. Los atributos de la clase **nunca** llevan `public` (siempre son
   públicos implícitamente, según la nota del spec sobre
   encapsulamiento pendiente); si lo llevaran, chocarían con
   constructor/método en el parser.
5. Solo incluí `+=`, `-=`, `*=` como asignación compuesta — la tabla
   del spec no muestra `/=` ni `%=`.
6. El fallthrough de `switch` (un `case` sin `break` cae al
   siguiente) está soportado de forma natural: `break` es una
   sentencia normal y opcional dentro de cada `case`, no hay que
   modelarlo aparte.
7. Agregué el operador ternario (`? :`) y `new` tanto para objetos
   (`new Persona(...)`) como para arreglos con tamaño
   (`new int[5]`, `new int[3][3]`) — ambos confirmados en los
   ejemplos. Los arreglos también se pueden inicializar con una
   lista literal (`{"Carlos", "Ana", "Pedro"}`), igual que en Y?.
8. ~~No agregué `this`~~ **Corregido**: probando contra un proyecto de
   referencia real (`ejemplos/Pila/`, ver abajo) apareció `this.campo`
   por todos lados, así que ahora `acceso` acepta `this` igual que un
   `IDENTIFICADOR`.
9. Los campos de una clase **sí pueden llevar `public`/`private`**
   como modificador opcional — el mismo proyecto de referencia usa
   `private Nodo cima;`. La nota original del spec ("no hay
   encapsulamiento") sigue aplicando en el sentido de que no validamos
   el modificador semánticamente, pero sintácticamente ahora se acepta
   con o sin modificador.

## Archivos de ejemplo reales (`ejemplos/`)

`ejemplos/Pila/` es un proyecto de referencia real que me pasaron (una
pila enlazada: `main.pig` importa `Nodo.z`, `Pila.z` y `utils/utils.y`)
— lo usé para encontrar bugs que los ejemplos sueltos del spec no
revelaban. `Main.java` ahora parsea estos 4 archivos en cada corrida
(además de los 3 casos mínimos) y reporta `OK`/`FAIL` por archivo, así
que sirven como prueba de regresión: si volvés a tocar una gramática,
correr `Main` te avisa si rompiste algo.

Bugs reales que salieron de probar contra estos archivos (ya
corregidos):

- **Pig Latin**: el `;` final de `>>` (imprimir) y `<<` (leer) es
  **opcional**, no obligatorio — el archivo real los omite
  constantemente (sobre todo justo antes de un `}` de cierre), y el
  propio spec original tampoco lo lleva después de `<<` en su único
  ejemplo. Ojo: si dos `>>` quedan pegados sin `;` entre ellos, el
  parser los junta en un solo nodo `imprimir` con varias expresiones,
  en vez de dos sentencias separadas — mismo resultado en tokens
  consumidos, pero hay que tenerlo en cuenta al escribir el Visitor.
- **Zetariano**: `this` y modificadores `public`/`private` en campos
  (los dos puntos 8 y 9 de arriba).
- **`main.pig` (el archivo, no la gramática)**: le faltaba el `FINIS;`
  final que cierra la sección `MAIOR>` — el archivo tal como lo
  recibí termina justo después del `} finis;` del `dum(...)` de afuera,
  sin ningún `FINIS;` en mayúscula en ninguna parte (confirmado con
  `grep -n "FINIS"`, cero resultados antes de la corrección). No era
  un bug de `PigLatin.g4`: la regla `seccionPrincipal: 'MAIOR>'
  sentencia* 'FINIS' ';';` es correcta y coincide con el único ejemplo
  del spec original, que sí cierra con `FINIS;`. Se corrigió agregando
  esa línea al final de `ejemplos/Pila/main.pig`.

## Tabla de símbolos (`com.company.semantico`)

Modelo compartido por los 3 lenguajes (necesario porque Pig Latin
importa estructuras `.y` y clases `.z`, así que sus tipos tienen que
poder representar "esto es una estructura/clase definida en otro
archivo", no solo primitivos):

- **`Tipo`** — tipo semántico unificado: primitivos (`ENTERO`,
  `DECIMAL`, `CADENA`, `CARACTER`, `BOOLEANO`, `VOID`), `ESTRUCTURA`/
  `CLASE` (por nombre) y `ARREGLO` (tipo base + dimensiones). Cada
  Visitor traduce las palabras propias de su lenguaje (`int`/`numerus`/
  `entero`, etc.) a este vocabulario único. Incluye `esCompatibleCon`
  (entero → decimal se acepta por promoción implícita, el resto exige
  coincidencia exacta).
- **`CategoriaSimbolo`** — rol del símbolo dentro de su ámbito
  (`VARIABLE`, `PARAMETRO`, `CAMPO`, `FUNCION`, `METODO`,
  `CONSTRUCTOR`, `ESTRUCTURA`, `CLASE`).
- **`Simbolo`** — nombre + tipo + categoría + línea/columna. Se usa
  directamente para variables, parámetros y campos.
- **`SimboloInvocable`** (extiende `Simbolo`) — para funciones de Y?,
  métodos y constructores de Zetariano: lista de parámetros + tipo de
  retorno + `mismaFirma(...)` para detectar sobrecargas duplicadas.
  `ambitoLocal` queda en `null` hasta que el Visitor recorra el cuerpo.
- **`SimboloEstructura`** (extiende `Simbolo`) — campos de una
  `estructura` de Y? (solo datos, sin métodos).
- **`SimboloClase`** (extiende `Simbolo`) — campos + métodos
  (con sobrecarga, varios por nombre) + constructores de una clase
  Zetariano.
- **`TablaSimbolos`** — un ámbito (scope) encadenado a su padre;
  `declarar` rechaza redeclaración en el mismo ámbito (permite
  shadowing entre ámbitos anidados), `buscar` sube por la cadena de
  padres.
- **`TablaSimbolosGlobal`** — catálogo de todo lo definido a nivel de
  archivo (estructuras, clases, funciones sueltas) entre todos los
  `.y`/`.z` que un `.pig` importa; estructuras y clases comparten un
  solo espacio de nombres de tipos.

`Main.java` tiene una prueba de humo (`verificarTablaSimbolos`) armada
a mano sobre el mismo caso de la pila enlazada (estructura `Nodo`
auto-referenciada, clase `Pila` con un campo `Nodo` y dos sobrecargas
de `apilar`, y un ámbito anidado con shadowing) que confirma que
declarar/buscar/sobrecarga/compatibilidad de tipos funcionan antes de
conectarlo a ningún Visitor.

## Visitor de Y? (`com.company.y`)

Primer Visitor completo (semántico + generación de cuartetas en la misma
pasada, ver la discusión de diseño más abajo):

- **`RegistradorFirmasY`** (pasada 1, no extiende el Visitor de ANTLR:
  solo toca los 2 lugares fijos de nivel superior) — registra
  estructuras (en dos pasos: primero los nombres vacíos, después los
  campos, para permitir auto-referencia como `Nodo.siguiente : Nodo`
  sin importar el orden de declaración) y funciones con su firma
  completa en `TablaSimbolosGlobal`. Y? no permite sobrecarga: una
  función repetida por nombre se rechaza.
- **`YVisitor`** (pasada 2, extiende `YParserBaseVisitor<Operando>`) —
  recorre el cuerpo de cada función ya registrada. Cubre: declaración/
  asignación/incremento-decremento, `retornar` (validado contra el
  tipo de retorno de la función), `si/sino/contrario`, `mientras`,
  `hacer...mientras`, `para`, `elegir/caso/siempre` (con fallthrough
  intencional), `romper`/`continuar` (con una pila de ciclos que sabe a
  qué etiqueta saltar en cada caso, incluyendo que `continuar` en un
  `para` deba pasar por la actualización antes de repetir), todas las
  expresiones con su precedencia, y acceso a variables/campos de
  estructura/índices de arreglo/llamadas a función — incluyendo
  `imprimir`/`leer` como funciones del sistema (no están en
  `%funciones`, se resuelven como casos especiales antes de buscar en
  la tabla global).
- **Diseño de una sola pasada para cuerpos:** análisis semántico y
  generación de cuartetas van en el mismo recorrido del árbol (no en
  dos Visitors separados), para no duplicar la lógica de "cómo bajo a
  los hijos de esta regla". Cada método `visitX` se mantiene corto y
  delega en helpers privados (`resolverAcceso`, `operacionAritmetica`,
  `exigirBooleano`, etc.); nada de estado disperso, todo vive en 3
  lugares fijos: `ambitoActual` (`TablaSimbolos`), `cuartetas`
  (`TablaCuartetas`) y `errores` (`GestorErrores`).
- **`Operando`** (`com.company.semantico`) — lo que devuelve visitar
  una expresión: su `Tipo` + el texto para usar en una `Cuarteta`
  (variable, temporal, literal). Distingue `error()` (ya se reportó un
  problema, no propagar en cascada) de `sinValor()` (caso normal sin
  valor usable, ej. llamar a una función `void`).
- **`GestorErrores`/`ErrorSemantico`** — junta todos los errores
  semánticos de una pasada sin frenar en el primero, igual que ANTLR
  con los de sintaxis.

`Main.java` (`verificarVisitorY`) corre el pipeline completo sobre un
programa Y? de prueba (estructura + función con parámetros/retorno +
`mientras` + `si/contrario`) que no debería generar errores, y también
sobre `ejemplos/Pila/utils/utils.y` real. Ese archivo real tiene un bug
genuino -no inventado-: llama a `imprimit(...)` (con typo) en vez de
`imprimir(...)`, y el Visitor lo reporta como
`funcion no declarada: 'imprimit'` (línea 5) — confirmado corriendo
`Main`, es la prueba de que el análisis semántico detecta errores
reales, no solo que "no explota".

## Visitor de Zetariano (`com.company.zetariano`)

Mismo diseño de dos pasadas que Y?, adaptado a que un archivo Zetariano
es siempre UNA sola clase pública (con herencia de estado tipo Java:
campos de instancia, `this`, sobrecarga de métodos/constructores,
llamadas encadenadas).

- **Ojo con el nombre de la clase de pasada 2 — no es `ZetarianoVisitor`:**
  Y? usa una gramática **separada** (`YParser` + `YLexer`), así que
  ANTLR genera `YParserVisitor`/`YParserBaseVisitor` (nombrados por
  "YParser"), y una clase propia llamada `YVisitor` no choca con nada.
  Zetariano usa una gramática **combinada** (`grammar Zetariano;`), así
  que ANTLR genera `ZetarianoVisitor`/`ZetarianoBaseVisitor`
  (nombrados directamente por "Zetariano") — si la clase de pasada 2 se
  llamara igual, sería un choque de clases duplicadas. Por eso se llama
  **`ZetarianoAnalizador`**. Esta misma regla aplica a Pig Latin
  (gramática combinada `grammar PigLatin;`): su futura clase de pasada
  2 debe llamarse `PigLatinAnalizador`, no `PigLatinVisitor`.
- **`RegistradorFirmasZetariano`** (pasada 1) — a diferencia de Y? (que
  necesita un truco de "nombres vacíos primero, campos después" para
  permitir auto-referencia entre varias estructuras), acá solo hay UNA
  clase por archivo, así que no hace falta ese truco: se registra la
  `SimboloClase` y de una vez sus campos/constructores/métodos (estos
  últimos dos con soporte de sobrecarga, a diferencia de las funciones
  de Y?, que no permiten repetir nombre).
- **`ZetarianoAnalizador`** (pasada 2, extiende
  `ZetarianoBaseVisitor<Operando>`) — mismo principio de una sola
  pasada (semántica + cuartetas juntas) que `YVisitor`, con la
  complejidad extra de:
  - **Inicializadores de campo antes del constructor:** cualquier
    `campo = valorInicial` se emite al principio de CADA constructor
    (semántica de Java), vía `emitirInicializacionesDeCampos()`.
  - **`this` implícito:** un identificador suelto que no es variable
    local/parámetro se resuelve como `this.campo` o `this.metodo(...)`
    automáticamente (`resolverAcceso`/`resolverLlamadaImplicita`).
  - **Llamadas encadenadas** (`obj.metodo1().metodo2()`): `resolverAcceso`
    recorre los sufijos con un índice manual (no `for-each`) porque un
    sufijo `.metodo` seguido de `(args)` son dos sufijos que juntos
    forman UNA llamada, y el resultado de esa llamada puede a su vez
    encadenar más sufijos.
  - **`+` como concatenación de cadenas:** a diferencia de Y? (donde
    `+` siempre es aritmético), los archivos reales de Zetariano usan
    `resultado = resultado + algo;` al estilo Java. `visitExpAditiva`
    y el `+=` de `visitAsignacion` detectan si alguno de los operandos
    es `CADENA` y en ese caso emiten una cuarteta `"concat"` en vez de
    exigir que ambos lados sean numéricos.
  - **`Tipo.NULO`** (nuevo en `Tipo.java`) — el tipo del literal `null`;
    a diferencia de `Tipo.ERROR` (no compatible con nada), `NULO` sí es
    compatible como valor asignable a cualquier tipo de referencia
    (`ESTRUCTURA`/`CLASE`/`ARREGLO`), igual que en Java.

- **Refactor (clases colaboradoras):** tanto `ZetarianoAnalizador` como
  `RegistradorFirmasZetariano` necesitaban resolver tipos (`resolverTipo`/
  `resolverTipoBase`), y antes del refactor esa lógica estaba duplicada
  literalmente igual en las dos clases (una la necesita para campos/
  parámetros/tipo de retorno en pasada 1, la otra para declaraciones/
  `new` en pasada 2). Se extrajo a **`ResolvedorTipoZetariano`**, una
  clase chica sin estado propio (solo `global`/`errores`), y ahora
  ambas pasadas comparten UNA sola instancia — se eliminó la
  duplicación real, no solo el tamaño. Aparte, el bloque de
  `resolverAcceso`/`resolverLlamadaImplicita`/`resolverLlamadaMetodo`
  (la lógica de `this` implícito + llamadas encadenadas, la parte más
  densa de todo el Visitor) se extrajo a **`ResolvedorAccesoZetariano`**,
  que recibe una referencia al `ZetarianoAnalizador` que la creó (como
  `visitor`) para poder llamar `visitor.visit(...)` sobre subexpresiones
  (índices de arreglo, argumentos de llamada) sin tener que extender
  ella misma ningún Visitor de ANTLR. `ambitoActual`/`claseActual` NO
  se guardan como campos en `ResolvedorAccesoZetariano` (cambian en
  cada llamada según el punto del árbol), se pasan como parámetros de
  `resolverAcceso(...)`. Resultado: `ZetarianoAnalizador` bajó de 1001
  a 796 líneas, quedándose solo con el recorrido de sentencias/
  expresiones y las cosas que de verdad son solo suyas (inicialización
  de campos, chequeo de tipos aritméticos/lógicos, etc.).

`Main.java` (`verificarVisitorZetariano`) corre el pipeline sobre
`ejemplos/Pila/Nodo.z` (0 errores esperados) y `ejemplos/Pila/Pila.z`
(comparten la misma `TablaSimbolosGlobal`, para que `Pila.z` pueda
resolver el tipo `Nodo` — el mismo patrón de resolución cruzada entre
archivos que `PigLatinAnalizador` va a necesitar para los `import`
reales). `Pila.z` tiene un bug genuino: `obtenerCima()` está declarado
`public int obtenerCima()` pero en un caso hace `return null;` — el
Visitor lo detecta y reporta `'return' espera entero, se recibio nulo`,
la misma clase de confirmación que el typo `imprimit` en Y?.

## Visitor de Pig Latin (`com.company.pig`)

El punto de entrada real de un programa completo: un `.pig` no define
tipos ni funciones propias, solo **importa** archivos `.y`/`.z` y
ejecuta `VARIABILES>` + `MAIOR>`. Por eso la pasada 1 aquí hace algo
distinto a Y?/Zetariano.

- **Otra vez, no es `PigLatinVisitor`:** `PigLatin.g4` también es
  gramática combinada, así que ANTLR genera `PigLatinVisitor`
  directamente — la clase de pasada 2 se llama **`PigLatinAnalizador`**,
  igual que `ZetarianoAnalizador`.
- **`RegistradorFirmasPigLatin`** (pasada 1) — por cada `import`, parsea
  el archivo correspondiente y corre **su propia** pasada 1
  (`RegistradorFirmasY` o `RegistradorFirmasZetariano`) contra la MISMA
  `TablaSimbolosGlobal` compartida, para que cualquier import (o el
  propio `.pig`) pueda usar el tipo/función/clase de cualquier OTRO
  import sin que el orden importe — mismo principio que el Paso A/B de
  `RegistradorFirmasY`, pero a nivel de archivo completo. Convención de
  `ruta` (ver `PigLatin.g4`): el último segmento es la extensión (`y` o
  `z`), el anterior es el nombre de archivo, y los previos son
  subcarpetas (`utils.utils.y` → `utils/utils.y`). Devuelve una lista de
  `ArchivoImportado` (el árbol ya parseado de cada import) para que la
  pasada 2 no tenga que volver a parsear.
  - **Limitación conocida, documentada en el código:** los imports se
    procesan uno completo a la vez, en el orden en que aparecen — a
    diferencia del Paso A/B de `RegistradorFirmasY` (que registra todos
    los nombres antes de llenar ningún campo). Si un import usara un
    tipo de OTRO import que aparece más abajo en la lista, daría
    "tipo desconocido". No afecta a `ejemplos/Pila/main.pig` (`Nodo.z`
    ya aparece antes que `Pila.z`), y la solución (si hiciera falta más
    adelante) es la misma idea de dos pasos, llevada a nivel de archivo.
- **`PigLatinAnalizador`** (pasada 2, extiende
  `PigLatinBaseVisitor<Operando>`) — primero corre `YVisitor`/
  `ZetarianoAnalizador` sobre el CUERPO de cada archivo importado (para
  generarle sus cuartetas), y recién después analiza `VARIABILES>` y
  `MAIOR>` del propio `.pig`. Dos piezas nuevas, propias de Pig Latin:
  - **`declaracion`/`inicializador` con tipo inferido:** a diferencia de
    Y?/Zetariano (tipo explícito), `esto x : <inicializador>;` no dice
    el tipo por separado — se infiere de CUÁL alternativa de
    `inicializador` matchea (`numerus/textum/decimalis/littera` +
    expresión, `verum`/`falsus`, `Estructura { ... }`, o
    `novus Clase(...)`).
  - **`resolverAcceso` es una fusión** de la de Y? (campo de estructura,
    índice de arreglo, función global suelta) y la de Zetariano (`.` +
    `(` consumidos juntos como una sola llamada a método, para
    `pila.apilar(x)`) — sin `this` implícito, porque Pig Latin no tiene
    métodos propios, solo los que trae importados.
  - **`leer` (`<<`) no valida tipos a propósito:** en
    `ejemplos/Pila/main.pig` real, `opcion` es `numerus` (entero) y se
    lee con `opcion << ;` igual que `lectura` — exigir que `leer`
    devuelva `CADENA` (como se asumió para `leer`/`leer()` de Y? por
    falta de mejor evidencia) generaría un error falso en un archivo
    real y válido. Se trata como lectura "cruda" sin chequeo de tipo.

- **Refactor (clases colaboradoras):** mismo criterio que en Zetariano.
  Se extrajo **`ResolvedorTipoPigLatin`** (`mapearTipoPrimitivo`/
  `resolverTipoDato`) — acá no había duplicación entre pasadas (pasada
  1 de Pig Latin no resuelve tipos, solo delega en las pasadas 1 de
  Y?/Zetariano), pero era una responsabilidad chica y propia que no
  necesitaba vivir dentro de `PigLatinAnalizador`. Y se extrajo
  **`ResolvedorAccesoPigLatin`** con el mismo patrón que
  `ResolvedorAccesoZetariano` (recibe `visitor` para poder llamar
  `visitor.visit(...)`, recibe `ambitoActual` por parámetro — acá sin
  `claseActual`, porque Pig Latin no tiene `this`), conteniendo la
  fusión Y?+Zetariano descrita arriba (`tipoDeSufijo`, `resolverAcceso`,
  `buscarCampo`, `resolverLlamadaMetodo`, `resolverLlamadaFuncionGlobal`).
  `PigLatinAnalizador` bajó de ~900 a 686 líneas.
- **Por qué separar así y no de otra forma:** cada clase que queda
  tiene una sola razón para cambiar — si cambia cómo se resuelve un
  tipo, se toca `ResolvedorTipoX`; si cambia cómo se resuelve `obj.algo`
  o una llamada encadenada, se toca `ResolvedorAccesoX`; si cambia una
  regla de sentencia/expresión nueva, se toca el Analizador/Visitor
  principal. Las tres clases de cada lenguaje se mantienen en el mismo
  paquete y se construyen juntas (el Analizador crea sus dos
  colaboradoras en el constructor), así que siguen siendo "una unidad"
  para quien lee el código, solo que ya no es un único archivo de 900+
  líneas. Se verificó que el refactor no cambió el comportamiento
  compilando todo el proyecto de nuevo contra los mismos ejemplos
  (`Nodo.z`, `Pila.z`, `utils.y`, `main.pig`) y confirmando exactamente
  los mismos errores esperados que antes.

`Main.java` (`verificarVisitorPigLatin`) corre el pipeline completo
sobre `ejemplos/Pila/main.pig` (los 3 imports + `VARIABILES>` +
`MAIOR>` con su `dum` y su `si/aliter` de 4 ramas). Se esperan
**exactamente 2 errores** — los mismos 2 ya conocidos y confirmados en
las pruebas de Y? y Zetariano (el typo `imprimit` de `utils.y` y el
`return null;` de `obtenerCima()` en `Pila.z`), no errores nuevos: es
la confirmación de que analizar el programa completo, con los 3
lenguajes trabajando juntos, no introduce ningún falso positivo.

**Nota sobre la gramática:** `PigLatin.g4` se ajustó para que
`condicional` use una regla `cuerpo` (igual que Y?/Zetariano) en vez de
escribir `'{' sentencia* '}'` tres veces seguidas inline. Con 3 ramas
`si/aliter/aliter` escritas inline, ANTLR junta TODAS las `sentencia`
de las 3 ramas en un solo `List<SentenciaContext>` sin ninguna marca de
dónde termina un bloque y empieza el siguiente — no hay forma de
separarlas de vuelta en el Visitor. Con `cuerpo`, ANTLR da un
`List<CuerpoContext>` en orden, uno por rama, cada uno con su propia
lista de sentencias ya separada. Se aprovechó el mismo cambio en
`cicloMientras`/`cicloHacer`/`cicloPara` por consistencia (ahí no era
estrictamente necesario, al tener un solo bloque cada uno, pero deja
las 4 reglas con la misma forma).

## Backend: cuartetas -> C (`com.company.backend`)

**Incremento 1** (funciones Y? 100% primitivas): `GeneradorC` traduce
las `Cuarteta` de `TablaCuartetas` a código C compilable.

- **Solo conoce el IR, no los AST de origen** — exactamente el contrato
  que ya describía el Javadoc original de `Cuarteta.java` ("el
  generador de bytecode/C solo conoce este modelo"). Por eso NO hizo
  falta tocar `YVisitor`/`ZetarianoAnalizador`/`PigLatinAnalizador`
  para este incremento: `GeneradorC` recibe `TablaCuartetas` (las
  cuartetas ya generadas) + `TablaSimbolosGlobal` (para recuperar la
  firma exacta — parámetros y tipo de retorno — de cada función, dato
  que las cuartetas por sí solas no cargan).
- **Alcance de este incremento:** funciones Y? con parámetros, retorno
  y variables locales de tipo primitivo escalar (`entero`→`int`,
  `decimal`→`double`, `caracter`→`char`, `booleano`→`int`), aritmética,
  comparaciones, lógicos, `si/contrario`, `mientras`/`hacer`/`para`
  (vía `if_false`/`if_true`/`goto`/`label`), `imprimir`/`leer`, y
  llamadas entre funciones que a su vez también sean 100% primitivas.
  Si una función usa un arreglo, una estructura no plana, o llama a
  algo no soportado todavía, `GeneradorC` **no genera C inválido**:
  omite esa función del `.c` y deja el motivo en `getNotas()`.
- **Cómo infiere los tipos:** los parámetros y las variables locales
  declaradas al nivel superior del cuerpo de la función salen
  directamente de `SimboloInvocable`/`TablaSimbolos` (ya construidos
  por `RegistradorFirmasY`/`YVisitor, sin volver a analizarlas). Los
  temporales (`t0`, `t1`, ...) —que no existen en ninguna tabla de
  símbolos— se infieren con un pequeño pase propio sobre la secuencia
  de cuartetas (literal → tipo por patrón; operador aritmético/relacional/
  lógico → tipo del resultado por las mismas reglas de promoción que ya
  usa el Visitor). Ver el Javadoc de la clase para la limitación
  conocida (una variable declarada dentro de un bloque anidado, y nunca
  antes asignada, no se puede inferir).
- `Main.java` (`verificarBackendC`) reusa el mismo "programa Y? de
  prueba" de `verificarVisitorY` (`sumar` + `procesar`, con aritmética,
  `mientras`, `si/contrario`, `imprimir` y una llamada entre ambas) y
  confirma que las dos se traducen a C sin ninguna nota de "omitida".
  El `.c` generado queda en `salida/backend/yFeliz.c`. Además, fuera
  del proyecto Maven (el sandbox no tiene el jar de ANTLR), se probó
  `GeneradorC` con `gcc` real: se reconstruyó a mano la misma secuencia
  de cuartetas que `YVisitor` generaría (llamando a la misma API real
  de `TablaCuartetas`), se generó el `.c`, se compiló con
  `gcc -Wall` (0 advertencias) y se corrió — `procesar(15)` imprime
  `mucho` (total llega a 105) y `procesar(3)` imprime `poco` (total
  llega a 3), exactamente lo esperado.

**Incremento 2** (estructuras "planas" de Y?): agrega soporte para
`%estructuras` cuyos campos son TODOS primitivos (sin campos de tipo
estructura, ni siquiera no recursivos).

- **Por qué "plana" y no cualquier estructura:** Y? no tiene `new` para
  estructuras (no existe esa cuarteta en `YVisitor`, a diferencia de
  Zetariano/Pig Latin), así que una variable de tipo estructura se
  traduce como un `struct` de C **por valor** (`struct Punto p;`), no
  por puntero — y ahí es donde `resultado.x = a.x + b.x` ya es
  sintaxis C válida tal cual, sin traducir nada: el texto compuesto con
  `.` ya lo arma `resolverAcceso` en el propio `YVisitor`/
  `ResolvedorAccesoY`. El problema es un campo de tipo estructura (por
  ejemplo la auto-referencia real `Nodo.siguiente : Nodo` de
  `ejemplos/Pila`): un `struct Nodo` no puede contener OTRO
  `struct Nodo` por valor (tamaño infinito), tendría que ser
  `struct Nodo*` — y eso obliga a decidir cuándo una asignación
  necesita `&` y cuándo un acceso encadenado necesita `->` en vez de
  `.`, que es esencialmente el mismo problema que resolver objetos de
  Zetariano. Por eso se deja junto a esa pieza en el **incremento 3**,
  en vez de resolverlo a medias aquí.
- **Otra limitación de origen, no del backend:** la sintaxis de Y?
  distingue parámetros por valor (`tipo nombre`) de por referencia
  (`{} tipo nombre`, ver el comentario en `YParser.g4`), pero
  `RegistradorFirmasY` no guarda esa distinción en ningún lado — así
  que, tal como está today el analizador semántico, todo parámetro de
  tipo estructura ya se trata igual sin importar cómo se escribió; el
  backend simplemente sigue esa misma realidad (por valor).
- `GeneradorC` emite un `struct Nombre { tipo campo; ... };` por cada
  estructura plana encontrada en `TablaSimbolosGlobal`, y una variable
  local de tipo estructura se declara con `= {0}` (C deja un struct
  local sin inicializar con basura si no se hace explícito).
  `Main.java` (`verificarBackendCEstructuras`) usa una función
  `sumarPuntos(Punto a, Punto b) -> Punto` real (dos parámetros y un
  retorno de tipo estructura, más una variable local del mismo tipo) y
  confirma que se genera el `struct`, la firma y las asignaciones de
  campo esperadas, sin ninguna nota de "omitida". El `.c` generado
  queda en `salida/backend/puntos.c`. También se probó con `gcc` real
  (fuera del proyecto Maven, mismo método que el incremento 1):
  `sumarPuntos({2,3}, {10,20})` compiló con `gcc -Wall` (0 advertencias)
  y corrió, imprimiendo `12 23` — exactamente `(2+10, 3+20)`.

**Incremento 3** (estructuras Y? anidadas/auto-referenciadas): el caso
real que quedó pendiente del incremento 2 — una estructura con un campo
de su propio tipo, como `Nodo.siguiente : Nodo` (el mismo patrón que ya
usan `ejemplos/Pila/Nodo.z`/`Pila.z`, pero aquí como estructura de Y?).

- **La regla:** una *variable* de tipo estructura (parámetro, local,
  retorno) sigue siendo `struct X` **por valor**, igual que en el
  incremento 2. Pero un *campo* cuyo tipo es otra estructura se traduce
  como `struct X*` **(puntero)**, porque un `struct Nodo` no puede
  contener otro `struct Nodo` por valor (tamaño infinito). `GeneradorC`
  resuelve las dos consecuencias de esto automáticamente, sin que haga
  falta tocar `YVisitor` para nada:
  - Al emitir una cadena de acceso (`nodo.siguiente.valor`), cada `.`
    se vuelve `->` apenas el tramo anterior ya es un campo-puntero
    (`traducirAccesoC`) — aunque en la cuarteta original siempre sea
    `.`, porque así la arma `resolverAcceso` de `YVisitor`.
  - Al asignar una estructura **por valor** a un campo-puntero, se
    antepone `&` automáticamente (`nodo.siguiente = otroNodo;` →
    `nodo.siguiente = &otroNodo;`); el caso inverso (copiar un
    campo-puntero a una variable por valor) antepone `*` para
    desreferenciar.
- **Advertencia real, heredada del lenguaje, no de este backend:** Y?
  no tiene `new`/heap para estructuras, así que toda estructura vive en
  la pila de la función que la declaró. Si un campo-puntero termina
  apuntando a una variable/temporal local y esa referencia se usa
  después de que la función retorna, es un puntero colgante — el mismo
  riesgo que ya existe en C puro al hacer `&variableLocal`. `GeneradorC`
  no lo inventa ni lo puede evitar sin que el front end tenga alguna
  forma de asignación dinámica (fuera de alcance de este proyecto por
  ahora).
- `Main.java` (`verificarBackendCEstructurasAnidadas`) usa
  `sumarCadena(Nodo n) -> entero` (con `n.siguiente.valor`) y confirma
  que el campo se traduce como `struct Nodo*` y el acceso como
  `n.siguiente->valor`. El `.c` generado queda en `salida/backend/nodo.c`.
  También se probó con `gcc` real: con una cadena de 2 nodos armada a
  mano en un `main()` de C (10 → 20), `sumarCadena` da `30`, y además se
  confirmó por separado que la inserción automática de `&`/`*` genera
  C que compila limpio con `gcc -Wall` (0 advertencias) en ambos
  sentidos (asignar una estructura por valor a un campo-puntero, y
  copiar un campo-puntero a una variable por valor).

**Incremento 4** (clases/objetos de Zetariano): constructores, métodos,
`this`, `new` y llamadas a método (explícitas, implícitas y
encadenadas).

- **Por qué el modelo es MÁS simple que el de las estructuras de Y?:**
  a diferencia de Y?, Zetariano **sí tiene `new`/heap real** (existe esa
  cuarteta en `ZetarianoAnalizador`), así que no hace falta la mezcla
  valor/puntero del incremento 3. Acá **todo valor de tipo clase**
  (variable, parámetro, campo, `this`, temporal) es **siempre un
  puntero** (`struct X*`), desde el principio — `tipoC(...)` ya
  devuelve la forma con `*` directamente para una categoría `CLASE`, y
  eso alcanza para que `traducirAccesoC`/`tipoDeAccesoCampo` (sin
  ningún cambio propio) ya emitan `->` desde el primer segmento de
  cualquier cadena que empiece en una variable/campo de clase.
- **Constructor → `Clase_crear_N(...)`:** reserva memoria con
  `calloc(1, sizeof(struct Clase))` (no `malloc`) y retorna el puntero.
  El `calloc` es una decisión deliberada, no solo prolijidad: un campo
  de objeto **sin inicializador explícito** (p. ej. la real
  `Nodo siguiente;` de `ejemplos/Pila/Nodo.z`, o `Pila.cima`) **no
  genera ninguna cuarteta de asignación** — `ZetarianoAnalizador.
  emitirInicializacionesDeCampos()` solo emite algo cuando el campo SÍ
  trae `= valorInicial` — así que sin `calloc` ese campo quedaría con
  basura de memoria en vez de `NULL`. Es el mismo espíritu que el
  `= {0}` que ya usan las variables locales de tipo estructura desde el
  incremento 2.
- **Método → `Clase_metodo_N(struct Clase* this, params...)`:**
  `GeneradorC` agrega el parámetro `this` él mismo (nunca es un
  `Simbolo` real en ningún ámbito — no viene de
  `SimboloInvocable.getParametros()`).
- **Llamadas a método:** una cuarteta `"call"` cuyo `arg1` tiene un
  `.` (p. ej. `this.siguiente.obtenerValor` o `pila.apilar`) es una
  llamada a método, no a una función suelta de Y?. El último segmento
  es el nombre del método; el resto (que puede venir ya encadenado, ej.
  `this.siguiente`) es la expresión del objeto. Según
  `ResolvedorAccesoZetariano.resolverLlamadaMetodo`, los argumentos
  reales se empujan con `"param"` en orden y **el objeto se empuja una
  vez más, al final** — por eso `GeneradorC` toma los últimos (N+1)
  valores pendientes como `[arg1..argN, objeto]` y arma
  `Clase_metodo_N(objeto, arg1, ..., argN)`. Esto cubre tanto una
  llamada explícita (`pila.apilar(5)`) como una implícita
  (`incrementar()` dentro de un método, que
  `ResolvedorAccesoZetariano` ya resuelve como `this.incrementar` antes
  de llegar a `GeneradorC`) y una encadenada
  (`this.siguiente.obtenerValor()`).
- **`new Clase(...)`** (cuarteta `"new"`: `arg1`=nombre de la clase,
  `arg2`=cantidad de argumentos) se traduce a una llamada a
  `Clase_crear_N(...)`.
- **Sigue pendiente, a propósito:** `concat` (y en general el tipo
  cadena) — necesita una funcionalidad de runtime genuinamente aparte
  (buffers de tamaño fijo o dinámico, conversión entero/decimal →
  cadena) que se prefirió no resolver a medias junto con los objetos de
  este incremento. Es el único motivo real por el que, por ejemplo,
  `toString()` de la `Pila.z` real todavía no se traduce (aparte de
  `obtenerCima()`, que ya tiene su propio bug real y conocido —
  `return null;` en un método `int` — y por eso nunca llega ni siquiera
  a generar cuartetas limpias).
- `Main.java` (`verificarBackendCObjetos`) usa una clase de prueba
  propia (`Contador`, no la `Pila.z` real — para mantener el programa
  100% limpio, dado que `Pila.z` sí tiene el bug conocido de
  `obtenerCima()` y además necesita `concat` para `toString()`) que
  ejercita exactamente los mismos mecanismos que los archivos reales:
  constructor con un campo con inicializador (`valor`) y uno sin
  inicializador (`siguiente`, el mismo patrón de auto-referencia real
  de `Nodo.z`), un método que muta `this`, una llamada implícita a
  método, una llamada explícita **encadenada**, y un método que crea y
  retorna otro objeto con `new`. Se confirma que todo se traduce sin
  ninguna nota de "omitida". El `.c` generado queda en
  `salida/backend/contador.c`. También se probó con `gcc` real (mismo
  método que los incrementos 1-3): se reconstruyó a mano la secuencia
  de cuartetas exacta que `ZetarianoAnalizador` generaría, se compiló
  con `gcc -Wall` (0 advertencias) y se corrió — confirma que
  `this->siguiente` encadena correctamente, que la llamada implícita
  `incrementar()` y la encadenada `this.siguiente.obtenerValor()`
  llaman a la función C correcta, y que un objeto creado con `new`
  (`Contador_crearVinculado_1`) efectivamente tiene su campo sin
  inicializador (`siguiente`) en `NULL` gracias al `calloc`.

**Incremento 5** (tipo CADENA y `concat`): el último pendiente del
backend — cierra `toString()` de `Pila.z` y en general cualquier uso de
cadenas.

- **Representación:** una CADENA (literal, variable, campo, parámetro,
  retorno o temporal) es siempre `char*`, con `malloc` reservando
  memoria nueva cada vez que se produce una cadena **nueva**. No hay
  `free` en ningún lado — mismo espíritu que los objetos de Zetariano
  desde el incremento 4 (tampoco se liberan nunca); aceptable para el
  alcance de este proyecto, aunque un programa que concatene mucho en
  un ciclo largo iría acumulando memoria.
- **`concat`** (cualquiera de los dos operandos puede ser de **cualquier
  tipo primitivo**, no solo CADENA — `resultado + actual.getDato()`
  concatena una CADENA con un ENTERO, ver
  `ZetarianoAnalizador.visitExpAditiva`) se traduce a una llamada a
  `c3d_concat(char* a, char* b)`; el operando que no sea ya `char*` se
  envuelve primero en `c3d_entero_a_cadena`/`c3d_decimal_a_cadena`/
  `c3d_caracter_a_cadena` según su tipo. Las cuatro son funciones
  auxiliares que `GeneradorC` agrega él mismo al encabezado del `.c` —
  pero **solo las que realmente se usaron** en ese programa en
  particular: con `gcc -Wall`, una función `static` sin usar es una
  advertencia (`-Wunused-function`), así que agregarlas siempre "por si
  acaso" ensuciaría cualquier programa que, por ejemplo, no necesite
  convertir un `decimal` a cadena.
- **`==`/`!=` entre dos CADENA** (p. ej. `this.texto == otro`) se
  traduce con `strcmp(...) == 0` / `!= 0` en vez de comparar los
  punteros — comparar el contenido, no la dirección. Los operadores de
  orden (`<`, `>`, `<=`, `>=`) nunca llegan a las cuartetas con una
  CADENA de por medio: `ZetarianoAnalizador.visitExpRelacional` ya lo
  rechaza como error semántico antes (exige `esNumerico()`).
- **Limitación heredada, no nueva de este incremento:** BOOLEANO se
  representa igual que ENTERO (`int` liso, sin ninguna marca que los
  distinga) desde el incremento 1 — así que concatenar un booleano de
  Zetariano produce `"1"`/`"0"` en vez de `"verdadero"`/`"falso"`. No se
  ejercita en los archivos reales del proyecto.
- `Main.java` (`verificarBackendCCadenas`) usa una clase de prueba
  propia (`Mensaje`, mismo motivo que `Contador` en el incremento 4)
  que ejercita el mismo patrón real de `toString()` en `Pila.z`: un
  campo CADENA, concatenar CADENA+ENTERO con `+`, concatenar
  CADENA+CADENA con `+=`, retornar una CADENA, y comparar dos CADENA
  con `==`. Confirma además que el encabezado del `.c` **solo** trae
  los helpers `c3d_concat`/`c3d_entero_a_cadena` (no
  `c3d_decimal_a_cadena`/`c3d_caracter_a_cadena`, que esa clase no usa).
  El `.c` generado queda en `salida/backend/mensaje.c`. También se
  probó con `gcc` real (mismo método que los incrementos 1-4): se
  reconstruyó a mano la secuencia de cuartetas exacta, se compiló con
  `gcc -Wall` (**0 advertencias**, incluida la de función sin usar) y
  se corrió — `"Pila: " + 10 + " " + 20` da `"Pila: 10 20"`, y la
  comparación con `strcmp` da `1` contra sí mismo y `0` contra una
  cadena distinta.

Con esto, **el backend queda completo** salvo arreglos (bloqueado por
el front end, no por el backend — ver más abajo).

## Próximos pasos sugeridos

1. Backend: cuartetas -> C — **incrementos 1 a 5 hechos** (funciones Y?
   primitivas + estructuras planas + estructuras anidadas/
   auto-referenciadas + clases/objetos de Zetariano + cadenas/`concat`,
   ver arriba). **Solo queda pendiente:** arreglos, bloqueado por el
   front end (ver la nota en el Javadoc de `GeneradorC` — falta el
   tamaño y la inicialización real de un arreglo en las cuartetas de
   Y?; no es algo que el backend pueda resolver por su cuenta).
2. GUI: editor + coloreado + árbol de trabajo.
3. Manuales técnico y de usuario.

~~4. Refactor de las clases Visitor más grandes~~ — **hecho.**
`ZetarianoAnalizador` y `PigLatinAnalizador` ahora delegan resolución de
tipos y de acceso en `ResolvedorTipoX`/`ResolvedorAccesoX` (ver las
secciones de cada Visitor arriba). Verificado que compila y da los
mismos resultados que antes del refactor.
