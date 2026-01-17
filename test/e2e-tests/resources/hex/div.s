# div.s: Run a divide operation to test OOO capability
.section .text.init
.global _start

_start:
    # # A long divide operation
    # li a0, 20          # Dividend
    # li a1, 3           # Divisor
    # div a2, a0, a1     # Quotient
    # # A short add operation
    # li a3, 5
    # li a4, 10
    # add a5, a3, a4     # Sum
    # # Put add result
    # mv a0, a5
    # call put
    # # Put div result
    # mv a0, a2
    # call put
    # # End program
    li a0, 0
    li t0, 0xFFFFFFFF
    sb a0, 0(t0)

put:
    li t0, 0x80000000
    sw a0, 0(t0)
    ret
