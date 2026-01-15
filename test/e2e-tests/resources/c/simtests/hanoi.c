#include "include/extern.h"

int cd(int d, char *a, char *b, char *c, int sum) {
  if (d == 1) {
    sum++;
  } else {
    sum = cd(d - 1, a, c, b, sum);
    sum = cd(d - 1, b, a, c, sum);
    sum++;
  }
  return sum;
}

int main() {
  char a[5] = "A";
  char b[5] = "B";
  char c[5] = "C";
  int d = 10;
  int sum = cd(d, a, b, c, 0);
  put(sum);
  return 0;  // 20
}
