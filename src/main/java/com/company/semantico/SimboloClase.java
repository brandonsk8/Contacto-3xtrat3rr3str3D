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

    /**
     * Nombre de la superclase ('extends', Parte 2) o null. Se guarda solo el
     * nombre (no la referencia) porque los imports se procesan en orden: se
     * resuelve al vuelo contra TablaSimbolosGlobal.
     */
    private String nombreSuperclase;

    /** Paquete logico = carpeta (relativa al .pig que importa) donde vive el .z; "" si esta junto al .pig. */
    private String paquete = "";

    public String getPaquete() {
        return paquete;
    }

    public void setPaquete(String paquete) {
        this.paquete = paquete == null ? "" : paquete;
    }

    public SimboloClase(String nombre, int linea, int columna) {
        super(nombre, Tipo.clase(nombre), CategoriaSimbolo.CLASE, linea, columna);
    }

    public void setNombreSuperclase(String nombreSuperclase) {
        this.nombreSuperclase = nombreSuperclase;
    }

    public String getNombreSuperclase() {
        return nombreSuperclase;
    }

    public SimboloClase getSuperclase(TablaSimbolosGlobal global) {
        return nombreSuperclase != null ? global.buscarClase(nombreSuperclase) : null;
    }

    /** true si 'nombreClase' es esta clase o alguna de sus superclases. */
    public boolean esSubclaseDeOIgual(String nombreClase, TablaSimbolosGlobal global) {
        if (getNombre().equals(nombreClase)) {
            return true;
        }
        SimboloClase padre = getSuperclase(global);
        return padre != null && padre.esSubclaseDeOIgual(nombreClase, global);
    }

    /** Campo propio, o heredado subiendo por la cadena de 'extends'. */
    public Simbolo buscarCampoHeredado(String nombre, TablaSimbolosGlobal global) {
        Simbolo propio = buscarCampo(nombre);
        if (propio != null) {
            return propio;
        }
        SimboloClase padre = getSuperclase(global);
        return padre != null ? padre.buscarCampoHeredado(nombre, global) : null;
    }

    /** Sobrecargas propias mas las heredadas NO sobreescritas (misma firma). */
    public List<SimboloInvocable> buscarMetodosHeredados(String nombre, TablaSimbolosGlobal global) {
        List<SimboloInvocable> propios = new ArrayList<>(buscarMetodos(nombre));
        SimboloClase padre = getSuperclase(global);
        if (padre != null) {
            for (SimboloInvocable heredado : padre.buscarMetodosHeredados(nombre, global)) {
                boolean sobreescrito = false;
                for (SimboloInvocable p : propios) {
                    if (p.mismaFirma(tiposDe(heredado))) {
                        sobreescrito = true;
                        break;
                    }
                }
                if (!sobreescrito) {
                    propios.add(heredado);
                }
            }
        }
        return propios;
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
