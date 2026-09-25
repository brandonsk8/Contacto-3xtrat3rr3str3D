/* Generado automaticamente por GeneradorC a partir de las cuartetas de nodo-anidado.
 * Incrementos 1+2+3+4+5 del backend: funciones Y? (primitivas y/o con
 * estructuras planas/anidadas/auto-referenciadas), clases/objetos de
 * Zetariano (constructores, metodos, 'this', 'new', llamadas encadenadas) y
 * cadenas/'concat'. Solo los arreglos quedan pendientes (bloqueados por el
 * front end) - ver README y GeneradorC.getNotas() para lo que se omitio y
 * por que. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct Nodo;

struct Nodo {
    int valor;
    struct Nodo* siguiente;
};

int sumarCadena(struct Nodo n);

int sumarCadena(struct Nodo n) {
    int total;
    int t0;
    total = n.valor;
    t0 = total + n.siguiente->valor;
    total = t0;
    return total;
}

