package com.company.gui;

import com.company.backend.GeneradorC;
import com.company.ir.TablaCuartetas;
import com.company.pig.ArchivoImportado;
import com.company.pig.PigLatinAnalizador;
import com.company.pig.PigLatinLexer;
import com.company.pig.PigLatinParser;
import com.company.pig.RegistradorFirmasPigLatin;
import com.company.semantico.ErrorSemantico;
import com.company.semantico.GestorErrores;
import com.company.semantico.SimboloClase;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.y.RegistradorFirmasY;
import com.company.y.YLexer;
import com.company.y.YParser;
import com.company.y.YVisitor;
import com.company.zetariano.RegistradorFirmasZetariano;
import com.company.zetariano.ZetarianoAnalizador;
import com.company.zetariano.ZetarianoLexer;
import com.company.zetariano.ZetarianoParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Corre el pipeline completo (lexer -> parser -> analisis semantico ->
 * cuartetas -> GeneradorC) sobre el texto que el usuario tiene abierto en
 * el editor de VentanaPrincipal, sin releer el archivo de disco, asi que
 * "Correr" siempre refleja lo que se ve en pantalla, este guardado o no.
 *
 * El unico dato que si hace falta tomar del disco es, para un .pig, el
 * directorio donde vive, para resolver sus 'import' contra archivos
 * .y/.z vecinos via RegistradorFirmasPigLatin.
 */
public class EjecutorPipeline {

    /**
     * @param codigoFuente   el texto actual del editor (puede no estar guardado en disco todavia).
     * @param archivoAbierto la ruta del archivo abierto - se usa para saber la extension
     *                       (.y/.z/.pig) y, solo para un .pig, el directorio base desde
     *                       donde resolver sus imports.
     * @param nombrePrograma nombre para el encabezado del .c generado (normalmente el
     *                       nombre del archivo sin extension).
     * @return un reporte de texto plano listo para mostrar en la consola de la GUI.
     */
    public Resultado correr(String codigoFuente, Path archivoAbierto, String nombrePrograma) {
        String extension = extensionDe(archivoAbierto);
        switch (extension) {
            case "y":
                return correrY(codigoFuente, nombrePrograma);
            case "z":
                return correrZetariano(codigoFuente, nombrePrograma);
            case "pig":
                return correrPigLatin(codigoFuente, archivoAbierto, nombrePrograma);
            default:
                return new Resultado("No se reconoce la extension '" + extension + "' (se esperaba .y, .z o .pig).", null, null);
        }
    }

    private Resultado correrY(String codigoFuente, String nombrePrograma) {
        ColectorErroresSintaxis colector = new ColectorErroresSintaxis();
        YLexer lexer = new YLexer(CharStreams.fromString(codigoFuente));
        lexer.removeErrorListeners();
        lexer.addErrorListener(colector);
        YParser parser = new YParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(colector);
        YParser.ProgramaContext arbol = parser.programa();

        TablaSimbolosGlobal global = new TablaSimbolosGlobal();
        GestorErrores errores = new GestorErrores();
        TablaCuartetas cuartetas = new TablaCuartetas();

        new RegistradorFirmasY(global, errores).registrar(arbol);
        new YVisitor(global, errores, cuartetas).analizarPrograma(arbol);

        return armarReporte("Y?", nombrePrograma, colector.errores, errores, global, cuartetas);
    }

    private Resultado correrZetariano(String codigoFuente, String nombrePrograma) {
        ColectorErroresSintaxis colector = new ColectorErroresSintaxis();
        ZetarianoLexer lexer = new ZetarianoLexer(CharStreams.fromString(codigoFuente));
        lexer.removeErrorListeners();
        lexer.addErrorListener(colector);
        ZetarianoParser parser = new ZetarianoParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(colector);
        ZetarianoParser.ProgramaContext arbol = parser.programa();

        TablaSimbolosGlobal global = new TablaSimbolosGlobal();
        GestorErrores errores = new GestorErrores();
        TablaCuartetas cuartetas = new TablaCuartetas();

        SimboloClase clase = new RegistradorFirmasZetariano(global, errores).registrar(arbol);
        new ZetarianoAnalizador(global, errores, cuartetas).analizarPrograma(arbol, clase);

        return armarReporte("Zetariano", nombrePrograma, colector.errores, errores, global, cuartetas);
    }

