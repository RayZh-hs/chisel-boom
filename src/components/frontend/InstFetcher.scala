package components.frontend

import chisel3._
import chisel3.util._
import common._
import utility.CycleAwareModule

class InstFetcher extends CycleAwareModule {
    val io = IO(new Bundle {
        val pcOverwrite = Input(Valid(UInt(32.W)))
        val instAddr = Output(UInt(32.W))
        val instData = Input(UInt(32.W))
        val targetPC = Input(Valid(UInt(32.W)))
        val ifOut = Decoupled(new FetchToDecodeBundle())
    })

    val pc = RegInit(0.U(32.W))

    // Stage 2 State
    val s2_valid = RegInit(false.B)
    val s2_pc = Reg(UInt(32.W))

    // =========================================================
    // Control Signals
    // =========================================================

    val s2_fire = s2_valid && io.ifOut.ready

    // We can fetch a NEW instruction (S1) if:
    // 1. S2 is empty (!s2_valid)
    // 2. S2 is moving to Decode (s2_fire)
    // 3. We are flushing (io.pcOverwrite) -> everything is cleared and we restart
    val s1_ready = !s2_valid || s2_fire || io.pcOverwrite.valid

    // =========================================================
    // Stage 1 (S1): PC Generation & Request
    // =========================================================
    val fetch_addr = Wire(UInt(32.W))
    
    when(io.pcOverwrite.valid) {
        fetch_addr := io.pcOverwrite.bits
    }.elsewhen(io.targetPC.valid){ 
        fetch_addr := io.targetPC.bits
    }.elsewhen(s2_valid && !s2_fire){
        fetch_addr := s2_pc // Hold the PC if S2 is not moving
    }.otherwise{
        fetch_addr := pc
    }
    io.instAddr := fetch_addr

    val next_pc = Wire(UInt(32.W))

    when(io.pcOverwrite.valid) {
        // Priority 1: External Flush / Exception (Overrides everything)
        next_pc := io.pcOverwrite.bits + 4.U
    }.elsewhen(io.targetPC.valid) {
        // Priority 2: Branch Prediction Redirect
        next_pc := io.targetPC.bits + 4.U
    }.elsewhen(s1_ready) {
        // Priority 3: S1 is working, so push the pc (PC + 4)
        next_pc := pc + 4.U
    }.otherwise {
        next_pc := pc
    }
    pc := next_pc

    // =========================================================
    // Stage 2 (S2): State Update
    // =========================================================

    // Does S1 issue a request that will result in valid data for S2 next cycle?
    // 1. Flush: ALWAYS issues a valid new request.
    // 2. Predict Taken: it was sent, but we later know it is wrong.
    // 3. Normal: If S1 is ready, we issue a valid request.
    val s1_fire = s1_ready

    when(s1_fire) {
        s2_valid := true.B
        s2_pc := fetch_addr 
    }.elsewhen(s2_fire) {
        s2_valid := false.B
    }

    // Mask valid if flushing *this* cycle
    io.ifOut.valid := s2_valid && !io.pcOverwrite.valid

    io.ifOut.bits.pc := s2_pc
    io.ifOut.bits.inst := io.instData
    io.ifOut.bits.predict := io.targetPC.valid
    io.ifOut.bits.predictedTarget := io.targetPC.bits

    // Debugging
    when(io.ifOut.fire) {
        printf(
          p"FETCH: PC=0x${Hexadecimal(io.ifOut.bits.pc)} Inst=0x${Hexadecimal(io.ifOut.bits.inst)} Predict=${io.ifOut.bits.predict}\n"
        )
    }
    when(io.pcOverwrite.valid) {
        printf(p"FETCH: Redirect to 0x${Hexadecimal(io.pcOverwrite.bits)}\n")
    }
}
