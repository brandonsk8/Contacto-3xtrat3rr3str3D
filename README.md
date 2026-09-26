# Contacto 3xtrat3rr3str3D — Proyecto 1 (Compiladores 2, CUNOC)

Compilador que analiza y traduce tres lenguajes de programación (Y?,
Zetariano y Pig Latin) a código de tres direcciones (cuartetas), y
genera a partir de ellas código C ejecutable. Incluye una interfaz
gráfica de escritorio (Swing) para editar, ejecutar y revisar los
programas de los tres lenguajes.

**Autor:** Brandon — carné 201931217, Ingeniería en Ciencias y Sistemas,
USAC CUNOC.

**Repositorio:** https://github.com/brandonsk8/Contacto-3xtrat3rr3str3D

## Tecnologías utilizadas

- Java 17
- Maven (gestión de dependencias y build)
- ANTLR4 (análisis léxico y sintáctico de los tres lenguajes)
- Swing (interfaz gráfica)
- JUnit / verificación manual con `gcc` para el backend en C

## Requisitos previos

- JDK 17 o superior
- Maven 3.8+
- Plugin de ANTLR v4 para IntelliJ (recomendado, no obligatorio)
- Un compilador de C (`gcc` o similar) para compilar el código generado

## Cómo compilar y ejecutar

1. Clonar el repositorio y abrirlo en IntelliJ IDEA (`File -> Open...`,
   seleccionando la carpeta que contiene el `pom.xml`).
2. Dejar que IntelliJ importe el proyecto como proyecto Maven.
3. (Opcional) Instalar el plugin **ANTLR v4** desde el Marketplace de
   IntelliJ para resaltado y generación de parsers en vivo sobre los
   archivos `.g4`.
4. Compilar el proyecto:

   ```
   mvn clean compile
   ```

   Esto genera los lexers/parsers de los tres lenguajes en
   `target/generated-sources/antlr4`.
5. Ejecutar la aplicación desde `com.company.gui.VentanaPrincipal`
   (clase con su propio `main`, es el punto de entrada de la GUI).
6. (Opcional) Ejecutar `com.company.Main` para correr la suite de
   verificación del pipeline completo sobre los tres lenguajes.

### Generar el `.jar` ejecutable

```
mvn clean package
```

El `.jar` resultante (`target/*.jar`) abre la GUI al ejecutarse con
`java -jar`.

## Estructura del proyecto

```
compilador2-proyecto1/
├── pom.xml
├── src/main/antlr4/com/company/
│   ├── y/YLexer.g4, YParser.g4      # gramática de Y? (lexer/parser separados, indentación significativa)
│   ├── zetariano/Zetariano.g4       # gramática de Zetariano (combinada)
│   └── pig/PigLatin.g4              # gramática de Pig Latin (combinada)
├── src/main/java/com/company/
│   ├── Main.java                    # suite de verificación del pipeline completo
│   ├── y/                           # lexer base (indentación), pasada 1 y pasada 2 de Y?
│   ├── zetariano/                   # pasada 1 y pasada 2 de Zetariano, resolvedores auxiliares
│   ├── pig/                         # pasada 1 y pasada 2 de Pig Latin, resolvedores auxiliares
│   ├── semantico/                   # tabla de símbolos y tipos, compartidos por los 3 lenguajes
│   ├── ir/                          # modelo de cuarteta y su contenedor (Intermediate Representation)
│   ├── backend/                     # cuartetas -> C: TiposC, InferenciaTipos, EmisorSentencias, GeneradorC
│   └── gui/                         # editor Swing, resaltado de sintaxis, ejecución del pipeline
├── ejemplos/                        # programas de referencia (Pila enlazada en los 3 lenguajes)
└── docs/                            # manual técnico y manual de usuario
```

## Arquitectura general

Cada lenguaje se procesa en dos pasadas:

