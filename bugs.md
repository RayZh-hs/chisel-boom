# Found Bugs

1.  **InstructionMemory address bits mismatch**: In `InstructionMemory.scala`, the `readAddr` uses `IMEM_WIDTH + 2` bits, but `SyncReadMem` only has `1 << IMEM_WIDTH` entries. It should use `IMEM_WIDTH - 1` bits if it's indexing 32-bit words from a byte address, or more simply, just handle the width correctly based on the memory size.
2.  **IMEM_SIZE too small**: `IMEM_WIDTH = 8` (256 words = 1KB) is too small for some E2E tests like `fibonacci`.
3.  **Missing broadcast wake-up**: `IssueBuffer` and `SequentialIssueBuffer` do not check the current cycle's broadcast against the instruction currently being enqueued. If a dependency is satisfied in the same cycle as dispatch, the instruction might miss the update and wait forever.
4.  **Persistent flush required**: Pipeline stages in `ALUAdaptor`, `BRUAdaptor`, and `LoadStoreAdaptor` only check the flush signal combinatorially. If a stage is stalled during a flush, it might still proceed in the next cycle when the flush signal is gone.
5.  **InstructionMemory read latency**: `InstructionMemory` uses `SyncReadMem` which has 1-cycle latency. `InstFetcher` seems to handle it, but the address calculation in `InstructionMemory` was also slightly off.
