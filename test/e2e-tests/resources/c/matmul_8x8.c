#include "include/extern.h"

// Software multiplication since M extension is not present and
// standard libraries are not linked.
int mul(int a, int b) {
    int res = 0;
    int neg = 0;
    if (a < 0) { a = -a; neg = !neg; }
    if (b < 0) { b = -b; neg = !neg; }
    
    while (b > 0) {
        if (b & 1) res += a;
        a <<= 1;
        b >>= 1;
    }
    return neg ? -res : res;
}

int main() {
    int a[8][8];
    int b[8][8];
    int c[8][8];
    int i, j, k;

    // Initialize matrices
    // A[i][j] = i + j
    // B[i][j] = i
    for (i = 0; i < 8; i++) {
        for (j = 0; j < 8; j++) {
            a[i][j] = i + j;
            b[i][j] = i;
        }
    }

    // Multiply C = A * B
    for (i = 0; i < 8; i++) {
        for (j = 0; j < 8; j++) {
            int sum = 0;
            for (k = 0; k < 8; k++) {
                // sum += a[i][k] * b[k][j]
                // Use software multiplication
                sum += mul(a[i][k], b[k][j]);
            }
            c[i][j] = sum;
        }
    }

    // Print result
    for (i = 0; i < 8; i++) {
        for (j = 0; j < 8; j++) {
            put(c[i][j]);
        }
    }

    return 0;
}
