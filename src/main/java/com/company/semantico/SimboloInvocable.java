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

    /**
     * Compara solo por cantidad y tipo de parametros (sirve para
     * detectar sobrecargas duplicadas en Zetariano al agregar un metodo
     * o constructor con la misma firma que uno ya existente).
     */
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
