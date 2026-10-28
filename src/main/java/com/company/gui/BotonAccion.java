package com.company.gui;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Boton de la barra de herramientas: esquinas redondeadas, color de acento si es el principal, sigue el tema. */
final class BotonAccion extends JButton {

    private final boolean principal;

    // Boton propio (pintado a mano) para que se vea igual en tema claro y oscuro.
    BotonAccion(String texto, boolean principal) {
        super(texto);
        this.principal = principal;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.stringWidth(getText()) + 28, 30);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color fondo = principal ? Tema.acento : Tema.panel;
        if (getModel().isRollover()) {
            fondo = fondo.brighter();
        }
        if (getModel().isPressed()) {
            fondo = fondo.darker();
        }
        g.setColor(fondo);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
        if (!principal) {
            g.setColor(Tema.borde);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
        }
        g.setColor(principal ? Color.WHITE : Tema.texto);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
    }
}
