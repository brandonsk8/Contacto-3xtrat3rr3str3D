package com.company.gui;

import com.company.semantico.TablaSimbolosGlobal;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Ventana principal de la aplicacion: un editor con arbol de archivos
 * del proyecto abierto, numeracion de lineas, coloreado de sintaxis
 * segun la extension (.y/.z/.pig, ver Resaltador), consola de salida, y
 * botones para gestionar archivos/carpetas y correr el pipeline
 * completo sobre el archivo actual (lexer -> parser -> analisis
 * semantico -> cuartetas -> GeneradorC, via EjecutorPipeline).
 *
 * Un archivo abierto a la vez (sin pestañas), sin autocompletado.
 * "Correr" opera sobre el texto del editor tal cual esta en pantalla,
 * no hace falta guardar antes.
 */
public class VentanaPrincipal extends JFrame {

    private final EjecutorPipeline ejecutor = new EjecutorPipeline();

    private JTree arbolArchivos;
    private JTextPane editor;
    private JTextArea consola;
    private JLabel etiquetaArchivoActual;

    private Path carpetaProyecto;
    private Path archivoAbierto;
    private String ultimoCodigoCGenerado;
    private TablaSimbolosGlobal ultimaTablaSimbolos;

    public VentanaPrincipal() {
        super("Contacto 3xtrat3rr3str3D");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);