1. **Registro de firmas** (`RegistradorFirmasY`, `RegistradorFirmasZetariano`,
   `RegistradorFirmasPigLatin`): recorre la estructura de alto nivel del
   programa (estructuras, clases, funciones, imports) y registra sus
   firmas en una `TablaSimbolosGlobal` compartida, sin analizar el
   cuerpo de ninguna función o método todavía.
2. **Análisis y generación de cuartetas** (`YVisitor`, `ZetarianoAnalizador`,
   `PigLatinAnalizador`): recorre el cuerpo de cada función/método,
   valida tipos contra la tabla de símbolos, y en el mismo recorrido
   emite cuartetas (código de tres direcciones) a una `TablaCuartetas`.

El resultado de ambas pasadas (`TablaSimbolosGlobal` + `TablaCuartetas`)
alimenta al backend (`com.company.backend`, con `GeneradorC` como punto
de entrada), que traduce las cuartetas a código C compilable, sin
necesidad de volver a tocar el árbol sintáctico de ningún lenguaje.

```
código fuente (.y / .z / .pig)
        │
        ▼
  Lexer / Parser (ANTLR4)
        │
        ▼
  Pasada 1: registro de firmas ──► TablaSimbolosGlobal
        │
        ▼
  Pasada 2: análisis semántico + generación de cuartetas ──► TablaCuartetas
        │
        ▼
  GeneradorC ──► código C
```

## Los tres lenguajes

### Y? — indentación significativa

Y? no usa llaves para delimitar bloques: usa la indentación, igual que
Python. Como ANTLR4 no soporta esto de forma nativa, la gramática se
divide en dos piezas:

- **`YLexer.g4`** declara `NEWLINE`, `INDENT` y `DEDENT` como tokens
  sintéticos.
- **`YLexerBase.java`** (superclase de `YLexer`) sobreescribe
  `nextToken()`: mide la indentación de cada línea, la compara contra
  una pila de niveles vistos, y sintetiza `INDENT`/`DEDENT` según
  corresponda. El parser nunca ve la indentación cruda, solo estos
  tokens ya resueltos.

Puntos particulares del lenguaje: `imprimir`/`leer` no son palabras
reservadas (se validan en el análisis semántico, no en el parser);
los operadores soportados son `==, !=, <, >, +, -, *, /, &&, ||, !`;
no incluye `<=`, `>=`, `%` ni asignaciones compuestas.

### Zetariano — orientado a objetos, estilo Java

Gramática combinada (lexer + parser en un solo archivo), con llaves y
`;` explícitos. Soporta clases con campos, constructores y métodos
sobrecargados, herencia de estado vía `this`, llamadas a método
encadenadas (`obj.metodo1().metodo2()`) e implícitas
(`metodo()` dentro de otro método equivale a `this.metodo()`), y el
operador `+` como concatenación cuando alguno de los operandos es una
cadena.

La clase de la pasada 2 se llama `ZetarianoAnalizador` (no
`ZetarianoVisitor`): al ser una gramática combinada, ANTLR ya genera
una interfaz `ZetarianoVisitor`/`ZetarianoBaseVisitor` en el mismo
paquete, así que una clase propia con ese nombre chocaría con ella. Por
la misma razón la clase de Pig Latin se llama `PigLatinAnalizador`. Y?
sí puede llamarse `YVisitor` porque usa una gramática separada
(`YLexer.g4` + `YParser.g4`), y ANTLR nombra su interfaz generada
`YParserVisitor`, sin colisión posible.

### Pig Latin — el lenguaje de entrada

Un archivo `.pig` no define tipos ni funciones propias: solo importa
archivos `.y`/`.z` y ejecuta un bloque `VARIABILES>` + `MAIOR>`. Su
pasada 1 (`RegistradorFirmasPigLatin`) resuelve cada `import` corriendo
la pasada 1 correspondiente (Y? o Zetariano) contra la misma tabla de
símbolos global, para que cualquier import pueda referenciar tipos de
cualquier otro. Su pasada 2 primero genera las cuartetas del cuerpo de
cada archivo importado, y luego analiza el cuerpo del propio `.pig`.

