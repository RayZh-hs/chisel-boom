package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.ReturnAddressStack
import utility.CycleAwareModule

/** RAS Adaptor
  *
  * This module interfaces with the Return Address Stack (RAS) to provide
  * accurate target predictions for CALL and RET instructions.
  */
class RASAdaptor extends CycleAwareModule {
    val io = IO(new Bundle {
        // Inputs from Fetch/Decode pipeline
        val in = Flipped(Decoupled(new FetchToDecodeBundle))

        // Updates/Recovery
        val recover = Input(Bool())
        val recoverSP = Input(UInt(RAS_WIDTH.W))

        // Outputs
        val out = Decoupled(new RASAdaptorBundle)
    })

    val ras = Module(new ReturnAddressStack)

    ras.io.recover := io.recover
    ras.io.recoverSP := io.recoverSP

    // Internal Signals
    val inPacket = io.in.bits
    val instRaw = io.in.bits.inst

    // Decoding
    val opcode = instRaw(6, 0)
    val rd = instRaw(11, 7)
    val rs1 = instRaw(19, 15)

    // JAL: 1101111 (0x6F)
    val isJAL = opcode === "b1101111".U
    // JALR: 1100111 (0x67)
    val isJALR = opcode === "b1100111".U

    // J-Immediate Extraction
    // imm[20|10:1|11|19:12]
    val jImm = Wire(UInt(32.W))
    val jImmRaw =
        instRaw(31) ## instRaw(19, 12) ## instRaw(20) ## instRaw(30, 21) ## 0.U(
          1.W
        )
    jImm := Cat(Fill(32 - 21, instRaw(31)), jImmRaw)

    // Flow Control
    io.in.ready := io.out.ready
    io.out.valid := io.in.valid

    val fire = io.in.fire

    // Instruction Decoding using Pre-decoded info
    val isJ = isJAL || isJALR

    // Spec: CALL if J instruction and rd is x1 or x5
    val isCall = isJ && (rd === 1.U || rd === 5.U)

    // Spec: RET if J instruction (JALR) and rs1 is x1 or x5 and rd is x0
    val isRet = isJALR && (rd === 0.U) && (rs1 === 1.U || rs1 === 5.U)

    // RAS Operations
    val push = fire && isCall
    val pop = fire && isRet

    ras.io.push := push
    ras.io.pop := pop
    ras.io.writeVal := inPacket.pc + 4.U

    // Target Calculation
    val targetJAL = inPacket.pc + jImm
    val targetRET = ras.io.readVal

    // We can only reliably correct PC for JAL (static) and RET (RAS)
    val calculatedTarget = Mux(isRet, targetRET, targetJAL)

    val isPredictionWrong = inPacket.predictedTarget =/= calculatedTarget
    val canCorrect =
        isJAL || isRet // We don't correct JALR calls (register dependent)

    io.out.bits.currentSP := ras.io.currentSP
    io.out.bits.flush := fire && canCorrect && isPredictionWrong
    io.out.bits.flushNextPC := calculatedTarget

    // Debugging info
    when(canCorrect && isPredictionWrong) {
      printf("RAS: Detected ")
      when(isCall) {
        printf("CALL ")
      } .elsewhen(isRet) {
        printf("RET ")
      }
      printf(p"at PC=${Hexadecimal(inPacket.pc)}\n")
      printf(p"    Alternating to target: ${Hexadecimal(calculatedTarget)}\n")
    }
}