    private Resultado correrPigLatin(String codigoFuente, Path archivoAbierto, String nombrePrograma) {
        ColectorErroresSintaxis colector = new ColectorErroresSintaxis();
        PigLatinLexer lexer = new PigLatinLexer(CharStreams.fromString(codigoFuente));
        lexer.removeErrorListeners();
        lexer.addErrorListener(colector);
        PigLatinParser parser = new PigLatinParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(colector);
        PigLatinParser.ProgramaContext arbol = parser.programa();

        TablaSimbolosGlobal global = new TablaSimbolosGlobal();
        GestorErrores errores = new GestorErrores();
        TablaCuartetas cuartetas = new TablaCuartetas();

        Path base = archivoAbierto.toAbsolutePath().getParent();
        List<ArchivoImportado> importados;
        try {
            importados = new RegistradorFirmasPigLatin(global, errores, base).registrar(arbol);
        } catch (IOException ex) {
            return new Resultado("No se pudieron resolver los imports de '" + nombrePrograma + ".pig':\n" + ex.getMessage()
                    + "\n(revisa que los archivos .y/.z importados existan junto a este .pig y esten guardados)", null, global);
        }
        new PigLatinAnalizador(global, errores, cuartetas).analizarPrograma(arbol, importados);

        String encabezado = "Imports resueltos: " + importados.size() + "\n";
        Resultado resultado = armarReporte("Pig Latin", nombrePrograma, colector.errores, errores, global, cuartetas);
        return new Resultado(encabezado + resultado.reporte, resultado.codigoC, global);
    }

    private Resultado armarReporte(String lenguaje, String nombrePrograma, List<String> erroresSintaxis, GestorErrores errores,
                                   TablaSimbolosGlobal global, TablaCuartetas cuartetas) {
        StringBuilder r = new StringBuilder();
        r.append("=== ").append(lenguaje).append(" - ").append(nombrePrograma).append(" ===\n");

        if (!erroresSintaxis.isEmpty()) {
            r.append("ADVERTENCIA: ").append(erroresSintaxis.size()).append(" error(es) de sintaxis:\n");
            for (String e : erroresSintaxis) {
                r.append("  - ").append(e).append("\n");
            }
            r.append("\n");
        }

        if (errores.tieneErrores()) {
            r.append(errores.getErrores().size()).append(" error(es) semantico(s):\n");
            for (ErrorSemantico e : errores.getErrores()) {
                r.append("  - ").append(e).append("\n");
            }
            r.append("\nNo se genera codigo C mientras haya errores semanticos.\n");
            return new Resultado(r.toString(), null, global);
        }

        r.append("Sin errores semanticos.\n");
        r.append("Cuartetas generadas: ").append(cuartetas.getCuartetas().size()).append("\n\n");

        GeneradorC generador = new GeneradorC(global);
        String codigoC = generador.generar(nombrePrograma, cuartetas);

        r.append("--- Codigo C generado ---\n");
        r.append(codigoC).append("\n");

        if (!generador.getNotas().isEmpty()) {
            r.append("--- Notas del generador (partes omitidas del .c y por que) ---\n");
            for (String nota : generador.getNotas()) {
                r.append("  - ").append(nota).append("\n");
            }
        }
        return new Resultado(r.toString(), codigoC, global);
    }

    /**
     * Reporte de texto para la consola, el codigo C generado por separado
     * (null si no se llego a generar) y la tabla de simbolos global de esa
     * corrida (null solo si ni siquiera se pudo armar, ej. extension no
     * reconocida), para poder mostrarla aparte en la GUI.
     */
    public static final class Resultado {
        public final String reporte;
        public final String codigoC;
        public final TablaSimbolosGlobal tablaSimbolos;

        Resultado(String reporte, String codigoC, TablaSimbolosGlobal tablaSimbolos) {
            this.reporte = reporte;
            this.codigoC = codigoC;
            this.tablaSimbolos = tablaSimbolos;
        }
    }

    private String extensionDe(Path archivo) {
        String nombre = archivo.getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return punto >= 0 ? nombre.substring(punto + 1).toLowerCase() : "";
    }

    /**
     * Reemplaza el listener por defecto de ANTLR (que solo imprime por
     * stderr - la terminal/consola de IntelliJ, invisible desde la GUI) y
     * junta cada error de sintaxis como texto "linea L:C - mensaje", igual
     * de formato que ErrorSemantico.toString(), para poder mostrarlos en
     * el panel de la GUI. Se engancha tanto al lexer como al parser, asi
     * que cubre errores lexicos y sintacticos por igual.
     */
    private static class ColectorErroresSintaxis extends BaseErrorListener {
        private final List<String> errores = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int linea,
                                int columna, String mensaje, RecognitionException excepcion) {
            errores.add("linea " + linea + ":" + columna + " - " + mensaje);
        }
    }
}