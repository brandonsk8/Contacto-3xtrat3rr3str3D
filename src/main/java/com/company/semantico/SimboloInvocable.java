package com.company.semantico;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simbolo para todo lo "llamable": funciones sueltas de Y? (categoria
 * FUNCION), metodos de Zetariano (METODO) y constructores de Zetariano
 * (CONSTRUCTOR). Los tres necesitan lo mismo: una lista de parametros
 * (con su propio tipo, para poder validar cantidad y tipo de argumentos
 * en una llamada) y un tipo de retorno (VOID para constructores y para
 * funciones/metodos que no retornan nada).
 *
 * ambitoLocal empieza en null y lo llena el Visitor cuando recorre el
 * cuerpo de la funcion/metodo: ese ambito ya trae los parametros
 * declarados, y sirve como padre para las variables locales del cuerpo.
 */
public class SimboloInvocable extends Simbolo {

    private final List<Simbolo> parametros;
    private final Tipo tipoRetorno;
    private TablaSimbolos ambitoLocal;

    /** Solo METODO (Parte 2): true si llevaba '@Override'. */
    private boolean marcadoOverride;

    public SimboloInvocable(String nombre, CategoriaSimbolo categoria, List<Simbolo> parametros,
                             Tipo tipoRetorno, int linea, int columna) {
        super(nombre, tipoRetorno, categoria, linea, columna);
        this.parametros = parametros != null ? parametros : new ArrayList<>();
        this.tipoRetorno = tipoRetorno != null ? tipoRetorno : Tipo.VOID;
    }

    public List<Simbolo> getParametros() {
        return Collections.unmodifiableList(parametros);
    }

    public Tipo getTipoRetorno() {
        return tipoRetorno;
    }

    public TablaSimbolos getAmbitoLocal() {
        return ambitoLocal;
    }

    public void setAmbitoLocal(TablaSimbolos ambitoLocal) {
        this.ambitoLocal = ambitoLocal;
    }

    public boolean isMarcadoOverride() {
        return marcadoOverride;
    }

    public void setMarcadoOverride(boolean marcadoOverride) {
        this.marcadoOverride = marcadoOverride;
    }

    /**
     * Compara solo por cantidad y tipo de parametros (sirve para
     * detectar sobrecargas duplicadas en Zetariano al agregar un metodo
     * o constructor con la misma firma que uno ya existente).
     */
    /**
     * Para RESOLVER una llamada (no para detectar duplicados): cada argumento
     * debe poder asignarse al parametro correspondiente, lo que incluye pasar
     * una subclase donde se espera la superclase (polimorfismo) y un entero
     * donde se espera un decimal.
     */
    public boolean aceptaArgumentos(List<Tipo> tiposArgumentos, TablaSimbolosGlobal global) {
        if (parametros.size() != tiposArgumentos.size()) {
            return false;
        }
        for (int i = 0; i < parametros.size(); i++) {
            Tipo arg = tiposArgumentos.get(i);
            if (arg == null || !parametros.get(i).getTipo().esCompatibleConPolimorfismo(arg, global)) {
                return false;
            }
        }
        return true;
    }

    public boolean mismaFirma(List<Tipo> tiposArgumentos) {
        if (parametros.size() != tiposArgumentos.size()) {
            return false;
        }
        for (int i = 0; i < parametros.size(); i++) {
            if (!parametros.get(i).getTipo().equals(tiposArgumentos.get(i))) {
                return false;
            }
        }
        return true;
    }
}
