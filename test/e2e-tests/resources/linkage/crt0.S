.section .text.init
.global _start

_start:
    # Initialize stack pointer
    # Assuming memory size is 4KB (0x1000)
    li sp, 0x1000
    
    # Call main
    call main

    # After main returns, write the return value (a0) to a special address
    # Let's use 0xFFFFFFF0 as the "tohost" address for simulation exit
    li t0, 0xFFFFFFF0
    sw a0, 0(t0)

loop:
    j loop
