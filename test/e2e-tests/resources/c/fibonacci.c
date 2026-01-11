#include "include/extern.h"

int fib(int n) {
    if (n <= 1) return n;
    return fib(n - 1) + fib(n - 2);
}

int main() {
    put(fib(6));    // 8
    return 0;
}
