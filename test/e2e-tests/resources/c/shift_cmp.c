#include "include/extern.h"

int main() {
    unsigned int a = 0x12345678;
    unsigned int b = a << 4;  // 0x23456780
    unsigned int c = a >> 4;  // 0x01234567
    int d = -16;
    int e = d >> 2;           // -4 (arithmetic shift)
    
    int res = 0;
    if (b == 0x23456780) res += 1;
    if (c == 0x01234567) res += 2;
    if (e == -4) res += 4;
    
    unsigned int f = 10;
    unsigned int g = 20;
    if (f < g) res += 8;
    
    int h = -10;
    int i = 5;
    if (h < i) res += 16;
    
    put(res);   // 31
    return 0;
}