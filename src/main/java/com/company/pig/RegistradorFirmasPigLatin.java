package com.company.pig;

import com.company.semantico.GestorErrores;
import com.company.semantico.SimboloClase;
import com.company.semantico.TablaSimbolosGlobal;
import com.company.y.RegistradorFirmasY;
import com.company.y.YLexer;
import com.company.y.YParser;
import com.company.zetariano.RegistradorFirmasZetariano;
import com.company.zetariano.ZetarianoLexer;
import com.company.zetariano.ZetarianoParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pasada 1 de Pig Latin: un .pig no define tipos ni funciones propias,
 * solo IMPORTA archivos .y/.z. Por cada 'import', parsea ese archivo y
 * corre su propia pasada 1 (RegistradorFirmasY o RegistradorFirmasZetariano)
 * contra la MISMA TablaSimbolosGlobal, para que cualquier import pueda
 * referenciar un tipo o funcion de otro.
 *
 * Convencion de 'ruta': el ultimo segmento es la extension ('y'/'z'), el
 * anterior el nombre de archivo, y el resto subcarpetas. Ej. 'Nodo.z' ->
 * "Nodo.z" junto al .pig; 'utils.utils.y' -> "utils/utils.y".
 *
 * Guarda el arbol de cada import en un ArchivoImportado, porque
 * PigLatinAnalizador (pasada 2) necesita recorrerlo sin volver a parsear.
 *
 * Limitacion conocida: los imports se procesan en el orden del .pig, uno
 * a la vez - un import que use un tipo de otro import posterior en la
 * lista reportaria "tipo desconocido".
 */
public class RegistradorFirmasPigLatin {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final Path directorioBase;

    // Resuelve los import de Pig Latin y registra las firmas importadas.
    public RegistradorFirmasPigLatin(TablaSimbolosGlobal global, GestorErrores errores, Path directorioBase) {
        this.global = global;
        this.errores = errores;
        this.directorioBase = directorioBase;
    }

    // Procesa los import y registra clases y funciones.
    public List<ArchivoImportado> registrar(PigLatinParser.ProgramaContext ctx) throws IOException {
        List<ArchivoImportado> archivos = new ArrayList<>();
        if (ctx.listaImportaciones() == null) {
            return archivos;
        }
        // Etapa 1: leer todos los archivos y declarar el NOMBRE de cada clase,
        // para que el orden de los 'import' no importe.
        for (PigLatinParser.ImportacionContext imp : ctx.listaImportaciones().importacion()) {
            ArchivoImportado archivo = procesarImportacion(imp);
            if (archivo != null) {
                archivos.add(archivo);
            }
        }
        // Etapa 2: firmas. Las clases se registran con su superclase primero
        // (para validar '@Override'); las estructuras/funciones de Y? en el orden dado.
        List<ArchivoImportado> pendientes = new ArrayList<>(archivos);
        for (ArchivoImportado a : archivos) {
            if (!a.esZetariano()) {
                errores.setArchivoActual(a.getNombreArchivo());
                new RegistradorFirmasY(global, errores).registrar(a.getArbolY());
                errores.setArchivoActual(null);
                pendientes.remove(a);
            }
        }
        while (!pendientes.isEmpty()) {
            ArchivoImportado elegido = null;
            for (ArchivoImportado a : pendientes) {
                String sup = a.getClase().getNombreSuperclase();
                boolean padrePendiente = false;
                for (ArchivoImportado otro : pendientes) {
                    if (otro != a && sup != null && otro.getClase().getNombre().equals(sup)) {
                        padrePendiente = true;
                    }
                }
                if (!padrePendiente) {
                    elegido = a;
                    break;
                }
            }
            if (elegido == null) {
                elegido = pendientes.get(0); // herencia circular: se registra igual; el ciclo se reporta al usar la clase
            }
            errores.setArchivoActual(elegido.getNombreArchivo());
            new RegistradorFirmasZetariano(global, errores).registrarMiembros(elegido.getArbolZetariano(), elegido.getClase());
            errores.setArchivoActual(null);
            pendientes.remove(elegido);
        }
        return archivos;
    }

