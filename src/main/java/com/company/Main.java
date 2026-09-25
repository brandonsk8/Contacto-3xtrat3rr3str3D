package com.company;

import com.company.backend.GeneradorC;
import com.company.ir.TablaCuartetas;
import com.company.pig.ArchivoImportado;
import com.company.pig.PigLatinAnalizador;
import com.company.pig.PigLatinLexer;
import com.company.pig.PigLatinParser;
import com.company.pig.RegistradorFirmasPigLatin;
import com.company.semantico.CategoriaSimbolo;
import com.company.semantico.GestorErrores;
import com.company.semantico.SimboloClase;
import com.company.semantico.SimboloEstructura;
import com.company.semantico.SimboloInvocable;
import com.company.semantico.Simbolo;
import com.company.semantico.TablaSimbolos;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.semantico.Tipo;
import com.company.y.RegistradorFirmasY;
import com.company.y.YLexer;
import com.company.y.YParser;
import com.company.y.YVisitor;
import com.company.zetariano.RegistradorFirmasZetariano;
import com.company.zetariano.ZetarianoAnalizador;
import com.company.zetariano.ZetarianoLexer;
import com.company.zetariano.ZetarianoParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Suite de verificacion del pipeline completo (lexer/parser ANTLR4,
 * analisis semantico y generacion de codigo C) para los 3 lenguajes.
 * No es el punto de entrada de la aplicacion, eso es VentanaPrincipal.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        verificarMinimosVacios();
        verificarEjemplosPila();
        verificarTablaSimbolos();
        verificarVisitorY();
        verificarVisitorZetariano();
        verificarVisitorPigLatin();
        verificarBackendC();
        System.out.println("Todas las verificaciones pasaron correctamente.");
    }

    /** Programa "casi vacio" de cada lenguaje, para confirmar que el pipeline ANTLR4 esta bien conectado. */
    private static void verificarMinimosVacios() throws Exception {
        String yMinimo =
                "%funciones\n" +
                        "definir vacia():\n" +
                        "    retornar\n";
        YParser yParser = new YParser(new CommonTokenStream(new YLexer(CharStreams.fromString(yMinimo))));
        yParser.programa();
        verificarPrograma("Y? (minimo)", yParser);

        String zetarianoMinimo = "public class Vacia {\n}\n";
        ZetarianoParser zParser = new ZetarianoParser(new CommonTokenStream(new ZetarianoLexer(CharStreams.fromString(zetarianoMinimo))));
        zParser.programa();
        verificarPrograma("Zetariano (minimo)", zParser);

        String pigLatinMinimo = "MAIOR>\nFINIS;";
        PigLatinParser pParser = new PigLatinParser(new CommonTokenStream(new PigLatinLexer(CharStreams.fromString(pigLatinMinimo))));
        pParser.programa();
        verificarPrograma("Pig Latin (minimo)", pParser);
    }

    /**
     * Ejemplo real de referencia (una pila enlazada), con los 3
     * lenguajes trabajando juntos: ejemplos/Pila/main.pig importa
     * ejemplos/Pila/Nodo.z, ejemplos/Pila/Pila.z y
     * ejemplos/Pila/utils/utils.y. Asume el directorio raiz del
     * proyecto como working directory.
     */
    private static void verificarEjemplosPila() throws IOException {
        Path base = Paths.get("ejemplos", "Pila");

        PigLatinParser mainParser = new PigLatinParser(new CommonTokenStream(new PigLatinLexer(CharStreams.fromPath(base.resolve("main.pig")))));
        mainParser.programa();
        verificarPrograma("Pig Latin (ejemplos/Pila/main.pig)", mainParser);

        ZetarianoParser nodoParser = new ZetarianoParser(new CommonTokenStream(new ZetarianoLexer(CharStreams.fromPath(base.resolve("Nodo.z")))));
        nodoParser.programa();
        verificarPrograma("Zetariano (ejemplos/Pila/Nodo.z)", nodoParser);

        ZetarianoParser pilaParser = new ZetarianoParser(new CommonTokenStream(new ZetarianoLexer(CharStreams.fromPath(base.resolve("Pila.z")))));
        pilaParser.programa();
        verificarPrograma("Zetariano (ejemplos/Pila/Pila.z)", pilaParser);

        YParser utilsParser = new YParser(new CommonTokenStream(new YLexer(CharStreams.fromPath(base.resolve("utils/utils.y")))));
        utilsParser.programa();
        verificarPrograma("Y? (ejemplos/Pila/utils/utils.y)", utilsParser);
    }

    /**
     * Prueba de humo de com.company.semantico: arma a mano una
     * estructura Nodo auto-referenciada, una clase Pila con dos metodos
     * sobrecargados, y un ambito anidado con shadowing, sin depender de
     * ningun Visitor todavia.
     */
    private static void verificarTablaSimbolos() {
        TablaSimbolosGlobal global = new TablaSimbolosGlobal();

        // ---- estructura Nodo: valor entero, siguiente Nodo (auto-referencia) ----
        SimboloEstructura nodo = new SimboloEstructura("Nodo", 1, 1);
        verificar("Simbolos: agregar campo 'valor' a Nodo",
                nodo.agregarCampo(new Simbolo("valor", Tipo.ENTERO, CategoriaSimbolo.CAMPO, 2, 5)));
        verificar("Simbolos: agregar campo 'siguiente' (auto-referencia) a Nodo",
                nodo.agregarCampo(new Simbolo("siguiente", Tipo.estructura("Nodo"), CategoriaSimbolo.CAMPO, 3, 5)));
        verificar("Simbolos: rechazar campo 'valor' duplicado en Nodo",
                !nodo.agregarCampo(new Simbolo("valor", Tipo.ENTERO, CategoriaSimbolo.CAMPO, 4, 5)));
        verificar("Simbolos: registrar estructura Nodo en la tabla global",
                global.registrarEstructura(nodo));

        // ---- clase Pila: campo cima : Nodo, dos sobrecargas de apilar() ----
        SimboloClase pila = new SimboloClase("Pila", 10, 1);
        verificar("Simbolos: agregar campo 'cima' (tipo Nodo) a Pila",
                pila.agregarCampo(new Simbolo("cima", Tipo.estructura("Nodo"), CategoriaSimbolo.CAMPO, 11, 5)));

        List<Simbolo> parametrosEntero = Arrays.asList(
                new Simbolo("valor", Tipo.ENTERO, CategoriaSimbolo.PARAMETRO, 12, 20));
        SimboloInvocable apilarEntero = new SimboloInvocable(
                "apilar", CategoriaSimbolo.METODO, parametrosEntero, Tipo.VOID, 12, 5);
        verificar("Simbolos: agregar metodo apilar(entero)", pila.agregarMetodo(apilarEntero));

        List<Simbolo> parametrosNodo = Arrays.asList(
                new Simbolo("n", Tipo.estructura("Nodo"), CategoriaSimbolo.PARAMETRO, 13, 20));
        SimboloInvocable apilarNodo = new SimboloInvocable(
                "apilar", CategoriaSimbolo.METODO, parametrosNodo, Tipo.VOID, 13, 5);
        verificar("Simbolos: agregar sobrecarga apilar(Nodo) (firma distinta)",
                pila.agregarMetodo(apilarNodo));

        SimboloInvocable apilarEnteroRepetido = new SimboloInvocable(
                "apilar", CategoriaSimbolo.METODO, parametrosEntero, Tipo.VOID, 14, 5);
        verificar("Simbolos: rechazar apilar(entero) repetido (misma firma)",
                !pila.agregarMetodo(apilarEnteroRepetido));
        verificar("Simbolos: apilar() tiene 2 sobrecargas registradas",
                pila.buscarMetodos("apilar").size() == 2);

        verificar("Simbolos: registrar clase Pila en la tabla global",
                global.registrarClase(pila));
        verificar("Simbolos: rechazar un segundo tipo llamado 'Pila' (choca con la clase)",
                !global.registrarEstructura(new SimboloEstructura("Pila", 20, 1)));
        verificar("Simbolos: resolverTipo('Nodo') devuelve el tipo estructura correcto",
                Tipo.estructura("Nodo").equals(global.resolverTipo("Nodo")));
        verificar("Simbolos: resolverTipo('Inexistente') devuelve null",
                global.resolverTipo("Inexistente") == null);

        // ---- ambitos anidados: parametro 'valor' del metodo, tapado por una variable local 'valor' dentro de un bloque 'si' ----
        TablaSimbolos ambitoMetodo = new TablaSimbolos(null);
        verificar("Ambitos: declarar parametro 'valor' en el ambito del metodo",
                ambitoMetodo.declarar(new Simbolo("valor", Tipo.ENTERO, CategoriaSimbolo.PARAMETRO, 12, 20)));
        apilarEntero.setAmbitoLocal(ambitoMetodo);

        TablaSimbolos ambitoBloqueSi = new TablaSimbolos(ambitoMetodo);
        verificar("Ambitos: 'valor' es visible desde el bloque hijo (sube al padre)",
                ambitoBloqueSi.buscar("valor") != null);
        verificar("Ambitos: shadowing - declarar otra 'valor' (decimal) dentro del bloque hijo",
                ambitoBloqueSi.declarar(new Simbolo("valor", Tipo.DECIMAL, CategoriaSimbolo.VARIABLE, 15, 9)));
        verificar("Ambitos: dentro del bloque, 'valor' resuelve a la variable local (decimal), no al parametro",
                ambitoBloqueSi.buscar("valor").getTipo().equals(Tipo.DECIMAL));
        verificar("Ambitos: el ambito del metodo no ve la 'valor' declarada en el bloque hijo",
                ambitoMetodo.buscarLocal("valor").getTipo().equals(Tipo.ENTERO));
        verificar("Ambitos: rechazar redeclarar 'valor' dos veces en el mismo bloque",
                !ambitoBloqueSi.declarar(new Simbolo("valor", Tipo.CADENA, CategoriaSimbolo.VARIABLE, 16, 9)));
        verificar("Ambitos: buscar un nombre inexistente en toda la cadena devuelve null",
                ambitoBloqueSi.buscar("noExiste") == null);

        // ---- compatibilidad de tipos ----
        verificar("Tipos: entero es compatible (promocion) con decimal",
                Tipo.ENTERO.esCompatibleCon(Tipo.DECIMAL) || Tipo.DECIMAL.esCompatibleCon(Tipo.ENTERO));
        verificar("Tipos: cadena NO es compatible con entero",
                !Tipo.CADENA.esCompatibleCon(Tipo.ENTERO));
        verificar("Tipos: dos Tipo.estructura(\"Nodo\") son iguales (equals por valor, no por referencia)",
                Tipo.estructura("Nodo").equals(Tipo.estructura("Nodo")));
    }

    private static void verificar(String etiqueta, boolean condicion) {
        if (condicion) {
            System.out.println("OK   - " + etiqueta);
        } else {
            throw new AssertionError("FAIL - " + etiqueta);
        }
    }

    /**
     * Prueba de humo del RegistradorFirmasY + YVisitor (pasadas 1 y 2
     * del analizador semantico de Y?, con generacion de cuartetas en la
     * misma pasada). Dos casos: un programa armado a mano con
     * estructura + funcion + control de flujo que no deberia generar
     * errores, y el archivo real ejemplos/Pila/utils/utils.y, que
     * tampoco deberia generar ninguno.
     */
    private static void verificarVisitorY() throws IOException {
        String yFeliz =
                "%estructuras\n" +
                        "estructura Punto:\n" +
                        "    entero x\n" +
                        "    entero y\n" +
                        "%funciones\n" +
                        "definir sumar(entero a, entero b) -> entero:\n" +
                        "    retornar a + b\n" +
                        "definir procesar(entero limite):\n" +
                        "    entero total = 0\n" +
                        "    entero i = 0\n" +
                        "    mientras (i < limite) hacer\n" +
                        "        total = sumar(total, i)\n" +
                        "        i++\n" +
                        "    si (total > 100) entonces\n" +
                        "        imprimir(\"mucho\")\n" +
                        "    contrario\n" +
                        "        imprimir(\"poco\")\n";

        AnalisisY felizResultado = analizarY(yFeliz);
        verificar("YVisitor: el programa Y? de prueba no genera errores semanticos",
                !felizResultado.errores.tieneErrores());
        verificar("YVisitor: se generaron cuartetas para el programa de prueba",
                !felizResultado.cuartetas.getCuartetas().isEmpty());
        if (felizResultado.errores.tieneErrores()) {
            felizResultado.errores.imprimir();
        }

        AnalisisY utilsResultado = analizarY(Paths.get("ejemplos", "Pila", "utils", "utils.y"));
        verificar("YVisitor: ejemplos/Pila/utils/utils.y no genera errores semanticos (typo 'imprimit' ya corregido)",
                !utilsResultado.errores.tieneErrores());
        if (utilsResultado.errores.tieneErrores()) {
            utilsResultado.errores.imprimir();
        }
    }

    /**
     * Prueba de humo del RegistradorFirmasZetariano + ZetarianoAnalizador
     * sobre los 2 archivos .z reales de ejemplos/Pila: Nodo.z y Pila.z.
     * Los dos se registran en la MISMA TablaSimbolosGlobal (Nodo.z
     * primero), para que Pila.z pueda resolver el tipo 'Nodo' de sus
     * campos/parametros. Ninguno de los dos deberia generar errores.
     */
    private static void verificarVisitorZetariano() throws IOException {
        TablaSimbolosGlobal global = new TablaSimbolosGlobal();
        GestorErrores errores = new GestorErrores();
        TablaCuartetas cuartetas = new TablaCuartetas();
        Path base = Paths.get("ejemplos", "Pila");

        int erroresAntesDeNodo = errores.getErrores().size();
        analizarZetariano(base.resolve("Nodo.z"), global, errores, cuartetas);
        verificar("ZetarianoAnalizador: Nodo.z no genera errores semanticos",
                errores.getErrores().size() == erroresAntesDeNodo);

        int erroresAntesDePila = errores.getErrores().size();
        analizarZetariano(base.resolve("Pila.z"), global, errores, cuartetas);
        List<String> erroresDePila = errores.getErrores().subList(erroresAntesDePila, errores.getErrores().size())
                .stream().map(e -> e.getMensaje()).collect(java.util.stream.Collectors.toList());

        verificar("ZetarianoAnalizador: Pila.z no genera ningun error (el 'return null;' de obtenerCima() ya se corrigio a 'return -1;')",
                erroresDePila.isEmpty());
        // confirma tambien que la concatenacion con '+' en toString() no genera errores falsos
        verificar("ZetarianoAnalizador: se generaron cuartetas para Nodo.z + Pila.z",
                !cuartetas.getCuartetas().isEmpty());

        if (!erroresDePila.isEmpty()) {
            System.out.println("     (no se esperaba ningun error en Pila.z):");
            for (String m : erroresDePila) {
                System.out.println("     " + m);
            }
        }
    }

    private static void analizarZetariano(Path archivo, TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas) throws IOException {
        ZetarianoParser parser = new ZetarianoParser(new CommonTokenStream(new ZetarianoLexer(CharStreams.fromPath(archivo))));
        ZetarianoParser.ProgramaContext arbol = parser.programa();

        SimboloClase clase = new RegistradorFirmasZetariano(global, errores).registrar(arbol);
        new ZetarianoAnalizador(global, errores, cuartetas).analizarPrograma(arbol, clase);
    }

    /** Analogo a analizarY(String): corre el pipeline completo de Zetariano sobre UN programa suelto, en su propia TablaSimbolosGlobal. */
    private static AnalisisY analizarZetariano(String codigoFuente) {
        TablaSimbolosGlobal global = new TablaSimbolosGlobal();
        GestorErrores errores = new GestorErrores();
        TablaCuartetas cuartetas = new TablaCuartetas();

        ZetarianoParser parser = new ZetarianoParser(new CommonTokenStream(new ZetarianoLexer(CharStreams.fromString(codigoFuente))));
        ZetarianoParser.ProgramaContext arbol = parser.programa();

        SimboloClase clase = new RegistradorFirmasZetariano(global, errores).registrar(arbol);
        new ZetarianoAnalizador(global, errores, cuartetas).analizarPrograma(arbol, clase);

        return new AnalisisY(global, errores, cuartetas);
    }

    /**
     * Corre el pipeline completo de Pig Latin sobre el ejemplo real
     * ejemplos/Pila/main.pig: RegistradorFirmasPigLatin (pasada 1)
     * resuelve los 3 'import' (Nodo.z, Pila.z, utils.utils.y) contra la
     * MISMA TablaSimbolosGlobal, y PigLatinAnalizador (pasada 2) primero
     * genera las cuartetas del cuerpo de cada archivo importado y
     * despues analiza VARIABILES> + MAIOR> del propio main.pig. No se
     * espera ningun error semantico en todo el programa.
     */
    private static void verificarVisitorPigLatin() throws IOException {
        TablaSimbolosGlobal global = new TablaSimbolosGlobal();
        GestorErrores errores = new GestorErrores();
        TablaCuartetas cuartetas = new TablaCuartetas();
        Path base = Paths.get("ejemplos", "Pila");

        PigLatinParser parser = new PigLatinParser(new CommonTokenStream(new PigLatinLexer(CharStreams.fromPath(base.resolve("main.pig")))));
        PigLatinParser.ProgramaContext arbol = parser.programa();

        List<ArchivoImportado> importados = new RegistradorFirmasPigLatin(global, errores, base).registrar(arbol);
        new PigLatinAnalizador(global, errores, cuartetas).analizarPrograma(arbol, importados);

        List<String> mensajes = errores.getErrores().stream().map(e -> e.getMensaje()).collect(java.util.stream.Collectors.toList());

        verificar("PigLatinAnalizador: se resolvieron los 3 imports de main.pig (Nodo.z, Pila.z, utils.utils.y)",
                importados.size() == 3);
        verificar("PigLatinAnalizador: main.pig completo no genera ningun error (utils.y y Pila.z ya estan corregidos)",
                mensajes.isEmpty());
        verificar("PigLatinAnalizador: se generaron cuartetas para el programa completo (imports + main)",
                !cuartetas.getCuartetas().isEmpty());

        if (!mensajes.isEmpty()) {
            System.out.println("     (no se esperaba ningun error en main.pig):");
            for (String m : mensajes) {
                System.out.println("     " + m);
            }
        }
    }

    /**
     * Prueba de humo de GeneradorC: reusa el "programa Y? de prueba" de
     * verificarVisitorY (sumar + procesar, tipos primitivos, control de
     * flujo, imprimir, y una llamada entre ambas) y confirma que las dos
     * funciones se traducen a C.
     */
    private static void verificarBackendC() throws IOException {
        String yFeliz =
                "%estructuras\n" +
                        "estructura Punto:\n" +
                        "    entero x\n" +
                        "    entero y\n" +
                        "%funciones\n" +
                        "definir sumar(entero a, entero b) -> entero:\n" +
                        "    retornar a + b\n" +
                        "definir procesar(entero limite):\n" +
                        "    entero total = 0\n" +
                        "    entero i = 0\n" +
                        "    mientras (i < limite) hacer\n" +
                        "        total = sumar(total, i)\n" +
                        "        i++\n" +
                        "    si (total > 100) entonces\n" +
                        "        imprimir(\"mucho\")\n" +
                        "    contrario\n" +
                        "        imprimir(\"poco\")\n";

        AnalisisY resultado = analizarY(yFeliz);
        verificar("GeneradorC: el programa de prueba no tiene errores semanticos antes de generar C",
                !resultado.errores.tieneErrores());

        GeneradorC generador = new GeneradorC(resultado.global);
        String codigoC = generador.generar("yFeliz", resultado.cuartetas);

        verificar("GeneradorC: 'sumar' (100% primitiva) SI se tradujo a C",
                codigoC.contains("int sumar(int a, int b)"));
        verificar("GeneradorC: 'procesar' (llama a 'sumar', tambien 100% primitiva) SI se tradujo a C",
                codigoC.contains("void procesar(int limite)"));
        verificar("GeneradorC: ninguna funcion se omitio (las 2 son primitivas, no deberia haber notas)",
                generador.getNotas().isEmpty());

        Path salida = Paths.get("salida", "backend", "yFeliz.c");
        Files.createDirectories(salida.getParent());
        Files.writeString(salida, codigoC);
        System.out.println("     .c generado en: " + salida.toAbsolutePath());
        if (!generador.getNotas().isEmpty()) {
            System.out.println("     Notas del generador (funciones omitidas):");
            for (String nota : generador.getNotas()) {
                System.out.println("     - " + nota);
            }
        }

        verificarBackendCEstructuras();
    }

    /**
     * Prueba de humo de GeneradorC con una estructura "plana" (todos sus
     * campos primitivos) como tipo de parametro/retorno/variable local:
     * una funcion recibe dos Puntos por valor, arma un tercero sumando
     * campo a campo, y lo retorna.
     */
    private static void verificarBackendCEstructuras() throws IOException {
        String yConEstructuras =
                "%estructuras\n" +
                        "estructura Punto:\n" +
                        "    entero x\n" +
                        "    entero y\n" +
                        "%funciones\n" +
                        "definir sumarPuntos(Punto a, Punto b) -> Punto:\n" +
                        "    Punto resultado\n" +
                        "    resultado.x = a.x + b.x\n" +
                        "    resultado.y = a.y + b.y\n" +
                        "    retornar resultado\n";

        AnalisisY resultado = analizarY(yConEstructuras);
        verificar("GeneradorC: el programa con la estructura 'Punto' no tiene errores semanticos",
                !resultado.errores.tieneErrores());

        GeneradorC generador = new GeneradorC(resultado.global);
        String codigoC = generador.generar("puntos", resultado.cuartetas);

        verificar("GeneradorC: se genero 'struct Punto { int x; int y; };'",
                codigoC.contains("struct Punto {") && codigoC.contains("int x;") && codigoC.contains("int y;"));
        verificar("GeneradorC: 'sumarPuntos' (parametros/retorno de tipo estructura plana) SI se tradujo a C",
                codigoC.contains("struct Punto sumarPuntos(struct Punto a, struct Punto b)"));
        verificar("GeneradorC: el acceso a campo 'resultado.x'/'a.x' se tradujo tal cual (valido en C para un struct por valor)",
                codigoC.contains("resultado.x = ") && codigoC.contains("a.x + b.x"));
        verificar("GeneradorC: ninguna funcion/estructura se omitio (Punto es plana, no deberia haber notas)",
                generador.getNotas().isEmpty());

        Path salida = Paths.get("salida", "backend", "puntos.c");
        Files.writeString(salida, codigoC);
        System.out.println("     .c generado en: " + salida.toAbsolutePath());

        verificarBackendCEstructurasAnidadas();
    }

    /**
     * Prueba de humo de GeneradorC con una estructura auto-referenciada
     * ('Nodo.siguiente : Nodo'). Confirma que 'siguiente' se traduce
     * como 'struct Nodo*' (puntero, un struct no puede contenerse a si
     * mismo) y que 'n.siguiente.valor' se traduce a 'n.siguiente->valor'.
     */
    private static void verificarBackendCEstructurasAnidadas() throws IOException {
        String yAnidado =
                "%estructuras\n" +
                        "estructura Nodo:\n" +
                        "    entero valor\n" +
                        "    Nodo siguiente\n" +
                        "%funciones\n" +
                        "definir sumarCadena(Nodo n) -> entero:\n" +
                        "    entero total\n" +
                        "    total = n.valor\n" +
                        "    total = total + n.siguiente.valor\n" +
                        "    retornar total\n";

        AnalisisY resultado = analizarY(yAnidado);
        verificar("GeneradorC: el programa con la estructura auto-referenciada 'Nodo' no tiene errores semanticos",
                !resultado.errores.tieneErrores());

        GeneradorC generador = new GeneradorC(resultado.global);
        String codigoC = generador.generar("nodo-anidado", resultado.cuartetas);

        verificar("GeneradorC: el campo 'siguiente' (tipo Nodo, auto-referenciado) se tradujo como puntero 'struct Nodo*'",
                codigoC.contains("struct Nodo* siguiente;"));
        verificar("GeneradorC: el acceso encadenado 'n.siguiente.valor' se tradujo con '->' automatico ('n.siguiente->valor')",
                codigoC.contains("n.siguiente->valor"));
        verificar("GeneradorC: ninguna funcion/estructura se omitio",
                generador.getNotas().isEmpty());

        Path salida = Paths.get("salida", "backend", "nodo.c");
        Files.writeString(salida, codigoC);
        System.out.println("     .c generado en: " + salida.toAbsolutePath());

        verificarBackendCObjetos();
    }

    /**
     * Prueba de humo de GeneradorC con clases/objetos de Zetariano:
     * constructor con 'this' implicito, 'new', metodos que leen/escriben
     * campos via 'this', un campo auto-referenciado, y llamadas a metodo
     * explicitas-encadenadas e implicitas. Usa una clase de prueba propia
     * ('Contador') que ejercita los mismos mecanismos que Nodo.z/Pila.z.
     */
    private static void verificarBackendCObjetos() throws IOException {
        String zetarianoContador =
                "public class Contador {\n" +
                        "    int valor;\n" +
                        "    Contador siguiente;\n" +
                        "\n" +
                        "    public Contador(int inicial) {\n" +
                        "        this.valor = inicial;\n" +
                        "    }\n" +
                        "\n" +
                        "    public void incrementar() {\n" +
                        "        this.valor = this.valor + 1;\n" +
                        "    }\n" +
                        "\n" +
                        "    public void incrementarDosVeces() {\n" +
                        "        incrementar();\n" +
                        "        incrementar();\n" +
                        "    }\n" +
                        "\n" +
                        "    public int obtenerValor() {\n" +
                        "        return this.valor;\n" +
                        "    }\n" +
                        "\n" +
                        "    public void enlazarCon(Contador otro) {\n" +
                        "        this.siguiente = otro;\n" +
                        "    }\n" +
                        "\n" +
                        "    public int valorDelSiguiente() {\n" +
                        "        return this.siguiente.valor;\n" +
                        "    }\n" +
                        "\n" +
                        "    public int valorEnlazado() {\n" +
                        "        return this.siguiente.obtenerValor();\n" +
                        "    }\n" +
                        "\n" +
                        "    public Contador crearVinculado(int v) {\n" +
                        "        return new Contador(v);\n" +
                        "    }\n" +
                        "}\n";

        AnalisisY resultado = analizarZetariano(zetarianoContador);
        verificar("GeneradorC: la clase 'Contador' de prueba no tiene errores semanticos",
                !resultado.errores.tieneErrores());

        GeneradorC generador = new GeneradorC(resultado.global);
        String codigoC = generador.generar("contador", resultado.cuartetas);

        verificar("GeneradorC: se genero 'struct Contador { int valor; struct Contador* siguiente; };'",
                codigoC.contains("struct Contador {") && codigoC.contains("int valor;") && codigoC.contains("struct Contador* siguiente;"));
        verificar("GeneradorC: el constructor se tradujo a 'struct Contador* Contador_crear_1(int inicial)' con calloc + 'this->valor = inicial;'",
                codigoC.contains("struct Contador* Contador_crear_1(int inicial)")
                        && codigoC.contains("calloc(1, sizeof(struct Contador))")
                        && codigoC.contains("this->valor = inicial;")
                        && codigoC.contains("return this;"));
        verificar("GeneradorC: 'incrementar()' lee/muta 'this->valor' via puntero (la lectura 'this->valor + 1' pasa por un temporal antes de reasignarse, como cualquier otra expresion)",
                codigoC.contains("void Contador_incrementar_0(struct Contador* this)")
                        && codigoC.contains("this->valor + 1;"));
        verificar("GeneradorC: la llamada IMPLICITA 'incrementar()' (== 'this.incrementar()') se tradujo a 'Contador_incrementar_0(this);'",
                codigoC.contains("void Contador_incrementarDosVeces_0(struct Contador* this)")
                        && codigoC.contains("Contador_incrementar_0(this);"));
        verificar("GeneradorC: 'obtenerValor()' retorna 'this->valor'",
                codigoC.contains("int Contador_obtenerValor_0(struct Contador* this)")
                        && codigoC.contains("return this->valor;"));
        verificar("GeneradorC: 'enlazarCon(Contador otro)' asigna el puntero sin '&' extra (ambos lados ya son punteros)",
                codigoC.contains("void Contador_enlazarCon_1(struct Contador* this, struct Contador* otro)")
                        && codigoC.contains("this->siguiente = otro;"));
        verificar("GeneradorC: el acceso encadenado 'this.siguiente.valor' se tradujo con '->' en los dos pasos",
                codigoC.contains("return this->siguiente->valor;"));
        verificar("GeneradorC: la llamada ENCADENADA 'this.siguiente.obtenerValor()' se tradujo a 'Contador_obtenerValor_0(this->siguiente)' (el resultado pasa por un temporal antes del 'return', como cualquier otra llamada)",
                codigoC.contains("Contador_obtenerValor_0(this->siguiente)"));
        verificar("GeneradorC: 'new Contador(v)' se tradujo a una llamada a 'Contador_crear_1(v)'",
                codigoC.contains("Contador_crear_1(v)") && codigoC.contains("return t"));
        verificar("GeneradorC: ninguna funcion/constructor/metodo se omitio (Contador es 100% soportada, no deberia haber notas)",
                generador.getNotas().isEmpty());

        Path salida = Paths.get("salida", "backend", "contador.c");
        Files.writeString(salida, codigoC);
        System.out.println("     .c generado en: " + salida.toAbsolutePath());
        if (!generador.getNotas().isEmpty()) {
            System.out.println("     Notas del generador (funciones/constructores/metodos omitidos):");
            for (String nota : generador.getNotas()) {
                System.out.println("     - " + nota);
            }
        }

        verificarBackendCCadenas();
    }

    /**
     * Prueba de humo de GeneradorC con tipo CADENA y 'concat'. Usa una
     * clase de prueba propia ('Mensaje') que ejercita: un campo CADENA,
     * concatenar CADENA + ENTERO con '+', concatenar CADENA + CADENA con
     * '+=', retornar una CADENA, y comparar dos CADENA con '==' (debe
     * comparar contenido via 'strcmp', no punteros).
     */
    private static void verificarBackendCCadenas() throws IOException {
        String zetarianoMensaje =
                "public class Mensaje {\n" +
                        "    String texto;\n" +
                        "\n" +
                        "    public Mensaje(String inicial) {\n" +
                        "        this.texto = inicial;\n" +
                        "    }\n" +
                        "\n" +
                        "    public void agregarEntero(int valor) {\n" +
                        "        this.texto = this.texto + valor;\n" +
                        "    }\n" +
                        "\n" +
                        "    public void agregarTexto(String extra) {\n" +
                        "        this.texto += extra;\n" +
                        "    }\n" +
                        "\n" +
                        "    public String obtenerTexto() {\n" +
                        "        return this.texto;\n" +
                        "    }\n" +
                        "\n" +
                        "    public boolean esIgualA(String otro) {\n" +
                        "        return this.texto == otro;\n" +
                        "    }\n" +
                        "}\n";

        AnalisisY resultado = analizarZetariano(zetarianoMensaje);
        verificar("GeneradorC: la clase 'Mensaje' de prueba no tiene errores semanticos",
                !resultado.errores.tieneErrores());

        GeneradorC generador = new GeneradorC(resultado.global);
        String codigoC = generador.generar("mensaje", resultado.cuartetas);

        verificar("GeneradorC: se genero 'struct Mensaje { char* texto; };' y el constructor recibe 'char* inicial'",
                codigoC.contains("struct Mensaje {") && codigoC.contains("char* texto;")
                        && codigoC.contains("struct Mensaje* Mensaje_crear_1(char* inicial)"));
        verificar("GeneradorC: 'this.texto = this.texto + valor;' (CADENA + ENTERO) convierte el entero con 'c3d_entero_a_cadena' antes de concatenar",
                codigoC.contains("c3d_concat(this->texto, c3d_entero_a_cadena(valor))"));
        verificar("GeneradorC: 'this.texto += extra;' (CADENA + CADENA) concatena directo, sin conversion",
                codigoC.contains("c3d_concat(this->texto, extra)"));
        verificar("GeneradorC: 'obtenerTexto()' retorna 'char*'",
                codigoC.contains("char* Mensaje_obtenerTexto_0(struct Mensaje* this)"));
        verificar("GeneradorC: 'this.texto == otro' (dos CADENA) se tradujo con 'strcmp(...) == 0', no comparando punteros",
                codigoC.contains("strcmp(this->texto, otro) == 0"));
        verificar("GeneradorC: solo se agregaron al encabezado los helpers de cadena REALMENTE usados (concat + entero_a_cadena, NO decimal/caracter - evita 'gcc -Wall' con funciones sin usar)",
                codigoC.contains("c3d_concat") && codigoC.contains("c3d_entero_a_cadena")
                        && !codigoC.contains("c3d_decimal_a_cadena") && !codigoC.contains("c3d_caracter_a_cadena"));
        verificar("GeneradorC: ninguna funcion/constructor/metodo se omitio (Mensaje es 100% soportada, no deberia haber notas)",
                generador.getNotas().isEmpty());

        Path salida = Paths.get("salida", "backend", "mensaje.c");
        Files.writeString(salida, codigoC);
        System.out.println("     .c generado en: " + salida.toAbsolutePath());
        if (!generador.getNotas().isEmpty()) {
            System.out.println("     Notas del generador (funciones/constructores/metodos omitidos):");
            for (String nota : generador.getNotas()) {
                System.out.println("     - " + nota);
            }
        }
    }

    private static AnalisisY analizarY(String codigoFuente) {
        YParser parser = new YParser(new CommonTokenStream(new YLexer(CharStreams.fromString(codigoFuente))));
        return analizarY(parser);
    }

    private static AnalisisY analizarY(Path archivo) throws IOException {
        YParser parser = new YParser(new CommonTokenStream(new YLexer(CharStreams.fromPath(archivo))));
        return analizarY(parser);
    }

    private static AnalisisY analizarY(YParser parser) {
        YParser.ProgramaContext arbol = parser.programa();

        TablaSimbolosGlobal global = new TablaSimbolosGlobal();
        GestorErrores errores = new GestorErrores();
        TablaCuartetas cuartetas = new TablaCuartetas();

        new RegistradorFirmasY(global, errores).registrar(arbol);
        new YVisitor(global, errores, cuartetas).analizarPrograma(arbol);

        return new AnalisisY(global, errores, cuartetas);
    }

    /** Agrupa el resultado de correr el RegistradorFirmasY + YVisitor sobre un programa Y?. */
    private static class AnalisisY {
        final TablaSimbolosGlobal global;
        final GestorErrores errores;
        final TablaCuartetas cuartetas;

        AnalisisY(TablaSimbolosGlobal global, GestorErrores errores, TablaCuartetas cuartetas) {
            this.global = global;
            this.errores = errores;
            this.cuartetas = cuartetas;
        }
    }

    /**
     * Reporta cuantos errores de sintaxis encontro ANTLR al parsear (ya
     * se imprimen solos via su listener por defecto). El parser dado
     * debe haber corrido ya su regla inicial "programa".
     */
    private static void verificarPrograma(String etiqueta, Parser parser) {
        int errores = parser.getNumberOfSyntaxErrors();
        if (errores == 0) {
            System.out.println("OK   - " + etiqueta);
        } else {
            System.out.println("FAIL - " + etiqueta + " (" + errores + " error(es) de sintaxis, ver arriba)");
        }
    }
}