## Análisis semántico (`com.company.semantico`)

Modelo de tipos y símbolos compartido por los tres lenguajes:

- **`Tipo`** — tipo semántico unificado (primitivos, `ESTRUCTURA`/
  `CLASE` por nombre, `ARREGLO`). Cada lenguaje traduce sus propias
  palabras de tipo a este vocabulario único.
- **`Simbolo`** / **`SimboloInvocable`** / **`SimboloEstructura`** /
  **`SimboloClase`** — representan variables, funciones/métodos/
  constructores, estructuras y clases respectivamente.
- **`TablaSimbolos`** — un ámbito (scope) encadenado a su padre, con
  soporte de shadowing entre ámbitos anidados.
- **`TablaSimbolosGlobal`** — catálogo de todo lo definido a nivel de
  archivo entre los `.y`/`.z` que un `.pig` importa.
- **`GestorErrores`** / **`ErrorSemantico`** — acumula todos los
  errores semánticos de una pasada sin detenerse en el primero, y
  etiqueta cada error con el archivo de origen (necesario porque Pig
  Latin junta en una sola lista los errores de `main.pig` y de cada
  archivo importado).

## Backend: cuartetas → C (`com.company.backend`)

El paquete se llama `backend` porque su responsabilidad es la contraria
a la del `front end` (lexer/parser/análisis semántico de cada
lenguaje): traduce la representación intermedia (cuartetas) al lenguaje
de salida (C), sin conocer los árboles sintácticos de origen ni las
palabras reservadas de ningún lenguaje.

No es una sola clase, sino cuatro, cada una con una responsabilidad
propia:

- **`TiposC`** — resuelve qué tipo C le corresponde a cada `Tipo`
  semántico o a un texto de cuarteta (variable, temporal, literal,
  acceso `obj.campo`/`arr[i]`). La usan las otras dos clases y
  `GeneradorC`.
- **`InferenciaTipos`** (Pase A) — recorre el cuerpo de una función una
  primera vez para asignarle un tipo C a cada temporal y variable antes
  de emitir ninguna línea (una declaración en C va antes de su uso,
  pero una cuarteta puede usar un temporal antes de la instrucción que
  "explicaría" su tipo si se leyera aislada).
- **`EmisorSentencias`** (Pase B) — con los tipos ya resueltos, recorre
  el mismo cuerpo una segunda vez y emite la sentencia C real de cada
  cuarteta (asignaciones, saltos, llamadas, lectura/escritura, arreglos,
  concat, ...).
- **`GeneradorC`** — orquestador: decide qué función/constructor/
  método/clase/estructura se puede traducir (los prefiltros
  `calcular*Soportad*s`), construye las tres clases anteriores, arma la
  firma y las declaraciones de cada función, y ensambla el `.c` final
  (includes, helpers de cadena, structs, prototipos, cuerpos).

Cubre:

- Funciones con parámetros, retorno y variables locales de tipo
  primitivo, control de flujo, y llamadas entre funciones.
- Estructuras "planas" (campos primitivos) traducidas como `struct`
  por valor.
- Estructuras auto-referenciadas o anidadas (`Nodo.siguiente : Nodo`),
  traducidas como `struct X*`, con inserción automática de `&`/`*` y
  de `->` en los accesos encadenados.
- Clases/objetos de Zetariano: constructores (`Clase_crear_N`, con
  `calloc` para que los campos sin inicializador queden en `NULL` —
  el objeto vive en el heap, tal como pide el enunciado), métodos
  (`Clase_metodo_N`, con `this` como primer parámetro), `new` y
  llamadas encadenadas/implícitas.
- Tipo cadena y concatenación (`concat`), con conversión automática de
  otros tipos primitivos a cadena, y comparación de cadenas por
  contenido (`strcmp`) en vez de por dirección.
