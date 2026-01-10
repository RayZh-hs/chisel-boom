# Memory Design

All non-wrapper functionality resides in the `components/backend/LSUPipeline.scala` file. The memory pipeline can be divided into three sub-stages:

## Load Store Queue

The Load Store Queue (LSQ) inherits a Sequential Issue Buffer. Only the top entry of the LSQ can be issued to later sub-stages.

If the top is a store command (and is ready), it will be broadcast immediately to the CDB. Issue will be blocked until ROB sends a commit signal for the store.

If the top is a load command (and is ready), it will be sent directly to the later sub-stages. Broadcast will occur in the last sub-stage.

## Load Store Operate

In this sub-stage, the PRFs are read and the accessed data alongside the imm values are operated to generate the effective address. This is piped to the next sub-stage.

## Load Store Writeback

Memories are layouted as `Vec(4, UInt(8.W)`. For each operation, 32 bits are accessed. In this stage, load/store commands are sent to the LSU to be executed.

For load commands, the data read will be broadcast to the CDB, marking it as ready.