    // Lee un import (.y/.z), valida su sintaxis y fija su paquete.
    private ArchivoImportado procesarImportacion(PigLatinParser.ImportacionContext ctx) throws IOException {
        List<String> segmentos = new ArrayList<>();
        for (TerminalNode id : ctx.ruta().IDENTIFICADOR()) {
            segmentos.add(id.getText());
        }
        int linea = ctx.getStart().getLine();
        int columna = ctx.getStart().getCharPositionInLine();

        if (segmentos.size() < 2) {
            errores.reportar(linea, columna, "import invalido: '" + ctx.ruta().getText() + "' (se esperaba algo como 'Nodo.z' o 'utils.utils.y')");
            return null;
        }

        String extension = segmentos.get(segmentos.size() - 1);
        String nombreArchivo = segmentos.get(segmentos.size() - 2);
        Path directorio = directorioBase;
        for (int i = 0; i < segmentos.size() - 2; i++) {
            directorio = directorio.resolve(segmentos.get(i));
        }
        Path archivo = directorio.resolve(nombreArchivo + "." + extension);
        if (!java.nio.file.Files.exists(archivo)) {
            // Archivos con puntos en el nombre (ej. 'Unidad.java.z'): 'a.b.Unidad.java.z' puede ser
            // a/b/Unidad.java.z. Se prueba pegando los ultimos segmentos con '.' hasta encontrarlo.
            for (int corte = segmentos.size() - 3; corte >= 0; corte--) {
                Path dir = directorioBase;
                for (int i = 0; i < corte; i++) {
                    dir = dir.resolve(segmentos.get(i));
                }
                String nombre = String.join(".", segmentos.subList(corte, segmentos.size() - 1));
                Path candidato = dir.resolve(nombre + "." + extension);
                if (java.nio.file.Files.exists(candidato)) {
                    archivo = candidato;
                    nombreArchivo = nombre;
                    segmentos = new ArrayList<>(segmentos.subList(0, corte));
                    segmentos.add(nombre);
                    segmentos.add(extension);
                    break;
                }
            }
        }

        // Nombre para mostrar en errores (no la ruta real del disco), ej. "Nodo.z" o "utils/utils.y"
        List<String> partesNombreMostrado = new ArrayList<>(segmentos.subList(0, segmentos.size() - 2));
        partesNombreMostrado.add(nombreArchivo);
        String nombreMostrado = String.join("/", partesNombreMostrado) + "." + extension;

        if (extension.equals("y")) {
            YLexer lexerY = new YLexer(CharStreams.fromPath(archivo));
            YParser parser = new YParser(new CommonTokenStream(lexerY));
            ErroresSintaxisImport colectorY = new ErroresSintaxisImport();
            lexerY.removeErrorListeners();
            lexerY.addErrorListener(colectorY);
            parser.removeErrorListeners();
            parser.addErrorListener(colectorY);
            YParser.ProgramaContext arbol = parser.programa();
            if (colectorY.reportar(errores, nombreMostrado)) {
                return null; // con errores de sintaxis no se analiza el archivo
            }
            return ArchivoImportado.deY(arbol, nombreMostrado);
        }
        if (extension.equals("z")) {
            ZetarianoLexer lexerZ = new ZetarianoLexer(CharStreams.fromPath(archivo));
            ZetarianoParser parser = new ZetarianoParser(new CommonTokenStream(lexerZ));
            ErroresSintaxisImport colectorZ = new ErroresSintaxisImport();
            lexerZ.removeErrorListeners();
            lexerZ.addErrorListener(colectorZ);
            parser.removeErrorListeners();
            parser.addErrorListener(colectorZ);
            ZetarianoParser.ProgramaContext arbol = parser.programa();
            if (colectorZ.reportar(errores, nombreMostrado)) {
                return null;
            }
            errores.setArchivoActual(nombreMostrado);
            SimboloClase clase = new RegistradorFirmasZetariano(global, errores).declarar(arbol);
            errores.setArchivoActual(null);
            if (clase != null) {
                clase.setPaquete(String.join("/", segmentos.subList(0, segmentos.size() - 2)));
            }
            return ArchivoImportado.deZetariano(arbol, clase, nombreMostrado);
        }

        errores.reportar(linea, columna, "extension de import desconocida: '" + extension + "' (se esperaba .y o .z)");
        return null;
    }

    /** Junta los errores lexicos/sintacticos de un archivo importado para reportarlos con su nombre. */
    private static final class ErroresSintaxisImport extends org.antlr.v4.runtime.BaseErrorListener {
        private final List<String[]> items = new ArrayList<>();

        @Override
        public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer, Object offendingSymbol, int linea,
                                int columna, String mensaje, org.antlr.v4.runtime.RecognitionException e) {
            items.add(new String[]{String.valueOf(linea), String.valueOf(columna), mensaje});
        }

        /** @return true si hubo errores (ya reportados en 'errores'). */
        boolean reportar(GestorErrores errores, String archivo) {
            for (String[] it : items) {
                errores.setArchivoActual(archivo);
                errores.reportar(Integer.parseInt(it[0]), Integer.parseInt(it[1]), "error de sintaxis: " + it[2]);
            }
            errores.setArchivoActual(null);
            return !items.isEmpty();
        }
    }
}
