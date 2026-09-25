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
 * Pasada 1 de un programa Pig Latin: a diferencia de Y? y Zetariano, un
 * archivo .pig no define ningun tipo ni funcion propia, solo IMPORTA
 * archivos .y/.z ya existentes. Por cada 'import', esta clase parsea el
 * archivo correspondiente y corre su propia pasada 1 (RegistradorFirmasY
 * o RegistradorFirmasZetariano) contra la MISMA TablaSimbolosGlobal
 * compartida, para que cualquier import pueda referenciar un tipo o
 * funcion de otro import.
 *
 * Convencion de 'ruta': el ULTIMO segmento es la extension ('y' o 'z'),
 * el segmento anterior es el nombre de archivo, y los segmentos previos
 * son subcarpetas. Ej. 'Nodo.z' -> "Nodo.z" en el mismo directorio que
 * el .pig; 'utils.utils.y' -> "utils/utils.y".
 *
 * Guarda el arbol (ProgramaContext) de cada archivo importado en un
 * ArchivoImportado, porque PigLatinAnalizador (pasada 2) todavia
 * necesita recorrer su cuerpo para generar las cuartetas, sin volver a
 * parsearlo.
 *
 * Limitacion conocida: los imports se procesan en el orden en que
 * aparecen en el .pig, uno completo a la vez. Si un import usa un tipo
 * de otro import que todavia no se proceso, se reportaria "tipo
 * desconocido" aunque exista mas adelante en la lista.
 */
public class RegistradorFirmasPigLatin {

    private final TablaSimbolosGlobal global;
    private final GestorErrores errores;
    private final Path directorioBase;

    public RegistradorFirmasPigLatin(TablaSimbolosGlobal global, GestorErrores errores, Path directorioBase) {
        this.global = global;
        this.errores = errores;
        this.directorioBase = directorioBase;
    }

    public List<ArchivoImportado> registrar(PigLatinParser.ProgramaContext ctx) throws IOException {
        List<ArchivoImportado> archivos = new ArrayList<>();
        if (ctx.listaImportaciones() == null) {
            return archivos;
        }
        for (PigLatinParser.ImportacionContext imp : ctx.listaImportaciones().importacion()) {
            ArchivoImportado archivo = procesarImportacion(imp);
            if (archivo != null) {
                archivos.add(archivo);
            }
        }
        return archivos;
    }

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

        // Nombre para mostrar en errores (no la ruta real del disco), ej. "Nodo.z" o "utils/utils.y"
        List<String> partesNombreMostrado = new ArrayList<>(segmentos.subList(0, segmentos.size() - 2));
        partesNombreMostrado.add(nombreArchivo);
        String nombreMostrado = String.join("/", partesNombreMostrado) + "." + extension;

        if (extension.equals("y")) {
            YParser parser = new YParser(new CommonTokenStream(new YLexer(CharStreams.fromPath(archivo))));
            YParser.ProgramaContext arbol = parser.programa();
            errores.setArchivoActual(nombreMostrado);
            new RegistradorFirmasY(global, errores).registrar(arbol);
            errores.setArchivoActual(null);
            return ArchivoImportado.deY(arbol, nombreMostrado);
        }
        if (extension.equals("z")) {
            ZetarianoParser parser = new ZetarianoParser(new CommonTokenStream(new ZetarianoLexer(CharStreams.fromPath(archivo))));
            ZetarianoParser.ProgramaContext arbol = parser.programa();
            errores.setArchivoActual(nombreMostrado);
            SimboloClase clase = new RegistradorFirmasZetariano(global, errores).registrar(arbol);
            errores.setArchivoActual(null);
            return ArchivoImportado.deZetariano(arbol, clase, nombreMostrado);
        }

        errores.reportar(linea, columna, "extension de import desconocida: '" + extension + "' (se esperaba .y o .z)");
        return null;
    }
}