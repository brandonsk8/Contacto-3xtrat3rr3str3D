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
import javax.swing.JTabbedPane;
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

    static {
        Tema.aplicar(true);
    }

    private final EjecutorPipeline ejecutor = new EjecutorPipeline();
    private AreaNumerosLinea gutter;
    private JSplitPane dividirEditorYPestanas;
    private JLabel etiquetaResumen;
    private JButton btnEjecutarRef;
    private JButton btnCorrerRef;

    private JTree arbolArchivos;
    private JTextPane editor;
    private JTextArea consola;
    private JTextArea areaCodigoC;
    private JTextArea areaCodigoAsm;
    private JTextArea areaCodigoC3d;
    private String ultimoCodigoC3dGenerado;
    private PanelEjecucion panelEjecucion;
    private JTabbedPane pestanasInferiores;
    private JLabel etiquetaArchivoActual;

    private Path carpetaProyecto;
    private Path archivoAbierto;
    private String ultimoCodigoCGenerado;
    private String ultimoCodigoAsmGenerado;
    private TablaSimbolosGlobal ultimaTablaSimbolos;

    public VentanaPrincipal() {
        super("Contacto 3xtrat3rr3str3D");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);

        setJMenuBar(construirBarraMenu());
        add(construirBarraHerramientas(), BorderLayout.NORTH);
        add(construirCuerpo(), BorderLayout.CENTER);
        instalarMenuContextualArbol();
        add(construirBarraEstado(), BorderLayout.SOUTH);
        aplicarColoresComponentes();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                dividirEditorYPestanas.setDividerLocation(0.58);
            }
        });
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
        menuArchivo.addSeparator();
        agregarItem(menuArchivo, "Abrir archivo...", e -> abrirArchivoSuelto());
        agregarItem(menuArchivo, "Descargar selección (archivo o carpeta .zip)...", e -> descargarSeleccion());

        JMenu menuVer = new JMenu("Ver");
        agregarItem(menuVer, "Tabla de símbolos", e -> mostrarTablaSimbolos());
        agregarItem(menuVer, "Cambiar tema claro / oscuro", e -> alternarTema());

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

        JButton btnCorrer = new BotonAccion("Compilar", false);
        btnCorrer.addActionListener(e -> correrArchivoActual());

        JButton btnEjecutar = new BotonAccion("\u25B6  Compilar y ejecutar", true);
        btnEjecutar.addActionListener(e -> ejecutarArchivoActual());

        JButton btnGuardarCodigoC = new BotonAccion("Guardar C", false);
        btnGuardarCodigoC.addActionListener(e -> guardarCodigoCGenerado());

        JButton btnGuardarC3d = new BotonAccion("Guardar .c3d", false);
        btnGuardarC3d.addActionListener(e -> guardarCodigoGenerado(ultimoCodigoC3dGenerado, ".c3d", "de tres direcciones"));

        JButton btnGuardarAsm = new BotonAccion("Guardar RISC-V", false);
        btnGuardarAsm.addActionListener(e -> guardarCodigoGenerado(ultimoCodigoAsmGenerado, ".asm", "RISC-V"));

        btnCorrerRef = btnCorrer;
        btnEjecutarRef = btnEjecutar;
        barra.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        barra.add(btnCorrer);
        barra.add(javax.swing.Box.createHorizontalStrut(6));
        barra.add(btnEjecutar);
        barra.add(javax.swing.Box.createHorizontalStrut(6));
        barra.add(javax.swing.Box.createHorizontalStrut(10));
        barra.add(btnGuardarC3d);
        barra.add(javax.swing.Box.createHorizontalStrut(6));
        barra.add(btnGuardarCodigoC);
        barra.add(javax.swing.Box.createHorizontalStrut(6));
        barra.add(btnGuardarAsm);
        return barra;
    }

    private JSplitPane construirCuerpo() {
        arbolArchivos = new JTree(new DefaultMutableTreeNode("(sin proyecto abierto)"));
        arbolArchivos.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        arbolArchivos.addTreeSelectionListener(this::alSeleccionarEnArbol);
        arbolArchivos.setRowHeight(24);
        arbolArchivos.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp,
                                                                    boolean leaf, int row, boolean foco) {
                Object o = v instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) v).getUserObject() : v;
                String txt = o instanceof File ? (row == 0 ? ((File) o).getName() : ((File) o).getName()) : String.valueOf(o);
                super.getTreeCellRendererComponent(t, txt, sel, exp, leaf, row, foco);
                setBackgroundNonSelectionColor(Tema.panel);
                setBackgroundSelectionColor(Tema.seleccion);
                setTextNonSelectionColor(Tema.texto);
                setTextSelectionColor(Tema.oscuroTexto());
                setBorderSelectionColor(null);
                return this;
            }
        });
        JScrollPane panelArbol = new JScrollPane(arbolArchivos);
        panelArbol.setPreferredSize(new Dimension(280, 0));

        editor = new EditorSinAjusteDeLinea();
        editor.setFont(Tema.fuenteCodigo(14));
        editor.setMargin(new java.awt.Insets(6, 8, 6, 8));
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
        gutter = new AreaNumerosLinea(editor);
        panelEditor.setRowHeaderView(gutter);

        consola = crearAreaSoloLectura();
        areaCodigoC = crearAreaSoloLectura();
        areaCodigoAsm = crearAreaSoloLectura();
        areaCodigoC3d = crearAreaSoloLectura();
        panelEjecucion = new PanelEjecucion();
        // doble clic en un error del reporte ("linea L:C - ...") salta a esa linea del editor
        consola.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    saltarALineaDeError();
                }
            }
        });

        pestanasInferiores = new JTabbedPane();
        pestanasInferiores.addTab("Reporte", new JScrollPane(consola));
        pestanasInferiores.addTab("Ejecución", panelEjecucion);
        pestanasInferiores.addTab("Cuartetas (C3D)", new JScrollPane(areaCodigoC3d));
        pestanasInferiores.addTab("C generado", new JScrollPane(areaCodigoC));
        pestanasInferiores.addTab("RISC-V generado", new JScrollPane(areaCodigoAsm));

        JSplitPane editorYConsola = new JSplitPane(JSplitPane.VERTICAL_SPLIT, panelEditor, pestanasInferiores);
        editorYConsola.setResizeWeight(0.55);
        dividirEditorYPestanas = editorYConsola;

        JSplitPane completo = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panelArbol, editorYConsola);
        completo.setResizeWeight(0.0);
        return completo;
    }

    private JTextArea crearAreaSoloLectura() {
        JTextArea area = new JTextArea();
        area.setFont(Tema.fuenteCodigo(13));
        area.setMargin(new java.awt.Insets(6, 8, 6, 8));
        area.setEditable(false);
        return area;
    }

    private javax.swing.JPanel construirBarraEstado() {
        etiquetaArchivoActual = new JLabel(" (ningun archivo abierto)");
        etiquetaResumen = new JLabel(" ");
        javax.swing.JPanel barra = new javax.swing.JPanel(new BorderLayout());
        barra.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        barra.add(etiquetaArchivoActual, BorderLayout.WEST);
        barra.add(etiquetaResumen, BorderLayout.EAST);
        return barra;
    }

    // ---------------- tema ----------------

    private void alternarTema() {
        Tema.aplicar(!Tema.esOscuro());
        SwingUtilities.updateComponentTreeUI(this);
        aplicarColoresComponentes();
        resaltarActual();
    }

    private void aplicarColoresComponentes() {
        getContentPane().setBackground(Tema.fondo);
        editor.setBackground(Tema.editorFondo);
        editor.setForeground(Tema.texto);
        editor.setCaretColor(Tema.texto);
        editor.setSelectionColor(Tema.seleccion);
        for (JTextArea a : new JTextArea[]{consola, areaCodigoC, areaCodigoAsm, areaCodigoC3d}) {
            a.setBackground(Tema.consolaFondo);
            a.setForeground(Tema.texto);
            a.setCaretColor(Tema.texto);
            a.setSelectionColor(Tema.seleccion);
        }
        arbolArchivos.setBackground(Tema.panel);
        gutter.setBackground(Tema.gutterFondo);
        gutter.setForeground(Tema.gutterTexto);
        etiquetaArchivoActual.setForeground(Tema.textoSuave);
        etiquetaResumen.setForeground(Tema.textoSuave);
        panelEjecucion.aplicarTema();
        repaint();
    }

    private void saltarALineaDeError() {
        try {
            int pos = consola.getCaretPosition();
            int linea = consola.getLineOfOffset(pos);
            String texto = consola.getText(consola.getLineStartOffset(linea),
                    consola.getLineEndOffset(linea) - consola.getLineStartOffset(linea));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[Ll][ií]nea\\s+(\\d+)").matcher(texto);
            if (!m.find()) {
                return;
            }
            int destino = Integer.parseInt(m.group(1));
            String contenido = editor.getDocument().getText(0, editor.getDocument().getLength());
            int offset = 0;
            for (int i = 1; i < destino; i++) {
                int salto = contenido.indexOf('\n', offset);
                if (salto < 0) {
                    break;
                }
                offset = salto + 1;
            }
            editor.requestFocusInWindow();
            editor.setCaretPosition(offset);
            Rectangle2D r = editor.modelToView2D(offset);
            if (r != null) {
                editor.scrollRectToVisible(r.getBounds());
            }
        } catch (Exception ignorada) {
            // un error al saltar no debe romper la ventana
        }
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


    // ---------------- accion: abrir archivo suelto / descargar ----------------

    private void abrirArchivoSuelto() {
        JFileChooser selector = new JFileChooser();
        if (carpetaProyecto != null) {
            selector.setCurrentDirectory(carpetaProyecto.toFile());
        }
        selector.setDialogTitle("Abrir archivo (.y, .z, .pig)");
        if (selector.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            abrirArchivoEnEditor(selector.getSelectedFile().toPath());
        }
    }

    /** Elemento elegido en el arbol (archivo o carpeta); sin seleccion, la raiz del proyecto. */
    private Path elementoSeleccionado() {
        Object nodo = arbolArchivos.getLastSelectedPathComponent();
        if (nodo instanceof DefaultMutableTreeNode) {
            Object valor = ((DefaultMutableTreeNode) nodo).getUserObject();
            if (valor instanceof File) {
                return ((File) valor).toPath();
            }
        }
        return carpetaProyecto;
    }

    /**
     * "Descargar": copia el archivo seleccionado al lugar que elija la
     * persona; si es una carpeta la empaqueta en un .zip conservando la
     * estructura de subcarpetas.
     */
    private void descargarSeleccion() {
        Path origen = elementoSeleccionado();
        if (origen == null || !Files.exists(origen)) {
            JOptionPane.showMessageDialog(this, "Abre una carpeta de proyecto y selecciona un archivo o carpeta.",
                    "Descargar", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean esCarpeta = Files.isDirectory(origen);
        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Descargar " + (esCarpeta ? "carpeta como .zip" : "archivo"));
        String nombre = origen.getFileName() == null ? "proyecto" : origen.getFileName().toString();
        selector.setSelectedFile(new File(esCarpeta ? nombre + ".zip" : nombre));
        if (selector.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destino = selector.getSelectedFile().toPath();
        if (esCarpeta && !destino.getFileName().toString().toLowerCase().endsWith(".zip")) {
            destino = destino.resolveSibling(destino.getFileName() + ".zip");
        }
        if (Files.exists(destino)) {
            int confirmar = JOptionPane.showConfirmDialog(this,
                    "'" + destino.getFileName() + "' ya existe. ¿Sobrescribir?",
                    "Confirmar sobrescritura", JOptionPane.YES_NO_OPTION);
            if (confirmar != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            if (esCarpeta) {
                comprimirCarpeta(origen, destino);
            } else {
                if (origen.equals(archivoAbierto)) {
                    Files.writeString(destino, editor.getText(), StandardCharsets.UTF_8);
                } else {
                    Files.copy(origen, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            JOptionPane.showMessageDialog(this, "Descargado en:\n" + destino, "Descargar",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo descargar:\n" + ex.getMessage(),
                    "Error al descargar", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void comprimirCarpeta(Path carpeta, Path zip) throws IOException {
        try (java.util.zip.ZipOutputStream salida = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip));
             java.util.stream.Stream<Path> recorrido = Files.walk(carpeta)) {
            for (Path p : (Iterable<Path>) recorrido::iterator) {
                if (p.equals(carpeta) || p.equals(zip)) {
                    continue;
                }
                String nombre = carpeta.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    salida.putNextEntry(new java.util.zip.ZipEntry(nombre + "/"));
                    salida.closeEntry();
                } else {
                    salida.putNextEntry(new java.util.zip.ZipEntry(nombre));
                    Files.copy(p, salida);
                    salida.closeEntry();
                }
            }
        }
    }

    /** Menu contextual (clic derecho) del arbol de archivos. */
    private void instalarMenuContextualArbol() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        JMenuItem nuevoArchivo = new JMenuItem("Nuevo archivo...");
        nuevoArchivo.addActionListener(e -> crearNuevoElemento(false));
        JMenuItem nuevaCarpeta = new JMenuItem("Nueva carpeta...");
        nuevaCarpeta.addActionListener(e -> crearNuevoElemento(true));
        JMenuItem descargar = new JMenuItem("Descargar...");
        descargar.addActionListener(e -> descargarSeleccion());
        JMenuItem actualizar = new JMenuItem("Actualizar");
        actualizar.addActionListener(e -> refrescarArbol());
        menu.add(nuevoArchivo);
        menu.add(nuevaCarpeta);
        menu.addSeparator();
        menu.add(descargar);
        menu.add(actualizar);
        arbolArchivos.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { mostrar(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { mostrar(e); }
            private void mostrar(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    javax.swing.tree.TreePath ruta = arbolArchivos.getPathForLocation(e.getX(), e.getY());
                    if (ruta != null) {
                        arbolArchivos.setSelectionPath(ruta);
                    }
                    menu.show(arbolArchivos, e.getX(), e.getY());
                }
            }
        });
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
        ultimoCodigoAsmGenerado = null;
        ultimoCodigoC3dGenerado = null;
        ultimaTablaSimbolos = null;
        long t0 = System.nanoTime();
        try {
            EjecutorPipeline.Resultado resultado = ejecutor.correr(editor.getText(), archivoAbierto, nombrePrograma);
            consola.setText(resultado.reporte);
            ultimoCodigoCGenerado = resultado.codigoC;
            ultimoCodigoAsmGenerado = resultado.codigoAsm;
            ultimaTablaSimbolos = resultado.tablaSimbolos;
            ultimoCodigoC3dGenerado = resultado.codigoC3d;
            areaCodigoC3d.setText(resultado.codigoC3d != null ? resultado.codigoC3d : "(sin cuartetas: hay errores)");
            areaCodigoC3d.setCaretPosition(0);
            areaCodigoC.setText(resultado.codigoC != null ? resultado.codigoC : "(sin codigo C: hay errores)");
            areaCodigoAsm.setText(resultado.codigoAsm != null ? resultado.codigoAsm : "(sin codigo RISC-V: hay errores)");
            areaCodigoC.setCaretPosition(0);
            areaCodigoAsm.setCaretPosition(0);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = resultado.codigoAsm != null;
            etiquetaResumen.setText((ok ? "✔ Compilado sin errores" : "✖ Con errores") + "  ·  " + ms + " ms");
            etiquetaResumen.setForeground(ok ? Tema.ok : Tema.error);
        } catch (Exception ex) {
            consola.setText("Error inesperado corriendo el pipeline:\n" + ex);
        }
        consola.setCaretPosition(0);
        pestanasInferiores.setSelectedIndex(0);
    }

    // ---------------- accion: compilar y ejecutar el RISC-V generado ----------------

    /**
     * Compila el archivo abierto y, si no hubo errores, EJECUTA el ensamblador
     * RISC-V generado con el interprete integrado (sin herramientas externas).
     */
    private void ejecutarArchivoActual() {
        correrArchivoActual();
        if (ultimoCodigoAsmGenerado == null) {
            return; // errores: el reporte ya los muestra
        }
        pestanasInferiores.setSelectedComponent(panelEjecucion);
        if (!"pig".equals(extensionDe(archivoAbierto))) {
            panelEjecucion.mostrarMensaje("Este archivo (." + extensionDe(archivoAbierto) + ") es solo una clase o libreria: no tiene programa principal.\n"
                    + "Compila sin errores, pero para EJECUTAR abre un archivo .pig (con MAIOR>) que lo importe.");
            return;
        }
        panelEjecucion.ejecutar(ultimoCodigoAsmGenerado);
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
        guardarCodigoGenerado(ultimoCodigoCGenerado, ".c", "C");
    }

    private void guardarCodigoGenerado(String contenido, String extension, String etiqueta) {
        if (contenido == null) {
            JOptionPane.showMessageDialog(this, "Todavia no hay codigo " + etiqueta + " generado (corre un programa primero, sin errores).",
                    "Nada que guardar", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Guardar código " + etiqueta + " generado...");
        if (carpetaProyecto != null) {
            selector.setCurrentDirectory(carpetaProyecto.toFile());
        }
        String nombreSugerido = (archivoAbierto != null ? nombreSinExtension(archivoAbierto) : "salida") + extension;
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
            Files.writeString(destino, contenido, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "Código " + etiqueta + " guardado en '" + destino + "'.",
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