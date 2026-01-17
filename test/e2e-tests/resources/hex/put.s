# put.s: Put values into mmio
.section .text.init
.global _start

_start:
    li a0, 2048
    call put
    li a0, 0
    li t0, 0xFFFFFFFF
    sb a0, 0(t0)

put:
    li t0, 0x80000000
    sw a0, 0(t0)
    ret
