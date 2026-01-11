#include "include/extern.h"

int main() {
    int x = 10;
    int y = 20;
    int z = 0;
    if (x < y) {
        z = 1;
    } else {
        z = 2;
    }
    
    if (x > y) {
        z += 10;
    } else {
        z += 20;
    }
    
    put(z); // 21
    return 0;
}
