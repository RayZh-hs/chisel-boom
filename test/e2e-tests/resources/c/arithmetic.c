int main() {
    int a = 10;
    int b = 20;
    int c = a + b; // 30
    int d = c - 5; // 25
    int e = d & 0xF; // 25 & 15 = 9
    int f = e | 0x10; // 9 | 16 = 25
    return f;
}
