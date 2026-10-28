# St4ck Over-Pig — Proyecto 2 (Compiladores 2, USAC CUNOC)

Compilador de tres lenguajes (**Y?**, **Zetariano** y **Pig Latin**) que genera
código de tres direcciones (cuartetas) y de ahí dos destinos:

- **Ensamblador RISC-V** (RV32IMFD), compatible con el simulador RARS.
- **Código C que usa únicamente etiquetas y `goto`** (un solo `main`, sin funciones definidas por el usuario).

La aplicación incluye una interfaz gráfica (Swing) para editar, compilar y
**ejecutar** el ensamblador generado con un intérprete RISC-V embebido, sin
herramientas externas.

**Autor:** Brandon Gustavo Güinac Román — carné 201931217, Ingeniería en Ciencias y Sistemas, USAC CUNOC.
**Repositorio:** https://github.com/brandonsk8/Contacto-3xtrat3rr3str3D

## Novedades del Proyecto 2

- **Zetariano:** encapsulamiento (`public`, `private`, `protected`, default), herencia (`extends`, `super`, `this`, `this(...)`) y polimorfismo con `@Override` obligatorio.
  - `protected`: mismo paquete y subclases (incluso de otro paquete).
  - default (sin modificador): solo el mismo paquete.
- **Pig Latin:** objetos con tipo explícito y objetos nulos.
  ```
  esto x : novus Persona(12, "Ana") : Persona ;
  esto y : novus Profesor(12, x, novus Persona()) : Persona ;
  esto z : null : Persona ;
  ```
- **Y?:** sin cambios respecto al Proyecto 1.
- **Backend RISC-V** y **backend C con etiquetas y goto**.
- **Intérprete RISC-V embebido** y consola de ejecución con entrada interactiva.
- **Interfaz renovada:** tema oscuro/claro, resaltado de sintaxis en tiempo real, árbol de trabajo con abrir, guardar y **descargar** (archivo o carpeta en `.zip`).
- Exportación del código de tres direcciones (`.c3d`), del C y del ensamblador.

## Tecnologías

- Java 17
- ANTLR 4.13.1 (análisis léxico y sintáctico, patrón Visitor)
- Maven
- Swing (interfaz gráfica; resaltado de sintaxis propio, sin librerías externas)
- RISC-V RV32IMFD (se valida con RARS)

## Requisitos

- JDK 17 o superior
- Maven 3.8+

## Compilar y ejecutar

```
mvn clean compile
```

Genera los lexers/parsers de los tres lenguajes en `target/generated-sources/antlr4`.
Luego ejecuta la clase `com.company.gui.VentanaPrincipal` (es el punto de entrada de la GUI).

Para generar el `.jar` ejecutable:

```
mvn clean package
java -jar target/compilador2-proyecto1-*.jar
```

## Cómo usar la aplicación

1. **Archivo > Abrir carpeta de proyecto…** y elige la carpeta con tus `.y`, `.z` y `.pig`.
2. Abre un `.pig` (es el programa principal; importa los `.y` y `.z` con `import ruta.Archivo.z`).
3. **Compilar** muestra errores léxicos, sintácticos y semánticos con línea y columna (doble clic salta a la línea).
4. **Compilar y ejecutar ▶** corre el programa dentro de la aplicación (pestaña *Ejecución*; si el programa lee datos, escríbelos en el campo inferior y pulsa Enter).
5. Las pestañas *Cuartetas (C3D)*, *C generado* y *RISC-V generado* muestran el código; los botones *Guardar* lo exportan.
6. **Archivo > Descargar selección…** (o clic derecho en el árbol) exporta un archivo o una carpeta como `.zip`.

Los `.y` y `.z` son bibliotecas/clases: se compilan para validarlos, pero solo un `.pig` tiene programa principal.

### Ejecutar el ensamblador en RARS

Guarda el `.asm` con *Guardar RISC-V*, ábrelo en RARS, ensámblalo (F3) y ejecútalo (F5). La salida debe coincidir con la de la pestaña *Ejecución*.

## Arquitectura

```
código fuente (.y / .z / .pig)
        │  ANTLR4
        ▼
 Pasada 1: registro de firmas ─► TablaSimbolosGlobal
        │
        ▼
 Pasada 2: análisis semántico + cuartetas ─► TablaCuartetas
        │
        ▼
 ModeloPrograma (funciones, objetos, structs, llamadas)
     ├─► GeneradorRiscV ─► .asm ─► InterpreteRiscV (embebido)
     └─► GeneradorCEtiquetas ─► .c (solo etiquetas y goto)
```

Los objetos y structs se guardan en el heap como bloques de slots de 8 bytes
(slot 0 = identificador de clase), con despacho virtual para el polimorfismo.

## Estructura del proyecto

```
compilador2-proyecto1/
├── pom.xml
├── src/main/antlr4/com/company/
│   ├── y/ (YLexer.g4, YParser.g4)       # Y? (indentación significativa)
│   ├── zetariano/Zetariano.g4
│   └── pig/PigLatin.g4
├── src/main/java/com/company/
│   ├── y/ zetariano/ pig/               # registro de firmas, análisis y resolvedores por lenguaje
│   ├── semantico/                       # tabla de símbolos, tipos, visibilidad y errores
│   ├── ir/                              # cuartetas y formateador .c3d
│   ├── backend/                         # modelo del programa, generador RISC-V y generador C
│   ├── riscv/                           # intérprete RISC-V embebido
│   └── gui/                             # ventana, tema, resaltador, consola de ejecución
├── ejemplos/                            # programas de referencia
└── docs/                                # manual técnico y manual de usuario
```

## Documentación

- `docs/Manual_Tecnico_St4ck_Over-Pig.pdf`: arquitectura, cuartetas, modelo orientado a objetos, tabla de compatibilidad de tipos de los tres lenguajes, backends, tecnologías, palabras reservadas, símbolos y gramática.
- `docs/Manual_Usuario_St4ck_Over-Pig.pdf`: uso de la interfaz con capturas de cada funcionalidad.

## Decisiones y limitaciones

- Los structs y objetos se asignan y pasan **por referencia**.
- Los booleanos se imprimen como `1` y `0`.
- La lectura de cadenas toma una palabra (hasta el primer espacio) por línea.
- Un programa que no termina se corta a los 2 000 millones de instrucciones (también hay botón *Detener*).
- En Pig Latin, `##` abre un comentario de bloque que se cierra con otro `##`; para comentar una línea usa `//`.
- Con errores léxicos, sintácticos o semánticos no se genera código.
