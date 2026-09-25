/* Generado automaticamente por GeneradorC a partir de las cuartetas de main.
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

struct Nodo;
struct Pila;

struct Nodo {
    int dato;
    struct Nodo* siguiente;
};

struct Pila {
    struct Nodo* cima;
    int tamanio;
};

struct Nodo* Nodo_crear_1(int dato1);
int Nodo_getDato_0(struct Nodo* this);
void Nodo_setDato_1(struct Nodo* this, int dato1);
struct Nodo* Nodo_getSiguiente_0(struct Nodo* this);
void Nodo_setSiguiente_1(struct Nodo* this, struct Nodo* siguiente1);
struct Pila* Pila_crear_0();
void Pila_apilar_1(struct Pila* this, int dato);
int Pila_desapilar_0(struct Pila* this);
int Pila_obtenerCima_0(struct Pila* this);
int Pila_estaVacia_0(struct Pila* this);
int Pila_obtenerTamanio_0(struct Pila* this);
char* Pila_toString_0(struct Pila* this);
void imprimirBienvenida();
int main(void);

struct Nodo* Nodo_crear_1(int dato1) {
    struct Nodo* this = calloc(1, sizeof(struct Nodo));
    this->dato = dato1;
    this->siguiente = NULL;
    return this;
}

int Nodo_getDato_0(struct Nodo* this) {
    return this->dato;
}

void Nodo_setDato_1(struct Nodo* this, int dato1) {
    this->dato = dato1;
}

struct Nodo* Nodo_getSiguiente_0(struct Nodo* this) {
    return this->siguiente;
}

void Nodo_setSiguiente_1(struct Nodo* this, struct Nodo* siguiente1) {
    this->siguiente = siguiente1;
}

struct Pila* Pila_crear_0() {
    struct Pila* this = calloc(1, sizeof(struct Pila));
    this->cima = NULL;
    this->tamanio = 0;
    return this;
}

void Pila_apilar_1(struct Pila* this, int dato) {
    struct Nodo* t0;
    struct Nodo* nuevo;
    t0 = Nodo_crear_1(dato);
    nuevo = t0;
    Nodo_setSiguiente_1(nuevo, this->cima);
    this->cima = nuevo;
    this->tamanio++;
}

int Pila_desapilar_0(struct Pila* this) {
    int t1;
    int dato;
    struct Nodo* t2;
    t1 = Nodo_getDato_0(this->cima);
    dato = t1;
    t2 = Nodo_getSiguiente_0(this->cima);
    this->cima = t2;
    this->tamanio--;
    return dato;
}

int Pila_obtenerCima_0(struct Pila* this) {
    int t3;
    int t4;
    int t5;
    t3 = Pila_estaVacia_0(this);
    if (!(t3)) goto L1;
    t4 = -1;
    return t4;
    goto L0;
    L1: ;
    L0: ;
    t5 = Nodo_getDato_0(this->cima);
    return t5;
}

int Pila_estaVacia_0(struct Pila* this) {
    int t6;
    t6 = this->cima == NULL;
    return t6;
}

int Pila_obtenerTamanio_0(struct Pila* this) {
    return this->tamanio;
}

char* Pila_toString_0(struct Pila* this) {
    char* resultado;
    struct Nodo* actual;
    int t7;
    int t8;
    char* t9;
    struct Nodo* t10;
    int t11;
    char* t12;
    struct Nodo* t13;
    resultado = "Pila [Cima -> Base]: ";
    actual = this->cima;
    L2: ;
    t7 = actual != NULL;
    if (!(t7)) goto L3;
    t8 = Nodo_getDato_0(actual);
    t9 = c3d_concat(resultado, c3d_entero_a_cadena(t8));
    resultado = t9;
    t10 = Nodo_getSiguiente_0(actual);
    t11 = t10 != NULL;
    if (!(t11)) goto L5;
    t12 = c3d_concat(resultado, ", ");
    resultado = t12;
    goto L4;
    L5: ;
    L4: ;
    t13 = Nodo_getSiguiente_0(actual);
    actual = t13;
    goto L2;
    L3: ;
    return resultado;
}

void imprimirBienvenida() {
    printf("%s\n", "------------------------------------------");
    printf("%s\n", "Este es mi primer programa a bajo nivel :D");
    printf("%s\n", "With <3 by IGriega");
    printf("%s\n", "------------------------------------------");
}

int main(void) {
    struct Pila* t14;
    struct Pila* pila;
    int t15;
    int opcion;
    int lectura;
    int t16;
    int t17;
    int t18;
    int t19;
    int t20;
    char* t21;
    int t22;
    t14 = Pila_crear_0();
    pila = t14;
    t15 = -1;
    opcion = t15;
    lectura = 0;
    imprimirBienvenida();
    L6: ;
    t16 = opcion != 4;
    if (!(t16)) goto L7;
    printf("%s\n", "-----------------------------------------------");
    printf("%s\n", "Ingresa la accion: \n");
    printf("%s\n", "1. Ingresar en pila \n");
    printf("%s\n", "2. Sacar de pila \n");
    printf("%s\n", "3. Imprimir pila \n");
    printf("%s\n", "4. Salir \n");
    printf("%s\n", "-----------------------------------------------");
    scanf("%d", &opcion);
    t17 = opcion == 1;
    if (!(t17)) goto L9;
    printf("%s\n", "Ingresa el numero: \n");
    scanf("%d", &lectura);
    Pila_apilar_1(pila, lectura);
    goto L8;
    L9: ;
    t18 = opcion == 2;
    if (!(t18)) goto L10;
    t19 = Pila_desapilar_0(pila);
    lectura = t19;
    printf("%s\n", "Elemento desapilado: ");
    printf("%d\n", lectura);
    goto L8;
    L10: ;
    t20 = opcion == 3;
    if (!(t20)) goto L11;
    t21 = Pila_toString_0(pila);
    printf("%s\n", t21);
    goto L8;
    L11: ;
    t22 = opcion == 4;
    if (!(t22)) goto L12;
    printf("%s\n", "Fin del programa");
    goto L8;
    L12: ;
    L8: ;
    printf("%s\n", "Ingresa cualquier tecla para continuar ");
    scanf("%d", &lectura);
    goto L6;
    L7: ;
    return 0;
}

