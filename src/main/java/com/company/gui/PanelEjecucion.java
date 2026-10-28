package com.company.gui;

import com.company.riscv.InterpreteRiscV;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pestana "Ejecucion": corre el ensamblador RISC-V generado DENTRO de la
 * aplicacion (con InterpreteRiscV, sin RARS ni ninguna herramienta
 * externa). Muestra lo que imprime el programa y, cuando el programa pide
 * un dato (leer), se escribe en el campo de abajo y se envia con Enter.
 */
final class PanelEjecucion extends JPanel {

    private static final long LIMITE_INSTRUCCIONES = 2_000_000_000L;

    private final JTextArea salida = new JTextArea();
    private final JTextField entrada = new JTextField();
    private final JButton btnDetener = new JButton("Detener");
    private final JLabel estado = new JLabel(" ");

    private final BlockingQueue<String> lineasEntrada = new LinkedBlockingQueue<>();
    private volatile InterpreteRiscV actual;
    private volatile boolean esperando = false;

    // Consola de salida con campo de entrada para read().
    PanelEjecucion() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        salida.setEditable(false);
        salida.setFont(Tema.fuenteCodigo(13));
        salida.setLineWrap(true);
        add(new JScrollPane(salida), BorderLayout.CENTER);

        JPanel abajo = new JPanel(new BorderLayout(4, 4));
        abajo.add(new JLabel("Entrada (Enter para enviar): "), BorderLayout.WEST);
        abajo.add(entrada, BorderLayout.CENTER);
        btnDetener.setEnabled(false);
        btnDetener.addActionListener(e -> detener());
        abajo.add(btnDetener, BorderLayout.EAST);
        JPanel sur = new JPanel(new BorderLayout());
        sur.add(abajo, BorderLayout.NORTH);
        sur.add(estado, BorderLayout.SOUTH);
        add(sur, BorderLayout.SOUTH);

        aplicarTema();
        entrada.addActionListener(e -> {
            String s = entrada.getText();
            entrada.setText("");
            if (actual == null) {
                return;
            }
            // eco de lo escrito, como en una terminal
            salida.append(s + "\n");
            lineasEntrada.add(s);
        });
    }

    /** Muestra un aviso en la consola de ejecucion (sin ejecutar nada). */
    void mostrarMensaje(String texto) {
        detener();
        salida.setText(texto + "\n");
    }

    /** Reaplica los colores de la paleta actual (al cambiar de tema). */
    void aplicarTema() {
        salida.setBackground(Tema.consolaFondo);
        salida.setForeground(Tema.texto);
        salida.setCaretColor(Tema.texto);
        entrada.setBackground(Tema.editorFondo);
        entrada.setForeground(Tema.texto);
        entrada.setCaretColor(Tema.texto);
        estado.setForeground(Tema.textoSuave);
    }

    /** Ensambla y ejecuta 'asm' en un hilo aparte (la ventana sigue viva). */
    void ejecutar(String asm) {
        detener();
        salida.setText("");
        lineasEntrada.clear();
        InterpreteRiscV interprete = new InterpreteRiscV(
                texto -> SwingUtilities.invokeLater(() -> {
                    salida.append(texto);
                    salida.setCaretPosition(salida.getDocument().getLength());
                }),
                this::pedirLinea);
        try {
            interprete.cargar(asm);
        } catch (InterpreteRiscV.ErrorRiscV ex) {
            salida.setText("Error al ensamblar" + (ex.linea > 0 ? " (linea " + ex.linea + ")" : "") + ": " + ex.getMessage() + "\n");
            return;
        }
        actual = interprete;
        btnDetener.setEnabled(true);
        estado.setText("Ejecutando...");
        entrada.requestFocusInWindow();
        Thread hilo = new Thread(() -> {
            InterpreteRiscV.Resultado r = interprete.ejecutar(LIMITE_INSTRUCCIONES);
            SwingUtilities.invokeLater(() -> {
                if (actual != interprete) {
                    return; // ya se lanzo otra ejecucion encima
                }
                actual = null;
                btnDetener.setEnabled(false);
                if (r.terminoBien) {
                    salida.append("\n--- Programa terminado (" + r.instrucciones + " instrucciones) ---\n");
                    estado.setText("Terminado");
                } else {
                    salida.append("\n--- Error de ejecucion: " + r.error + " ---\n");
                    estado.setText("Terminado con error");
                }
                salida.setCaretPosition(salida.getDocument().getLength());
            });
        }, "ejecucion-riscv");
        hilo.setDaemon(true);
        hilo.start();
    }

    // Interrumpe la ejecucion en curso.
    void detener() {
        InterpreteRiscV i = actual;
        if (i != null) {
            i.cancelar();
            lineasEntrada.add(""); // por si estaba esperando una linea
        }
    }

    /** Llamado por el interprete (en su hilo) cuando el programa lee: espera una linea escrita por el usuario. */
    private String pedirLinea() {
        SwingUtilities.invokeLater(() -> {
            estado.setText("El programa espera un dato: escribelo abajo y pulsa Enter");
            entrada.requestFocusInWindow();
        });
        esperando = true;
        try {
            while (true) {
                String s = lineasEntrada.poll(200, TimeUnit.MILLISECONDS);
                if (s != null) {
                    return s;
                }
                InterpreteRiscV i = actual;
                if (i == null) {
                    return null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            esperando = false;
            SwingUtilities.invokeLater(() -> estado.setText("Ejecutando..."));
        }
    }
}
