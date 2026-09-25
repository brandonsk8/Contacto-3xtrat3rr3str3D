package com.company.semantico;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simbolo para una 'estructura' de Y? (definida dentro de
 * %estructuras). Solo tiene campos (nombre -> tipo); Y? no permite
 * metodos dentro de una estructura, son puramente datos (a diferencia de
 * una clase de Zetariano, que si puede tener metodos y constructores).
 *
 * Se usa LinkedHashMap para conservar el orden de declaracion de los
 * campos: hace falta para el orden de inicializacion en
 * '{' valor1, valor2, ... '}' y, mas adelante, para el layout del struct
 * en el codigo C generado.
 */
public class SimboloEstructura extends Simbolo {

    private final Map<String, Simbolo> campos = new LinkedHashMap<>();

    public SimboloEstructura(String nombre, int linea, int columna) {
        super(nombre, Tipo.estructura(nombre), CategoriaSimbolo.ESTRUCTURA, linea, columna);
    }

    /** @return true si se agrego; false si ya existia un campo con ese nombre. */
    public boolean agregarCampo(Simbolo campo) {
        if (campos.containsKey(campo.getNombre())) {
            return false;
        }
        campos.put(campo.getNombre(), campo);
        return true;
    }

    public Simbolo buscarCampo(String nombre) {
        return campos.get(nombre);
    }

    public Map<String, Simbolo> getCampos() {
        return campos;
    }
}
