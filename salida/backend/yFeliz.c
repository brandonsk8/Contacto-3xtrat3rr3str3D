/* Generado automaticamente por GeneradorC a partir de las cuartetas de yFeliz.
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

int sumar(int a, int b);
void procesar(int limite);

int sumar(int a, int b) {
    int t0;
    t0 = a + b;
    return t0;
}

void procesar(int limite) {
    int total;
    int i;
    int t1;
    int t2;
    int t3;
    total = 0;
    i = 0;
    L0: ;
    t1 = i < limite;
    if (!(t1)) goto L1;
    t2 = sumar(total, i);
    total = t2;
    i++;
    goto L0;
    L1: ;
    t3 = total > 100;
    if (!(t3)) goto L3;
    printf("%s\n", "mucho");
    goto L2;
    L3: ;
    printf("%s\n", "poco");
    L2: ;
}

