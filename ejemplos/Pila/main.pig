import Nodo.z
import Pila.z
import utils.utils.y

VARIABILES>
esto pila : novus Pila();
esto opcion : numerus -1;
esto lectura : numerus 0;

MAIOR>
imprimirBienvenida();

dum (opcion != 4) {
    >> "-----------------------------------------------"
    >> "Ingresa la accion: \n";
    >> "1. Ingresar en pila \n";
    >> "2. Sacar de pila \n";
    >> "3. Imprimir pila \n";
    >> "4. Salir \n";
    >> "-----------------------------------------------"
    opcion <<;
    si (opcion == 1) {
        >> "Ingresa el numero: \n";
        lectura <<;
        pila.apilar(lectura);
    } aliter (opcion == 2 ) {
        lectura = pila.desapilar();
        >> "Elemento desapilado: ";
        >> lectura;
    } aliter (opcion == 3 ) {
        >> pila.toString();
    } aliter (opcion == 4 ) {
        >> "Fin del programa"
    } finis;

    >> "Ingresa cualquier tecla para continuar "
    lectura <<;

} finis;

FINIS;