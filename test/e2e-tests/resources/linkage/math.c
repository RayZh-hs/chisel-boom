
typedef int SItype __attribute__ ((mode (SI)));
typedef unsigned int USItype __attribute__ ((mode (SI)));

SItype __mulsi3 (SItype a, SItype b) {
    USItype ua = (USItype)a;
    USItype ub = (USItype)b;
    USItype res = 0;
    while (ub) {
        if (ub & 1) res += ua;
        ua <<= 1;
        ub >>= 1;
    }
    return (SItype)res;
}

USItype __udivsi3 (USItype n, USItype d) {
    USItype q = 0;
    USItype r = 0;
    for (int i = 31; i >= 0; i--) {
        r <<= 1;
        if ((n >> i) & 1) r |= 1;
        if (r >= d) {
            r -= d;
            q |= (1U << i);
        }
    }
    return q;
}

USItype __umodsi3 (USItype n, USItype d) {
    USItype r = 0;
    for (int i = 31; i >= 0; i--) {
        r <<= 1;
        if ((n >> i) & 1) r |= 1;
        if (r >= d) {
            r -= d;
        }
    }
    return r;
}

SItype __divsi3 (SItype n, SItype d) {
    int n_neg = n < 0;
    int d_neg = d < 0;
    USItype un = n_neg ? -n : n;
    USItype ud = d_neg ? -d : d;
    USItype uq = __udivsi3(un, ud);
    return (n_neg ^ d_neg) ? -uq : uq;
}

SItype __modsi3 (SItype n, SItype d) {
    int n_neg = n < 0;
    int d_neg = d < 0;
    USItype un = n_neg ? -n : n;
    USItype ud = d_neg ? -d : d;
    USItype ur = __umodsi3(un, ud);
    return n_neg ? -ur : ur;
}
