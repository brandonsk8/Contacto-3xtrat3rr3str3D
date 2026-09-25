/* Generado automaticamente por GeneradorC a partir de las cuartetas de puntos.
 * Incrementos 1+2+3+4+5 del backend: funciones Y? (primitivas y/o con
 * estructuras planas/anidadas/auto-referenciadas), clases/objetos de
 * Zetariano (constructores, metodos, 'this', 'new', llamadas encadenadas) y
 * cadenas/'concat'. Solo los arreglos quedan pendientes (bloqueados por el
 * front end) - ver README y GeneradorC.getNotas() para lo que se omitio y
 * por que. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct Punto;

struct Punto {
    int x;
    int y;
};

struct Punto sumarPuntos(struct Punto a, struct Punto b);

struct Punto sumarPuntos(struct Punto a, struct Punto b) {
    struct Punto resultado = {0};
    int t0;
    int t1;
    t0 = a.x + b.x;
    resultado.x = t0;
    t1 = a.y + b.y;
    resultado.y = t1;
    return resultado;
}

