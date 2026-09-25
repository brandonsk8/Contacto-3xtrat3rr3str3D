package com.company.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * Contenedor de cuartetas generadas durante la traduccion de un programa
 * (sin importar de cual de los 3 lenguajes provenga). Tambien centraliza
 * la generacion de nombres de temporales (t0, t1, ...) y etiquetas
 * (L0, L1, ...) para que no se dupliquen entre visitantes.
 */
public class TablaCuartetas {

    private final List<Cuarteta> cuartetas = new ArrayList<>();
    private int contadorTemporales = 0;
    private int contadorEtiquetas = 0;

    public String nuevoTemporal() {
        return "t" + (contadorTemporales++);
    }

    public String nuevaEtiqueta() {
        return "L" + (contadorEtiquetas++);
    }

    public void agregar(Cuarteta cuarteta) {
        cuartetas.add(cuarteta);
    }

    public void agregar(String operador, String arg1, String arg2, String resultado) {
        agregar(new Cuarteta(operador, arg1, arg2, resultado));
    }

    public List<Cuarteta> getCuartetas() {
        return cuartetas;
    }

    public void imprimir() {
        for (Cuarteta c : cuartetas) {
            System.out.println(c);
        }
    }
}
