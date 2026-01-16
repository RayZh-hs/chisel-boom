.section .text
.global _start

_start:
    li a0, 0            # Initialize counter
    li t1, 4            # Set Recursion limit (Fixed: using t1, not ra)
    jal t2, recurse     # Call recurse, save link in t2 (Fixed: prevents clobbering by inner calls)

    # Halt the simulation
    li a0, 0
    li t0, -1           # 0xFFFFFFFF
    sw a0, 0(t0)        # Write to Halt Address

recurse:
    addi a0, a0, 1
    jal ra, put         # Call leaf function (uses standard ra)
    bne a0, t1, recurse # Loop until limit is reached
    jr t2               # Return to _start using t2

put:
    li t0, 0x80000000
    sw a0, 0(t0)        # Write to Output Address
    ret                 # Return using standard ra