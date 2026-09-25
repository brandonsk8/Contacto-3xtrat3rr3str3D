/* Generado automaticamente por GeneradorC a partir de las cuartetas de contador.
 * Incrementos 1+2+3+4+5 del backend: funciones Y? (primitivas y/o con
 * estructuras planas/anidadas/auto-referenciadas), clases/objetos de
 * Zetariano (constructores, metodos, 'this', 'new', llamadas encadenadas) y
 * cadenas/'concat'. Solo los arreglos quedan pendientes (bloqueados por el
 * front end) - ver README y GeneradorC.getNotas() para lo que se omitio y
 * por que. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct Contador;

struct Contador {
    int valor;
    struct Contador* siguiente;
};

struct Contador* Contador_crear_1(int inicial);
void Contador_incrementar_0(struct Contador* this);
void Contador_incrementarDosVeces_0(struct Contador* this);
int Contador_obtenerValor_0(struct Contador* this);
void Contador_enlazarCon_1(struct Contador* this, struct Contador* otro);
int Contador_valorDelSiguiente_0(struct Contador* this);
int Contador_valorEnlazado_0(struct Contador* this);
struct Contador* Contador_crearVinculado_1(struct Contador* this, int v);

struct Contador* Contador_crear_1(int inicial) {
    struct Contador* this = calloc(1, sizeof(struct Contador));
    this->valor = inicial;
    return this;
}

void Contador_incrementar_0(struct Contador* this) {
    int t0;
    t0 = this->valor + 1;
    this->valor = t0;
}

void Contador_incrementarDosVeces_0(struct Contador* this) {
    Contador_incrementar_0(this);
    Contador_incrementar_0(this);
}

int Contador_obtenerValor_0(struct Contador* this) {
    return this->valor;
}

void Contador_enlazarCon_1(struct Contador* this, struct Contador* otro) {
    this->siguiente = otro;
}

int Contador_valorDelSiguiente_0(struct Contador* this) {
    return this->siguiente->valor;
}

int Contador_valorEnlazado_0(struct Contador* this) {
    int t1;
    t1 = Contador_obtenerValor_0(this->siguiente);
    return t1;
}

struct Contador* Contador_crearVinculado_1(struct Contador* this, int v) {
    struct Contador* t2;
    t2 = Contador_crear_1(v);
    return t2;
}

