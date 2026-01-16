# Hex Tests

These tests are written in RISC-V assembly and are not part of the test suite. Their main purpose is to test the cpu's core functionality executing minimal programs.

Therefore, the mango script `mango hex [compile|simulate] <testname>` can be manually called to compile and simulate these tests. Note that the dump and hex files will be generated alongside the assembly files for simpler debugging.