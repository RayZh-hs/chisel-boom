#include "include/extern.h"
int a[4];
int main() {
  int b[4];
  b[2] = 2;
  int *p;
  p = b;
  put(p[2]);
  return 0;  // 175
}
