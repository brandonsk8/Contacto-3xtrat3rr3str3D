package com.company.semantico;

/**
 * Representa una entrada en una tabla de simbolos: una variable, un
 * parametro, un campo, o el nombre de una funcion/metodo/estructura/clase.
 *
 * Para funciones, metodos y constructores se usa la subclase
 * SimboloInvocable (necesitan lista de parametros y tipo de retorno);
 * para estructuras y clases se usan SimboloEstructura y SimboloClase
 * (necesitan la lista de campos, y en el caso de clases, tambien metodos
 * y constructores). El resto (variable, parametro, campo) no necesita mas
 * datos que nombre + tipo, asi que usan esta clase directamente.
 */
public class Simbolo {

    private final String nombre;
    private final Tipo tipo;
    private final CategoriaSimbolo categoria;
    private final int linea;
    private final int columna;

    /**
     * Tamaño (como texto, ej. "10") de un arreglo de Y? de 1 dimension
     * declarado con corchetes pegados al nombre ("entero miArray[10]"),
     * a diferencia de Zetariano/Pig Latin que usan 'new tipo[n]'/'series'
     * y guardan el tamaño en una cuarteta. Solo se usa cuando
     * tipo.getCategoria() es ARREGLO; null si no aplica o si el tamaño
     * no se pudo determinar como literal entero.
     */
    private String tamanioArreglo;

    public Simbolo(String nombre, Tipo tipo, CategoriaSimbolo categoria, int linea, int columna) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.categoria = categoria;
        this.linea = linea;
        this.columna = columna;
    }

    public String getTamanioArreglo() {
        return tamanioArreglo;
    }

    public void setTamanioArreglo(String tamanioArreglo) {
        this.tamanioArreglo = tamanioArreglo;
    }

    public String getNombre() {
        return nombre;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public CategoriaSimbolo getCategoria() {
        return categoria;
    }

    public int getLinea() {
        return linea;
    }

    public int getColumna() {
        return columna;
    }

    @Override
    public String toString() {
        return categoria + " " + nombre + " : " + tipo + " (linea " + linea + ")";
    }
}