# Speculative Execution and Rollback

The speculative execution in our processor uses a gradual rollback mechanism to handle mispredicted branches and exceptions. While increasing misprediction penalties, this design simplifies code, allow infinite prediction depth and reduce hardware area since snapshots of the entire processor state are not required.

## Speculative

when a hit in the BTB occurs, the fetch unit speculatively fetches instructions from the predicted target address. The predict state and predicted pc is passed down in the frontend pipeline stages (fetch, decode, dispatch) alongside this branch instruction.

these info will be passed to the branch unit in the backend. The branch unit will verify the prediction when the branch instruction is executed. If the prediction is correct, the processor continues execution as normal. If the prediction is incorrect, the branch unit will signal a misprediction and provide the correct target address.

## Rollback

Upon receiving a misprediction signal from the branch unit, the processor initiates a rollback process. The rollback mechanism involves the following steps:

1. **Flush Pipeline**: The processor flushes all instructions that are in-flight in the pipeline stages (fetch, decode, dispatch, execute) that were fetched after the mispredicted branch.

Notably, the Issue Buffers need partial flush, remove only the instructions that are younger than the mispredicted branch instruction, while keeping the older instructions intact.
2. **Restore State**: 
In the frontend, the fetch unit overwrites the program counter (PC) to the correct target address.
In the backend, we need to restore the Register Alias Table (RAT) and Free List to their states prior to the mispredicted branch. Instead of the snapshot way, we roll back instrustions from the tail of the Reorder Buffer (ROB) one by one until we reach the mispredicted branch instruction. For each instruction being rolled back, we update the RAT and Free List accordingly using the information stored in the ROB entries.
For every instruction being rolled back, we perform the following updates:

   - **RAT Update**: The RAT entry for the logical destination register is restored to point to the previous physical register (stale physical destination) that was mapped before the instruction was dispatched.
   - **Free List Update**: The physical register that was allocated for the instruction's destination is returned to the Free List, making it available for future allocations.

In this stage, though dispatch of new instructions is paused, the backend continues to process and retire instructions, while the frontend continue to fetch instructions from the correct target address. Hopefully, this allows the pipeline to drain faster and reduces the overall penalty of mispredictions.

3. **Resume Execution**: After restoring the state, the processor resumes instruction fetching from the correct target address, continuing normal execution.
