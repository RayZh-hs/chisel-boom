#include "include/extern.h"

int main() {
    int a = 100;
    int b = 3;
    
    // Division
    // expect 33
    int q = a / b; 
    put(q);
    
    // Remainder
    // expect 1
    int r = a % b;
    put(r);

    // Signed division
    int c = -100;
    // expect -33
    int sc = c / b;
    put(sc);

    // Signed remainder
    // expect -1
    int sr = c % b;
    put(sr);

    return 0;
}