        setJMenuBar(construirBarraMenu());
        add(construirBarraHerramientas(), BorderLayout.NORTH);
        add(construirCuerpo(), BorderLayout.CENTER);
        add(construirBarraEstado(), BorderLayout.SOUTH);
    }

    /**
     * Acciones de archivo/proyecto (poco frecuentes) y "Ver tabla de
     * símbolos" van en el menú, para no saturar la barra de herramientas
     * con botones que no se usan a cada rato.
     */
    private JMenuBar construirBarraMenu() {
        JMenuBar barraMenu = new JMenuBar();

        JMenu menuArchivo = new JMenu("Archivo");
        agregarItem(menuArchivo, "Abrir carpeta de proyecto...", e -> abrirCarpetaProyecto());
        agregarItem(menuArchivo, "Nuevo archivo...", e -> crearNuevoElemento(false));
        agregarItem(menuArchivo, "Nueva carpeta...", e -> crearNuevoElemento(true));
        menuArchivo.addSeparator();
        agregarItem(menuArchivo, "Guardar", e -> guardarArchivoActual());
        agregarItem(menuArchivo, "Guardar como...", e -> guardarComo());

        JMenu menuVer = new JMenu("Ver");
        agregarItem(menuVer, "Tabla de símbolos", e -> mostrarTablaSimbolos());

        barraMenu.add(menuArchivo);
        barraMenu.add(menuVer);
        return barraMenu;
    }

    private void agregarItem(JMenu menu, String texto, java.awt.event.ActionListener accion) {
        JMenuItem item = new JMenuItem(texto);
        item.addActionListener(accion);
        menu.add(item);
    }

    /** Solo lo que se usa constantemente mientras se edita: correr el pipeline y exportar el C generado. */
    private JToolBar construirBarraHerramientas() {
        JToolBar barra = new JToolBar();
        barra.setFloatable(false);

        JButton btnCorrer = new JButton("Correr");
        btnCorrer.addActionListener(e -> correrArchivoActual());

        JButton btnGuardarCodigoC = new JButton("Guardar código C...");
        btnGuardarCodigoC.addActionListener(e -> guardarCodigoCGenerado());

        barra.add(btnCorrer);
        barra.add(btnGuardarCodigoC);
        return barra;
    }

    private JSplitPane construirCuerpo() {
        arbolArchivos = new JTree(new DefaultMutableTreeNode("(sin proyecto abierto)"));
        arbolArchivos.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        arbolArchivos.addTreeSelectionListener(this::alSeleccionarEnArbol);
        JScrollPane panelArbol = new JScrollPane(arbolArchivos);
        panelArbol.setPreferredSize(new Dimension(280, 0));

        editor = new EditorSinAjusteDeLinea();
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        // Reresalta con cada tecla/pegado - solo en insertUpdate/removeUpdate,
        // nunca en changedUpdate (ver el Javadoc de Resaltador: evita el bucle
        // infinito, porque aplicar() cambia atributos y eso SI dispara changedUpdate).
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { resaltarActual(); }

            @Override
            public void removeUpdate(DocumentEvent e) { resaltarActual(); }

            @Override
            public void changedUpdate(DocumentEvent e) { /* intencional: ver Javadoc de Resaltador */ }
        });
        JScrollPane panelEditor = new JScrollPane(editor);
        panelEditor.setRowHeaderView(new AreaNumerosLinea(editor));

        consola = new JTextArea();
        consola.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        consola.setEditable(false);
        consola.setLineWrap(true);
        consola.setWrapStyleWord(true);
        JScrollPane panelConsola = new JScrollPane(consola);

        JSplitPane editorYConsola = new JSplitPane(JSplitPane.VERTICAL_SPLIT, panelEditor, panelConsola);
        editorYConsola.setResizeWeight(0.7);

        JSplitPane completo = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panelArbol, editorYConsola);
        completo.setResizeWeight(0.0);
        return completo;
    }

    private JLabel construirBarraEstado() {
        etiquetaArchivoActual = new JLabel(" (ningun archivo abierto)");
        etiquetaArchivoActual.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return etiquetaArchivoActual;
    }

    // ---------------- accion: abrir carpeta de proyecto ----------------

    private void abrirCarpetaProyecto() {
        JFileChooser selector = new JFileChooser();
        selector.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        selector.setDialogTitle("Elegi la carpeta del proyecto (ej. ejemplos/Pila)");
        int resultado = selector.showOpenDialog(this);
        if (resultado != JFileChooser.APPROVE_OPTION) {
            return;
        }
        carpetaProyecto = selector.getSelectedFile().toPath();
        refrescarArbol();
    }

    private void refrescarArbol() {
        if (carpetaProyecto == null) {
            return;
        }
        DefaultMutableTreeNode raiz = construirNodo(carpetaProyecto.toFile());
        arbolArchivos.setModel(new DefaultTreeModel(raiz));
    }

    private DefaultMutableTreeNode construirNodo(File archivoODirectorio) {
        DefaultMutableTreeNode nodo = new DefaultMutableTreeNode(archivoODirectorio);
        if (archivoODirectorio.isDirectory()) {
            File[] hijos = archivoODirectorio.listFiles();
            if (hijos != null) {
                Arrays.sort(hijos, Comparator
                        .comparing(File::isFile) // carpetas primero
                        .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File hijo : hijos) {
                    nodo.add(construirNodo(hijo));
                }
            }
        }
        return nodo;
    }

    // ---------------- accion: nuevo archivo / nueva carpeta ----------------

    /**
     * Crea un archivo o carpeta dentro de la carpeta seleccionada en el
     * arbol (o, si lo seleccionado es un archivo, dentro de su carpeta
     * contenedora). Sin seleccion, usa la raiz del proyecto.
     */
    private void crearNuevoElemento(boolean esCarpeta) {
        if (carpetaProyecto == null) {
            JOptionPane.showMessageDialog(this, "Abri una carpeta de proyecto primero (\"Abrir carpeta de proyecto...\").",
                    "No hay proyecto abierto", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path destino = carpetaDestinoSegunSeleccion();
        String tipo = esCarpeta ? "carpeta" : "archivo";
        String nombre = JOptionPane.showInputDialog(this,
                "Nombre de la nueva " + tipo + " (dentro de '" + destino.getFileName() + "'):",
                esCarpeta ? "NuevaCarpeta" : "nuevoArchivo.y");
        if (nombre == null || nombre.isBlank()) {
            return;
        }
        Path nuevo = destino.resolve(nombre.trim());
        if (Files.exists(nuevo)) {
            JOptionPane.showMessageDialog(this, "Ya existe '" + nuevo.getFileName() + "' ahi.",
                    "No se pudo crear", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            if (esCarpeta) {
                Files.createDirectory(nuevo);
            } else {
                Files.createFile(nuevo);
            }
            refrescarArbol();
            if (!esCarpeta) {
                abrirArchivoEnEditor(nuevo);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo crear '" + nuevo + "':\n" + ex.getMessage(),
                    "Error al crear " + tipo, JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Carpeta contenedora a usar para "Nuevo archivo"/"Nueva carpeta", segun lo seleccionado en el arbol. */
    private Path carpetaDestinoSegunSeleccion() {
        Object seleccionado = arbolArchivos.getLastSelectedPathComponent();
        if (seleccionado instanceof DefaultMutableTreeNode) {
            Object valor = ((DefaultMutableTreeNode) seleccionado).getUserObject();
            if (valor instanceof File) {
                File archivo = (File) valor;
                return archivo.isDirectory() ? archivo.toPath() : archivo.toPath().getParent();
            }
        }
        return carpetaProyecto;
    }

    // ---------------- accion: guardar como ----------------

    /**
     * A diferencia de "Guardar", exporta el texto del editor a donde el
     * usuario elija sin necesitar un archivo ya abierto. Si el destino
     * cae dentro del proyecto abierto, el arbol se refresca, y ese
     * archivo pasa a ser el "abierto".
     */
    private void guardarComo() {
        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Guardar como...");
        if (archivoAbierto != null) {
            selector.setSelectedFile(archivoAbierto.toFile());
        } else if (carpetaProyecto != null) {
            selector.setCurrentDirectory(carpetaProyecto.toFile());
        }
        int resultado = selector.showSaveDialog(this);
        if (resultado != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destino = selector.getSelectedFile().toPath();
        if (Files.exists(destino)) {
            int confirmar = JOptionPane.showConfirmDialog(this,
                    "'" + destino.getFileName() + "' ya existe. ¿Sobrescribir?",
                    "Confirmar sobrescritura", JOptionPane.YES_NO_OPTION);
            if (confirmar != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            Files.writeString(destino, editor.getText(), StandardCharsets.UTF_8);
            archivoAbierto = destino;
            etiquetaArchivoActual.setText(" " + destino + "  (guardado)");
            resaltarActual();
            if (carpetaProyecto != null && destino.toAbsolutePath().startsWith(carpetaProyecto.toAbsolutePath())) {
                refrescarArbol();
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar '" + destino + "':\n" + ex.getMessage(),
                    "Error al guardar", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- accion: seleccionar un archivo en el arbol ----------------

    private void alSeleccionarEnArbol(TreeSelectionEvent evento) {
        Object seleccionado = arbolArchivos.getLastSelectedPathComponent();
        if (!(seleccionado instanceof DefaultMutableTreeNode)) {
            return;
        }
        Object valor = ((DefaultMutableTreeNode) seleccionado).getUserObject();
        if (!(valor instanceof File)) {
            return;
        }
        File archivo = (File) valor;
        if (archivo.isDirectory()) {
            return;
        }
        abrirArchivoEnEditor(archivo.toPath());
    }

    private void abrirArchivoEnEditor(Path archivo) {
        try {
            String contenido = Files.readString(archivo, StandardCharsets.UTF_8);
            // El archivo (y por lo tanto la extension) se fija ANTES de poner
            // el texto, para que el auto-resaltado que dispara setText() ya
            // sepa que lenguaje usar.
            archivoAbierto = archivo;
            editor.setText(contenido);
            editor.setCaretPosition(0);
            etiquetaArchivoActual.setText(" " + archivo);
            resaltarActual();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo abrir '" + archivo + "':\n" + ex.getMessage(),
                    "Error al abrir archivo", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- accion: guardar ----------------

    private void guardarArchivoActual() {
        if (archivoAbierto == null) {
            JOptionPane.showMessageDialog(this, "No hay ningun archivo abierto para guardar.",
                    "Nada que guardar", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Files.writeString(archivoAbierto, editor.getText(), StandardCharsets.UTF_8);
            etiquetaArchivoActual.setText(" " + archivoAbierto + "  (guardado)");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar '" + archivoAbierto + "':\n" + ex.getMessage(),
                    "Error al guardar", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- accion: correr el pipeline ----------------

    private void correrArchivoActual() {
        if (archivoAbierto == null) {
            JOptionPane.showMessageDialog(this, "Abri un archivo (.y, .z o .pig) primero.",
                    "Nada que correr", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String nombrePrograma = nombreSinExtension(archivoAbierto);
        consola.setText("Corriendo " + archivoAbierto.getFileName() + "...\n");
        ultimoCodigoCGenerado = null;
        ultimaTablaSimbolos = null;
        try {
            EjecutorPipeline.Resultado resultado = ejecutor.correr(editor.getText(), archivoAbierto, nombrePrograma);
            consola.setText(resultado.reporte);
            ultimoCodigoCGenerado = resultado.codigoC;
            ultimaTablaSimbolos = resultado.tablaSimbolos;
        } catch (Exception ex) {
            consola.setText("Error inesperado corriendo el pipeline:\n" + ex);
        }
        consola.setCaretPosition(0);
    }

    // ---------------- accion: ver la tabla de simbolos ----------------

    private void mostrarTablaSimbolos() {
        if (ultimaTablaSimbolos == null) {
            JOptionPane.showMessageDialog(this, "Todavia no hay una tabla de simbolos disponible (corre un archivo primero).",
                    "Nada que mostrar", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String[]> filas = TablaSimbolosFormateador.filas(ultimaTablaSimbolos);
        String[] columnas = {"Ámbito", "Nombre", "Categoría", "Tipo", "Línea"};
        DefaultTableModel modelo = new DefaultTableModel(columnas, 0) {
            @Override
            public boolean isCellEditable(int fila, int columna) {
                return false;
            }
        };
        for (String[] fila : filas) {
            modelo.addRow(fila);
        }

        JTable tabla = new JTable(modelo);
        tabla.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        tabla.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        JDialog dialogo = new JDialog(this, "Tabla de símbolos", false);
        dialogo.add(new JScrollPane(tabla));
        dialogo.setSize(800, 500);
        dialogo.setLocationRelativeTo(this);
        dialogo.setVisible(true);
    }

    // ---------------- accion: guardar el codigo C generado ----------------

    private void guardarCodigoCGenerado() {
        if (ultimoCodigoCGenerado == null) {
            JOptionPane.showMessageDialog(this, "Todavia no hay codigo C generado (corre un programa primero, sin errores).",
                    "Nada que guardar", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Guardar código C generado...");
        if (carpetaProyecto != null) {
            selector.setCurrentDirectory(carpetaProyecto.toFile());
        }
        String nombreSugerido = (archivoAbierto != null ? nombreSinExtension(archivoAbierto) : "salida") + ".c";
        selector.setSelectedFile(new File(nombreSugerido));
        int resultado = selector.showSaveDialog(this);
        if (resultado != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destino = selector.getSelectedFile().toPath();
        if (Files.exists(destino)) {
            int confirmar = JOptionPane.showConfirmDialog(this,
                    "'" + destino.getFileName() + "' ya existe. ¿Sobrescribir?",
                    "Confirmar sobrescritura", JOptionPane.YES_NO_OPTION);
            if (confirmar != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            Files.writeString(destino, ultimoCodigoCGenerado, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "Código C guardado en '" + destino + "'.",
                    "Guardado", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar '" + destino + "':\n" + ex.getMessage(),
                    "Error al guardar", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String nombreSinExtension(Path archivo) {
        String nombre = archivo.getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return punto >= 0 ? nombre.substring(0, punto) : nombre;
    }

    // ---------------- coloreado de sintaxis ----------------

    private void resaltarActual() {
        String extension = archivoAbierto != null ? extensionDe(archivoAbierto) : null;
        SwingUtilities.invokeLater(() -> Resaltador.aplicar(editor, extension));
    }

    private String extensionDe(Path archivo) {
        String nombre = archivo.getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return punto >= 0 ? nombre.substring(punto + 1).toLowerCase() : "";
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new VentanaPrincipal().setVisible(true));
    }

    /**
     * Un JTextPane comun hace word-wrap siempre; para Y?, que usa
     * indentacion significativa, una linea larga envuelta visualmente
     * rompe la lectura de la indentacion. Esta subclase desactiva el
     * wrap (aparece scroll horizontal en su lugar).
     */
    private static class EditorSinAjusteDeLinea extends JTextPane {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }
    }

    /**
     * Numeracion de lineas para el editor - se muestra como "row header"
     * del JScrollPane que lo envuelve, asi que se desplaza junto con el
     * scroll automaticamente y se redibuja sola cada vez que cambia el
     * documento. Trabaja sobre un JTextComponent generico (no
     * JTextArea) porque JTextPane no tiene getLineCount/getLineStartOffset,
     * asi que las lineas se calculan a mano contando saltos de linea.
     */
    private static class AreaNumerosLinea extends JComponent {
        private final JTextComponent editorAsociado;

        AreaNumerosLinea(JTextComponent editorAsociado) {
            this.editorAsociado = editorAsociado;
            setFont(editorAsociado.getFont());
            setBackground(new Color(235, 235, 235));
            setForeground(new Color(120, 120, 120));
            setOpaque(true);
            editorAsociado.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) { refrescar(); }

                @Override
                public void removeUpdate(DocumentEvent e) { refrescar(); }

                @Override
                public void changedUpdate(DocumentEvent e) { /* solo cambio de estilo (Resaltador) - no cambia la cantidad de lineas */ }
            });
        }

        private void refrescar() {
            SwingUtilities.invokeLater(() -> {
                revalidate();
                repaint();
            });
        }

        /** Offset (posicion en el documento) donde empieza cada linea logica. */
        private int[] calcularInicios() {
            try {
                String texto = editorAsociado.getDocument().getText(0, editorAsociado.getDocument().getLength());
                List<Integer> inicios = new ArrayList<>();
                inicios.add(0);
                for (int i = 0; i < texto.length(); i++) {
                    if (texto.charAt(i) == '\n') {
                        inicios.add(i + 1);
                    }
                }
                int[] arreglo = new int[inicios.size()];
                for (int i = 0; i < arreglo.length; i++) {
                    arreglo[i] = inicios.get(i);
                }
                return arreglo;
            } catch (BadLocationException ex) {
                return new int[]{0};
            }
        }

        @Override
        public Dimension getPreferredSize() {
            int lineas = calcularInicios().length;
            FontMetrics fm = getFontMetrics(getFont());
            int ancho = fm.stringWidth(String.valueOf(lineas)) + 18;
            return new Dimension(ancho, editorAsociado.getHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(getForeground());
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int[] inicios = calcularInicios();
            for (int i = 0; i < inicios.length; i++) {
                try {
                    Rectangle2D vista = editorAsociado.modelToView2D(inicios[i]);
                    if (vista == null) {
                        continue;
                    }
                    int y = (int) vista.getY() + fm.getAscent();
                    String numero = String.valueOf(i + 1);
                    int x = getWidth() - fm.stringWidth(numero) - 6;
                    g.drawString(numero, x, y);
                } catch (BadLocationException ignorada) {
                    // linea desapareció entre calcularInicios() y modelToView2D() (edicion concurrente
                    // en el propio hilo de Swing no debería pasar, pero por las dudas no rompemos el pintado)
                }
            }
        }
    }
}