- Arreglos de 1 dimensión de tipo `entero`/`decimal`/`booleano` en los
  tres lenguajes: dinámicos en Zetariano y Pig Latin (`new tipo[n]` /
  `series nombre[n] : tipo`, reservados con `malloc()` en tiempo de
  ejecución), y de tamaño fijo literal en Y? (`entero arr[10]`,
  declarados como un arreglo real de C — `int arr[10];` — sin
  `malloc`). `caracter` queda deliberadamente fuera: su tipo C
  (`char*`) chocaría con cómo ya se representa `cadena`.
- `leer()`/`scanf` con reintento automático: si el usuario ingresa un
  valor inválido para `%d`/`%lf`, el `scanf` generado descarta la línea
  completa y vuelve a pedir la entrada, en vez de entrar en un ciclo
  infinito (bug real de C con `scanf` que fallaba en las pruebas
  manuales).

Si una función usa una construcción no soportada todavía, `GeneradorC`
no genera C inválido: omite esa función/clase del `.c` y deja el motivo
disponible en `getNotas()`.

**Limitaciones conocidas:** arreglos multidimensionales; arreglos cuyo
tipo de elemento es una estructura o una clase (`series personas[3] :
Persona`); arreglos-parámetro de Zetariano; y arreglos de Y? cuyo
tamaño no sea un entero literal (una expresión o una variable).

## Stack y heap en el proceso de compilación

- **Heap:** los objetos de Zetariano (`new`) y Pig Latin (`novus`) se
  traducen a `calloc()` en el C generado — viven más allá de la función
  que los crea, tal como exige el enunciado. Los arreglos dinámicos
  (`new tipo[n]` / `series`) se traducen a `malloc()` por el mismo
  motivo.
- **Stack:** toda variable local del C generado (`int t0;`, `int
  suma;`) es una variable de pila real, y una llamada recursiva (la
  suite de pruebas incluye `factorial`) usa el call stack nativo de C.
  Dentro del propio compilador, `pilaCiclos` (un `Deque` usado como
  pila, con `push`/`pop`) en `YVisitor`/`ZetarianoAnalizador`/
  `PigLatinAnalizador` guarda las etiquetas de "continuar"/"fin" del
  ciclo más interno, para que `romper`/`continuar` (o `interrumpe`/
  `perge` en Pig Latin) sepan a cuál saltar sin importar cuántos ciclos
  haya anidados.

## Interfaz gráfica (`com.company.gui`)

- **`VentanaPrincipal`** — editor con árbol de archivos del proyecto
  abierto, numeración de líneas, coloreado de sintaxis según la
  extensión del archivo (`.y`/`.z`/`.pig`), consola de salida, y
  botones para abrir/crear/guardar archivos y carpetas, correr el
  pipeline completo sobre el archivo actual, y exportar el código C
  generado a un archivo `.c`.
- **`Resaltador`** — coloreado de sintaxis (palabras reservadas,
  cadenas, comentarios, números) implementado con expresiones
  regulares y `javax.swing.text`, sin librerías externas.
- **`EjecutorPipeline`** — corre el pipeline completo (lexer, parser,
  análisis semántico, cuartetas, generación de C) sobre el texto que
  está en el editor en ese momento, sin depender de que el archivo esté
  guardado en disco.

## Archivos de ejemplo (`ejemplos/`)

`ejemplos/Pila/` es un proyecto de referencia (una pila enlazada) con
los tres lenguajes trabajando juntos: `main.pig` importa `Nodo.z`,
`Pila.z` y `utils/utils.y`. `Main.java` lo usa como prueba de
regresión en cada corrida.

## Documentación adicional

- `docs/manual_tecnico.docx` — tecnologías utilizadas, diagrama de
  clases, especificación de palabras reservadas/símbolos/gramática y
  tabla de compatibilidad de tipos de los tres lenguajes.
- `docs/manual_usuario.docx` — manual de uso de la interfaz gráfica.
