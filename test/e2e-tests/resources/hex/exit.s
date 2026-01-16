# exit.s: Exit without errors
.section .text.init
.global _start

_start:
    li t0, 0xFFFFFFFF
    sw a0, 0(t0)
