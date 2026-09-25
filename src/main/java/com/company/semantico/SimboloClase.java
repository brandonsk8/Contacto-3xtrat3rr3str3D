package com.company.semantico;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simbolo para una clase de Zetariano (un archivo .z = una clase publica).
 * A diferencia de una estructura de Y?, una clase tiene campos, metodos
 * (con sobrecarga: varios metodos pueden compartir nombre, se guardan en
 * una lista por nombre) y constructores (tambien puede haber varios, por
 * sobrecarga).
 */
public class SimboloClase extends Simbolo {

    private final Map<String, Simbolo> campos = new LinkedHashMap<>();
    private final Map<String, List<SimboloInvocable>> metodos = new LinkedHashMap<>();
    private final List<SimboloInvocable> constructores = new ArrayList<>();

    public SimboloClase(String nombre, int linea, int columna) {
        super(nombre, Tipo.clase(nombre), CategoriaSimbolo.CLASE, linea, columna);
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

    /**
     * @return true si se agrego; false si ya existe un metodo con el
     *         mismo nombre Y los mismos tipos de parametro (misma firma).
     */
    public boolean agregarMetodo(SimboloInvocable metodo) {
        List<SimboloInvocable> sobrecargas = metodos.computeIfAbsent(metodo.getNombre(), k -> new ArrayList<>());
        List<Tipo> tipos = tiposDe(metodo);
        for (SimboloInvocable existente : sobrecargas) {
            if (existente.mismaFirma(tipos)) {
                return false;
            }
        }
        sobrecargas.add(metodo);
        return true;
    }

    public List<SimboloInvocable> buscarMetodos(String nombre) {
        return metodos.getOrDefault(nombre, Collections.emptyList());
    }

    public Map<String, List<SimboloInvocable>> getMetodos() {
        return metodos;
    }

    /** @return true si se agrego; false si ya existe un constructor con la misma firma. */
    public boolean agregarConstructor(SimboloInvocable constructor) {
        List<Tipo> tipos = tiposDe(constructor);
        for (SimboloInvocable existente : constructores) {
            if (existente.mismaFirma(tipos)) {
                return false;
            }
        }
        constructores.add(constructor);
        return true;
    }

    public List<SimboloInvocable> getConstructores() {
        return constructores;
    }

    private static List<Tipo> tiposDe(SimboloInvocable invocable) {
        List<Tipo> tipos = new ArrayList<>();
        for (Simbolo p : invocable.getParametros()) {
            tipos.add(p.getTipo());
        }
        return tipos;
    }
}
