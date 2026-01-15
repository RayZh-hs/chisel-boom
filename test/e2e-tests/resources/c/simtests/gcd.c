#include "include/extern.h"

int gcd(int x, int y) {
  if (x % y == 0)
    return y;
  else
    return gcd(y, x % y);
}

int main() {
  put(gcd(10, 1));
  put(gcd(34986, 3087));
  put(gcd(2907, 1539));
  return 0;  // 178
}