#include "include/extern.h"

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
                sum += a[i][k] * b[k][j];
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
