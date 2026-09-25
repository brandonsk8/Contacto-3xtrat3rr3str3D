/* Generado automaticamente por GeneradorC a partir de las cuartetas de mensaje.
 * Incrementos 1+2+3+4+5 del backend: funciones Y? (primitivas y/o con
 * estructuras planas/anidadas/auto-referenciadas), clases/objetos de
 * Zetariano (constructores, metodos, 'this', 'new', llamadas encadenadas) y
 * cadenas/'concat'. Solo los arreglos quedan pendientes (bloqueados por el
 * front end) - ver README y GeneradorC.getNotas() para lo que se omitio y
 * por que. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char* c3d_concat(const char* a, const char* b) {
    size_t len = strlen(a) + strlen(b) + 1;
    char* r = malloc(len);
    snprintf(r, len, "%s%s", a, b);
    return r;
}
static char* c3d_entero_a_cadena(int v) {
    char* buf = malloc(16);
    snprintf(buf, 16, "%d", v);
    return buf;
}

struct Mensaje;

struct Mensaje {
    char* texto;
};

struct Mensaje* Mensaje_crear_1(char* inicial);
void Mensaje_agregarEntero_1(struct Mensaje* this, int valor);
void Mensaje_agregarTexto_1(struct Mensaje* this, char* extra);
char* Mensaje_obtenerTexto_0(struct Mensaje* this);
int Mensaje_esIgualA_1(struct Mensaje* this, char* otro);

struct Mensaje* Mensaje_crear_1(char* inicial) {
    struct Mensaje* this = calloc(1, sizeof(struct Mensaje));
    this->texto = inicial;
    return this;
}

void Mensaje_agregarEntero_1(struct Mensaje* this, int valor) {
    char* t0;
    t0 = c3d_concat(this->texto, c3d_entero_a_cadena(valor));
    this->texto = t0;
}

void Mensaje_agregarTexto_1(struct Mensaje* this, char* extra) {
    char* t1;
    t1 = c3d_concat(this->texto, extra);
    this->texto = t1;
}

char* Mensaje_obtenerTexto_0(struct Mensaje* this) {
    return this->texto;
}

int Mensaje_esIgualA_1(struct Mensaje* this, char* otro) {
    int t2;
    t2 = (strcmp(this->texto, otro) == 0);
    return t2;
}

