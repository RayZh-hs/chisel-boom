package components.frontend

import chisel3._
import chisel3.util._
import common._
import utility.CycleAwareModule
import components.structures.InstructionMemory

class InstFetcher extends CycleAwareModule {
    val io = IO(new Bundle {
        // PC overwrite logic
        val pcOverwrite = Input(Valid(UInt(32.W)))
        // Inst outputs
        val ifOut = Decoupled(new FetchToDecodeBundle())

        // Memory interface
        val instAddr = Output(UInt(32.W))
        val instData = Input(UInt(32.W))

        // Branch predictor interface
        val targetPC = Input(Valid(UInt(32.W)))
    })

    val pc = RegInit(0.U(32.W))

    val queue = Module(
      new Queue(new FetchToDecodeBundle, entries = 3, pipe = true, flow = true)
    )

    val can_fetch = queue.io.count <= 1.U

    // We can fetch if the queue has space OR if we are flushing (overwrite)
    val fetch_allowed = can_fetch || io.pcOverwrite.valid

    io.instAddr := Mux(io.pcOverwrite.valid, io.pcOverwrite.bits, pc)

    val pc_delayed = RegEnable(io.instAddr, fetch_allowed)

    val kill_next_fetch = RegInit(false.B)

    val was_fetching = RegNext(fetch_allowed, false.B)

    // kill_next_fetch is a one-shot flag that suppresses exactly one enqueue.
    // After the shadow fetch would have been enqueued (was_fetching is true),
    // we must clear the flag so subsequent fetches can proceed.
    // This handles the case where fetch stalls immediately after a BTB prediction.
    val kill_consumed = was_fetching && kill_next_fetch

    when(io.pcOverwrite.valid) {
        pc := io.pcOverwrite.bits + 4.U
        kill_next_fetch := false.B
    }.elsewhen(io.targetPC.valid && fetch_allowed) {
        pc := io.targetPC.bits
        // Only kill the next fetch if there was actually a fetch in progress.
        // When resuming from a stall (was_fetching = false), the branch instruction
        // itself hasn't been enqueued yet, so we should not suppress the next enqueue.
        kill_next_fetch := was_fetching
    }.elsewhen(fetch_allowed) {
        pc := pc + 4.U
        kill_next_fetch := false.B
    }.elsewhen(kill_consumed) {
        // Clear kill_next_fetch after it has suppressed the shadow fetch,
        // even if fetch_allowed is false (e.g., queue full during rollback).
        kill_next_fetch := false.B
    }

    // FIX: Do not enqueue if we are killing this fetch (shadow fetch of a taken branch)
    queue.io.enq.valid := was_fetching && !io.pcOverwrite.valid && !kill_next_fetch
    queue.io.enq.bits.inst := io.instData
    queue.io.enq.bits.pc := pc_delayed
    queue.io.enq.bits.predict := io.targetPC.valid
    queue.io.enq.bits.predictedTarget := io.targetPC.bits

    queue.reset := reset.asBool || io.pcOverwrite.valid

    io.ifOut <> queue.io.deq

    when(queue.io.enq.fire) {
        printf(
          p"FETCH: PC=0x${Hexadecimal(queue.io.enq.bits.pc)} Inst=0x${Hexadecimal(queue.io.enq.bits.inst)}\n"
        )
    }
    when(io.pcOverwrite.valid) {
        printf(p"FETCH: Redirect to 0x${Hexadecimal(io.pcOverwrite.bits)}\n")
    }
    // Debug print for prediction
    when(io.targetPC.valid && fetch_allowed && !io.pcOverwrite.valid) {
        printf(
          p"FETCH: BTB Predicted Target 0x${Hexadecimal(io.targetPC.bits)}\n"
        )
    }
}
