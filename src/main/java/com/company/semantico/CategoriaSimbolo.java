package com.company.semantico;

/**
 * Que "rol" cumple un simbolo dentro de su ambito. Sirve para dar mensajes
 * de error mas claros (ej. "ya existe una variable llamada X" vs "ya existe
 * una funcion llamada X") y para que el Visitor sepa que hacer con cada uno.
 */
public enum CategoriaSimbolo {
    VARIABLE,
    PARAMETRO,
    CAMPO,        // atributo de una estructura (Y?) o de una clase (Zetariano)
    FUNCION,      // Y?: definida suelta con 'definir' en %funciones
    METODO,       // Zetariano: definido dentro de una clase
    CONSTRUCTOR,  // Zetariano
    ESTRUCTURA,   // Y?: definida con 'estructura' en %estructuras
    CLASE         // Zetariano: 'public class X { ... }'
}
