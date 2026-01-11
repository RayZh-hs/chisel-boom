.section .text.init
.global _start

_start:
    # Initialize stack pointer
    # Assuming memory size is 4KB (0x1000)
    li sp, 0x1000
    
    # Call main
    call main

    # After main returns, write the return value (a0) to a special address
    # Address 0x80000008 corresponds to the Exit Device
    li t0, 0x80000008
    sw a0, 0(t0)

loop:
    j loop

.global put
put:
    li t0, 0x80000000
    sw a0, 0(t0)
    ret
