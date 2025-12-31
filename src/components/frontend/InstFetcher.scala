package components

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

        // TODO branch predictor interface
    })

    val pc = RegInit(0.U(32.W))

    val queue = Module(new Queue(new FetchToDecodeBundle, entries = 3, pipe = true, flow = true))

    val can_fetch = queue.io.count <= 1.U
    
    // We can fetch if the queue has space OR if we are flushing (overwrite)
    val fetch_allowed = can_fetch || io.pcOverwrite.valid

    io.instAddr := Mux(io.pcOverwrite.valid, io.pcOverwrite.bits, pc)
    
    val pc_delayed = RegEnable(io.instAddr, fetch_allowed)
    
    when (io.pcOverwrite.valid) {
        pc := io.pcOverwrite.bits + 4.U
    } .elsewhen (fetch_allowed) {
        pc := pc + 4.U
    }

    val was_fetching = RegNext(fetch_allowed, false.B)
    queue.io.enq.valid := was_fetching && !io.pcOverwrite.valid
    queue.io.enq.bits.inst := io.instData
    queue.io.enq.bits.pc   := pc_delayed

    queue.reset := reset.asBool || io.pcOverwrite.valid

    io.ifOut <> queue.io.deq
}