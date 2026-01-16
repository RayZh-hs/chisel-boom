package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.ReturnAddressStack

/**
  * RAS Adaptor
  *
  * This module interfaces with the Return Address Stack (RAS) to provide
  * accurate target predictions for CALL and RET instructions.
  * 
  */
class RASAdaptor extends Module {
    val io = IO(new Bundle {
        // Inputs from Fetch/Decode pipeline
        val in = Flipped(Decoupled(new DecodedInstBundle))

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
    val inst = io.in.bits

    // Flow Control
    io.in.ready := io.out.ready
    io.out.valid := io.in.valid
    
    val fire = io.in.fire

    // Instruction Decoding using Pre-decoded info
    val isJAL = inst.bruOpType === BRUOpType.JAL
    val isJALR = inst.bruOpType === BRUOpType.JALR
    val isJ = isJAL || isJALR

    // Spec: CALL if J instruction and rd is x1 or x5
    val isCall = isJ && (inst.ldst === 1.U || inst.ldst === 5.U)
    
    // Spec: RET if J instruction (JALR) and rs1 is x1 or x5 and rd is x0
    val isRet = isJALR && (inst.ldst === 0.U) && (inst.lrs1 === 1.U || inst.lrs1 === 5.U)

    // RAS Operations
    val push = fire && isCall
    val pop = fire && isRet

    ras.io.push := push
    ras.io.pop := pop
    ras.io.writeVal := inst.pc + 4.U

    // Target Calculation
    val targetJAL = inst.pc + inst.imm
    val targetRET = ras.io.readVal
    
    // We can only reliably correct PC for JAL (static) and RET (RAS)
    val calculatedTarget = Mux(isRet, targetRET, targetJAL)
    
    val isPredictionWrong = inst.predictedTarget =/= calculatedTarget
    val canCorrect = isJAL || isRet // We don't correct JALR calls (register dependent)

    io.out.bits.currentSP := ras.io.currentSP
    io.out.bits.flush := io.in.valid && canCorrect && isPredictionWrong
    io.out.bits.flushNextPC := calculatedTarget
}