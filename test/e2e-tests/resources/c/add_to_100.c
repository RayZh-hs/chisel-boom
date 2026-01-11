#include "include/extern.h"

int main() {
    int n = 0;
    for (int i = 1; i <= 100; i++) {
        n += i;
    }
    put(n); // 5050
    return 0;
}