package com.company.semantico;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registro de todo lo definido "a nivel de archivo" en cada .y/.z que un
 * programa Pig Latin importa: estructuras y funciones sueltas (de
 * archivos .y) y clases (de archivos .z). No es el ambito de un archivo
 * en particular (eso es TablaSimbolos) - es el catalogo compartido que le
 * permite a un .pig, o a otro .y/.z, resolver el tipo "Nodo" o la llamada
 * "pila.apilar(...)" sin importar en que archivo se definieron.
 *
 * La idea es llenarlo en una primera pasada (una por cada archivo
 * importado) antes de analizar el cuerpo de main.pig, para que el orden
 * de los imports no importe.
 */
public class TablaSimbolosGlobal {

    private final Map<String, SimboloEstructura> estructuras = new LinkedHashMap<>();
    private final Map<String, SimboloClase> clases = new LinkedHashMap<>();
    private final Map<String, SimboloInvocable> funciones = new LinkedHashMap<>();

    /** @return true si se registro; false si ya existia una estructura o clase con ese nombre. */
    public boolean registrarEstructura(SimboloEstructura estructura) {
        if (existeTipo(estructura.getNombre())) {
            return false;
        }
        estructuras.put(estructura.getNombre(), estructura);
        return true;
    }

    /** @return true si se registro; false si ya existia una estructura o clase con ese nombre. */
    public boolean registrarClase(SimboloClase clase) {
        if (existeTipo(clase.getNombre())) {
            return false;
        }
        clases.put(clase.getNombre(), clase);
        return true;
    }

    /** @return true si se registro; false si ya existia una funcion suelta con ese nombre. */
    public boolean registrarFuncion(SimboloInvocable funcion) {
        if (funciones.containsKey(funcion.getNombre())) {
            return false;
        }
        funciones.put(funcion.getNombre(), funcion);
        return true;
    }

    /**
     * true si ya existe una estructura o una clase con este nombre (los
     * dos catalogos comparten un solo espacio de nombres de tipos: no
     * puede haber una estructura y una clase con el mismo nombre).
     */
    public boolean existeTipo(String nombre) {
        return estructuras.containsKey(nombre) || clases.containsKey(nombre);
    }

    public SimboloEstructura buscarEstructura(String nombre) {
        return estructuras.get(nombre);
    }

    public SimboloClase buscarClase(String nombre) {
        return clases.get(nombre);
    }

    public SimboloInvocable buscarFuncion(String nombre) {
        return funciones.get(nombre);
    }

    /**
     * Resuelve un nombre de tipo (usado, por ejemplo, en Pig Latin en
     * 'series x[5] : Nodo' o en un campo Zetariano 'Nodo siguiente;') a
     * un Tipo, buscando primero en estructuras y despues en clases.
     *
     * @return el Tipo correspondiente, o null si el nombre no esta
     *         registrado como ninguna estructura ni clase (tipo
     *         inexistente: error semantico que el Visitor debe reportar).
     */
    public Tipo resolverTipo(String nombre) {
        if (estructuras.containsKey(nombre)) {
            return Tipo.estructura(nombre);
        }
        if (clases.containsKey(nombre)) {
            return Tipo.clase(nombre);
        }
        return null;
    }

    public Map<String, SimboloEstructura> getEstructuras() {
        return estructuras;
    }

    public Map<String, SimboloClase> getClases() {
        return clases;
    }

    public Map<String, SimboloInvocable> getFunciones() {
        return funciones;
    }
}
