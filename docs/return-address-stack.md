# Return Address Stack Design

We adopted a two-hierarchy branch predictor design.

- Level 1 (1-cycle): A BTB (Branch Target Buffer) predictor.
- Level 2 (2-cycle): A Return Address Stack (RAS) predictor.

The RAS predictor receives prediction requests from the ID stage and re-orientates the PC if detects a J-family instruction.

## Overview

- `components.structures.ReturnAddressStack` implements the RAS structure, which is a circular stack (wraps around when full).
- `components.frontend.RASAdaptor` connects the RAS to the frontend pipeline, handling request and response logic.

## Request Handling

An instruction is speculated as a CALL if:
1. It is a J instruction;
2. `rd` is not `x0`.

An instruction is speculated as a RET if:
1. It is a J instruction;
2. `rd` is `x1` to `x5`.

When the frontend receives a CALL instruction, it pushes the return address (PC + 4) onto the RAS. When it receives a RET instruction, it pops the top address from the RAS and uses it as the target address for the RET.

The RAS Adaptor is parallel to the ID Stage. It receives raw instructions and PCs from the Instruction Fetcher and takes one cycle (just like Decoder) to exectute the RAS logic. If it determines that the PC needs to be overwritten, it will trigger:
1. PC-Overwrite signal to the Instruction Fetcher (same when BRU identifies misprediction);
2. Reset signal to the IF/ID Queue Buffer;
3. Alter the pc coming from the Decoder to Dispatcher;
4. (PLANNED) When ID is split into two stages, clear the ID substage buffer.

The PC override is speculated as:
1. If the instruction is a CALL, the target is:
    - `PC + 4` if it is JALR;
    - `PC + imm` if it is JAL.
2. If the instruction is a RET, the target is the top address popped from the RAS.

Alongside the pc, every command will carry a `rasSP` (RAS Stack Pointer) to indicate the stack pointer of RAS (before the active inst is dispatched). This information will be stored in the ROB and used during rollback to restore the RAS state.

## Rollback Handling

When a misprediction is detected and a rollback is triggered, the RAS Adaptor will restore the RAS state using the `rasSP` stored in the ROB entry of the instruction that caused the misprediction.

The stored stack pointer directly overrides the current one, hopefully restoring the RAS to the correct state.

This reset signal takes precedence over any push/pop operations that may occur in the same cycle, which should not happen (it should be invalidated by the pipeline flush).

## Overflow Handling

When the RAS is full, the stack pointer wraps around to 0, effectively overwriting the oldest return address.
