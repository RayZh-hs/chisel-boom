package components.frontend

import chisel3._
import chisel3.util._
import common._
import utility.CycleAwareModule
import components.structures.InstructionMemory

/** Instruction Fetcher, Annotated by RayZh-hs
  *
  * Stage 1 (S1): PC Request
  *   - Generate and send PC address to instruction memory
  *   - PC update logic (sequential, BTB prediction, or redirect)
  *
  * Stage 2 (S2): Wait for Response
  *   - Wait for instruction memory (instData) and BTB (targetPC) to respond
  *   - Both have 1-cycle latency via SyncReadMem
  *
  * Stage 3 (S3): Merge and Output
  *   - Combine instruction data with PC and prediction info
  *   - Output to external queue (ifOut)
  */
class InstFetcher extends CycleAwareModule {
    val io = IO(new Bundle {
        // Control: PC redirect on mispredict/exception
        val pcOverwrite = Input(Valid(UInt(32.W)))

        // External queue backpressure (from ifQueue.io.count)
        val queueCount = Input(UInt(log2Ceil(4).W))

        // Stage 1 output: PC request to instruction memory
        val instAddr = Output(UInt(32.W))

        // Stage 2 inputs: responses from memory and BTB
        val instData = Input(UInt(32.W))
        val targetPC = Input(Valid(UInt(32.W)))

        // Stage 3 output: merged fetch data to external queue
        val ifOut = Decoupled(new FetchToDecodeBundle())
    })

    // Pipeline Control
    // - Backpressure: can fetch if external queue has space (or flushing)
    val can_fetch = io.queueCount <= 1.U
    val fetch_allowed = can_fetch || io.pcOverwrite.valid

    // Track if a fetch was in progress last cycle (for S2->S3 transition)
    val was_fetching = RegNext(fetch_allowed, false.B)

    // Kill logic: suppress shadow fetch after BTB prediction redirects PC
    val kill_next_fetch = RegInit(false.B)
    val kill_consumed = was_fetching && kill_next_fetch

    // ================================
    // Stage 1 (S1): PC Request
    // ================================
    // - Generate PC and send address request to instruction memory.
    // - PC is updated based on: redirect > BTB prediction > sequential (+4)

    val pc = RegInit(0.U(32.W))

    // PC update logic
    when(io.pcOverwrite.valid) {
        // Redirect: highest priority (mispredict recovery)
        pc := io.pcOverwrite.bits + 4.U
        kill_next_fetch := false.B
    }.elsewhen(io.targetPC.valid && fetch_allowed) {
        // BTB prediction: redirect to predicted target
        pc := io.targetPC.bits
        // Kill the shadow fetch only if there was actually a fetch in progress.
        // When resuming from a stall (was_fetching = false), the branch instruction
        // itself hasn't been enqueued yet, so we should not suppress the next enqueue.
        kill_next_fetch := was_fetching
    }.elsewhen(fetch_allowed) {
        // Sequential: advance to next instruction
        pc := pc + 4.U
        kill_next_fetch := false.B
    }.elsewhen(kill_consumed) {
        // Clear kill flag after it has suppressed the shadow fetch,
        // even if fetch_allowed is false (e.g., queue full during rollback).
        kill_next_fetch := false.B
    }

    // S1 Output: send PC to instruction memory
    io.instAddr := Mux(io.pcOverwrite.valid, io.pcOverwrite.bits, pc)

    // ================================
    // Stage 2 (S2): Wait for Memory and BTB Response
    // ================================
    // - Instruction memory and BTB use SyncReadMem (1-cycle read latency).
    // - This stage captures the PC that was sent in S1 for use in S3.
    // - Note: io.instData and io.targetPC arrive in this cycle (combinational from external SyncReadMem read ports), ready for S3 to consume.

    // Pipeline register: capture PC from S1 for S3 output
    val pc_delayed = RegEnable(io.instAddr, fetch_allowed)

    // ================================
    // Stage 3 (S3): Merge and Output
    // ================================
    // - Combine instruction data with PC and prediction metadata.
    // - Output to external queue for downstream decode stage.

    // Valid if we were fetching last cycle, not redirecting, and not killing
    io.ifOut.valid := was_fetching && !io.pcOverwrite.valid && !kill_next_fetch

    // Merge fetch data
    io.ifOut.bits.pc := pc_delayed
    io.ifOut.bits.inst := io.instData
    io.ifOut.bits.predict := io.targetPC.valid
    io.ifOut.bits.predictedTarget := io.targetPC.bits

    // Debugging prints
    when(io.ifOut.fire) {
        printf(
          p"FETCH: PC=0x${Hexadecimal(io.ifOut.bits.pc)} Inst=0x${Hexadecimal(io.ifOut.bits.inst)}\n"
        )
        when(io.ifOut.bits.predict) {
            printf(
              p"FETCH: BTB Predicted Target=0x${Hexadecimal(io.ifOut.bits.predictedTarget)}\n"
            )
        }
    }
    when(io.pcOverwrite.valid) {
        printf(p"FETCH: Redirect to 0x${Hexadecimal(io.pcOverwrite.bits)}\n")
    }
}
