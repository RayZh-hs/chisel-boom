# Found Bugs

1. RAT defaults to `i => i` mapping, causing 0~31 registers to be locked at the start of simulation. A better default would be `i => 0`, marking all physical registers as free initially.
