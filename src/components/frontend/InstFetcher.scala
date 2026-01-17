package components.frontend

import chisel3._
import chisel3.util._
import common._
import utility.CycleAwareModule

/** Instruction Fetcher
  *
  * Fetches instructions from the instruction cache, handles PC updates, and
  * interfaces with the Branch Target Buffer (BTB) for branch prediction.
  */
class InstFetcher extends CycleAwareModule {
    // IO Definition
    val io = IO(new Bundle {
        val pcOverwrite =
            Input(Valid(UInt(32.W))) // Overwrite PC when misprediction occurs
        val instAddr = Output(UInt(32.W)) // Debug/Trace output
        val btbResult = Input(Valid(UInt(32.W))) // Branch Target from BTB

        val icache = new Bundle {
            val req = Decoupled(UInt(32.W)) // We send Address
            val resp =
                Flipped(Decoupled(UInt(32.W))) // We receive Data + Valid (Hit)
        }

        val ifOut = Decoupled(new FetchToDecodeBundle())

        // Used for profiling
        val busy =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
        val stallBuffer =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
    })

    val pc = RegInit(0.U(32.W))

    // Forward declaration: Stage 2 states
    val s2Valid = RegInit(false.B)
    val s2PC = Reg(UInt(32.W))
    val s2Predict = Reg(Bool())
    val s2Target = Reg(UInt(32.W))

    io.busy.foreach(_ := s2Valid)
    io.stallBuffer.foreach(_ := s2Valid && !io.ifOut.ready)

    val s2Fire = s2Valid && io.ifOut.ready && io.icache.resp.valid
    val s1Ready = !s2Valid || s2Fire || io.pcOverwrite.valid

    // Stage 1: PC Generation & Request
    // fetchAddr is what is sent to ICache and BTB
    val fetchAddr = Wire(UInt(32.W))
    when(io.pcOverwrite.valid) {
        fetchAddr := io.pcOverwrite.bits
    }.elsewhen(s2Valid && !s2Fire) {
        fetchAddr := s2PC // Hold target pc if ifOut stall or cache miss
    }.elsewhen(io.btbResult.valid) {
        fetchAddr := io.btbResult.bits
    }.otherwise {
        fetchAddr := pc
    }
    io.instAddr := fetchAddr

    io.icache.req.valid := !reset.asBool
    io.icache.req.bits := fetchAddr

    /*
     * @note
     *   Ignore icache.req.ready here. If cache is not ready (refilling),
     *   it won't return valid data, s2Fire will be false, and we naturally retry this fetchAddr next cycle).
     */

    // Update PC for next cycle
    val nextPC = Wire(UInt(32.W))
    when(io.pcOverwrite.valid) {
        nextPC := io.pcOverwrite.bits + 4.U
    }.elsewhen(io.btbResult.valid) {
        nextPC := io.btbResult.bits + 4.U
    }.elsewhen(s1Ready) {
        nextPC := pc + 4.U
    }.otherwise {
        nextPC := pc
    }
    pc := nextPC

    // Stage 2: Output Logic
    val s1Fire = s1Ready

    when(s1Fire) {
        s2Valid := true.B
        s2PC := fetchAddr
    }.elsewhen(s2Fire) {
        s2Valid := false.B
    }

    // Output valid only on Cache hit
    io.ifOut.valid := s2Valid && !io.pcOverwrite.valid && io.icache.resp.valid

    io.ifOut.bits.pc := s2PC
    io.ifOut.bits.inst := io.icache.resp.bits
    io.ifOut.bits.predict := io.btbResult.valid && !io.pcOverwrite.valid
    io.ifOut.bits.predictedTarget := io.btbResult.bits

    io.icache.resp.ready := s2Fire

    // Debugging Data
    when(io.ifOut.fire) {
        printf(
          p"FETCH: PC=0x${Hexadecimal(io.ifOut.bits.pc)} Inst=0x${Hexadecimal(io.ifOut.bits.inst)} Predict=${io.ifOut.bits.predict}\n"
        )
    }
    when(io.pcOverwrite.valid) {
        printf(p"FETCH: Redirect to 0x${Hexadecimal(io.pcOverwrite.bits)}\n")
    }
}
