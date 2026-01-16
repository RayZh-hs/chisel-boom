package components.frontend

import chisel3._
import chisel3.util._
import common._
import utility.CycleAwareModule

class InstFetcher extends CycleAwareModule {
    val io = IO(new Bundle {
        val pcOverwrite =
            Input(Valid(UInt(32.W))) // Overwrite PC when misprediction occurs
        val instAddr =
            Output(UInt(32.W)) // Inst Addr to be routed to BTB and ICache
        val instData = Input(UInt(32.W)) // Inst Data from ICache
        val btbResult = Input(Valid(UInt(32.W))) // Branch Target from BTB
        val ifOut = Decoupled(
          new FetchToDecodeBundle()
        ) // Output to Decode Stage (wire into queue)
    })

    val pc = RegInit(0.U(32.W))

    // Forward declaration: Stage 2 states
    val s2Valid = RegInit(false.B)
    val s2PC = Reg(UInt(32.W))

    // =========================================================
    // Control Signals
    // =========================================================

    // In S2 we can send data to decode when the data is valid and Decode is ready.
    val s2Fire = s2Valid && io.ifOut.ready

    // In S1 we can fetch a new instruction if any of the following is true:
    // 1. S2 is empty (!s2_valid)
    // 2. S2 is moving to Decode (s2_fire)
    // 3. We are flushing (io.pcOverwrite) -> everything is cleared and we restart
    val s1Ready = !s2Valid || s2Fire || io.pcOverwrite.valid

    // =========================================================
    // Stage 1 (S1): PC Generation & Request
    // =========================================================

    // fetchAddr is what is sent to ICache and BTB
    val fetchAddr = Wire(UInt(32.W))
    when(io.pcOverwrite.valid) {
        fetchAddr := io.pcOverwrite.bits
    }.elsewhen(s2Valid && !s2Fire) {
        fetchAddr := s2PC // Hold the target pc if S2 is not moving
    }.elsewhen(io.btbResult.valid) {
        fetchAddr := io.btbResult.bits
    }.otherwise {
        fetchAddr := pc
    }
    io.instAddr := fetchAddr

    // Update PC for next cycle
    val nextPC = Wire(UInt(32.W))
    when(io.pcOverwrite.valid) {
        // Priority 1: External Flush / Exception (Overrides everything)
        nextPC := io.pcOverwrite.bits + 4.U
    }.elsewhen(io.btbResult.valid) {
        // Priority 2: Branch Prediction Redirect
        nextPC := io.btbResult.bits + 4.U
    }.elsewhen(s1Ready) {
        // Priority 3: S1 is working, so push the pc (PC + 4)
        nextPC := pc + 4.U
    }.otherwise {
        nextPC := pc
    }
    pc := nextPC

    // =========================================================
    // Stage 2 (S2): State Update
    // =========================================================

    // Does S1 issue a request that will result in valid data for S2 next cycle?
    // 1. Flush: ALWAYS issues a valid new request.
    // 2. Predict Taken: it was sent, but we later know it is wrong.
    // 3. Normal: If S1 is ready, we issue a valid request.
    val s1Fire = s1Ready

    when(s1Fire) {
        s2Valid := true.B
        s2PC := fetchAddr
    }.elsewhen(s2Fire) {
        s2Valid := false.B
    }

    // Mask valid if flushing current cycle
    io.ifOut.valid := s2Valid && !io.pcOverwrite.valid

    io.ifOut.bits.pc := s2PC
    io.ifOut.bits.inst := io.instData
    io.ifOut.bits.predict := io.btbResult.valid
    io.ifOut.bits.predictedTarget := io.btbResult.bits

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
