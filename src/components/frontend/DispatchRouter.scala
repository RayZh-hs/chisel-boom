package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures._
import components.structures.SequentialBufferEntry
import components.structures.LoadStoreInfo

class DispatchRouter extends Module {
    val io = IO(new Bundle {
        val instInput = Flipped(Decoupled(new DecodedInstWithRAS))
        val robTagIn = Input(UInt(ROB_WIDTH.W))
        val robDispatchReady = Input(Bool())
        val rollbackValid = Input(Bool())

        val prfReady = Input(Vec(2, Bool()))

        // Buffer Outputs
        val aluIB = Decoupled(new IssueBufferEntry(new ALUInfo))
        val multIB = Decoupled(new IssueBufferEntry(new MultInfo))
        val bruIB = Decoupled(new IssueBufferEntry(new BRUInfo))
        val lsuIB = Decoupled(new SequentialBufferEntry(new LoadStoreInfo))

        // Updates
        val setBusy = Valid(UInt(PREG_WIDTH.W))
    })

    val inst = io.instInput.bits.inst
    val valid = io.instInput.valid
    
    // Decode Unit Types
    val isALU = inst.fUnitType === FunUnitType.ALU
    val isMULT = inst.fUnitType === FunUnitType.MULT
    val isBRU = inst.fUnitType === FunUnitType.BRU
    val isLSU = inst.fUnitType === FunUnitType.MEM

    // Common signals
    val src1Ready = io.prfReady(0)
    val src2Ready = io.prfReady(1)
    val robTag = io.robTagIn

    val readyForDispatch = io.robDispatchReady && !io.rollbackValid

    // ALU IB Enqueue
    io.aluIB.valid := valid && isALU && readyForDispatch
    io.aluIB.bits.robTag := robTag
    io.aluIB.bits.pdst := inst.pdst
    io.aluIB.bits.src1 := inst.prs1
    io.aluIB.bits.src2 := inst.prs2
    io.aluIB.bits.src1Ready := src1Ready
    io.aluIB.bits.src2Ready := Mux(inst.useImm, true.B, src2Ready)
    io.aluIB.bits.imm := inst.imm
    io.aluIB.bits.useImm := inst.useImm
    io.aluIB.bits.info.aluOp := inst.aluOpType
    if (Configurables.Elaboration.pcInIssueBuffer) {
        io.aluIB.bits.pc.get := inst.pc
    }

    // MULT IB Enqueue
    io.multIB.valid := valid && isMULT && readyForDispatch
    io.multIB.bits.robTag := robTag
    io.multIB.bits.pdst := inst.pdst
    io.multIB.bits.src1 := inst.prs1
    io.multIB.bits.src2 := inst.prs2
    io.multIB.bits.src1Ready := src1Ready
    io.multIB.bits.src2Ready := src2Ready
    io.multIB.bits.imm := inst.imm
    io.multIB.bits.useImm := false.B
    io.multIB.bits.info.multOp := inst.multOpType
    if (Configurables.Elaboration.pcInIssueBuffer) {
        io.multIB.bits.pc.get := inst.pc
    }

    // BRU IB Enqueue
    io.bruIB.valid := valid && isBRU && readyForDispatch
    io.bruIB.bits.robTag := robTag
    io.bruIB.bits.pdst := inst.pdst
    io.bruIB.bits.src1 := inst.prs1
    io.bruIB.bits.src2 := inst.prs2
    io.bruIB.bits.src1Ready := src1Ready
    io.bruIB.bits.src2Ready := src2Ready
    io.bruIB.bits.imm := inst.imm
    io.bruIB.bits.useImm := inst.useImm
    io.bruIB.bits.info.bruOp := inst.bruOpType
    io.bruIB.bits.info.cmpOp := inst.cmpOpType
    io.bruIB.bits.info.pc := inst.pc
    io.bruIB.bits.info.predict := inst.predict
    io.bruIB.bits.info.predictedTarget := inst.predictedTarget
    io.bruIB.bits.info.rasSP := io.instInput.bits.rasSP // Use RAS from Bundle
    if (Configurables.Elaboration.pcInIssueBuffer) {
        io.bruIB.bits.pc.get := inst.pc
    }

    // LSU IB Enqueue (Sequential)
    io.lsuIB.valid := valid && isLSU && readyForDispatch
    io.lsuIB.bits.robTag := robTag
    io.lsuIB.bits.pdst := inst.pdst
    io.lsuIB.bits.src1 := inst.prs1
    io.lsuIB.bits.src2 := inst.prs2
    // For stores, src2 is data to store. For loads, src2 is unused (imm offset).
    // Store: src1=base, src2=data. Both needed.
    // Load: src1=base. src2 unused.
    io.lsuIB.bits.src1Ready := src1Ready
    io.lsuIB.bits.src2Ready := Mux(
      inst.isStore,
      src2Ready,
      true.B
    ) 
    io.lsuIB.bits.info.opWidth := inst.opWidth
    io.lsuIB.bits.info.isStore := inst.isStore
    io.lsuIB.bits.info.isUnsigned := inst.isUnsigned
    io.lsuIB.bits.info.imm := inst.imm
    if (Configurables.Elaboration.pcInIssueBuffer) {
        io.lsuIB.bits.pc.get := inst.pc
    }

    // Determine readiness
    // Valid if target buffer is ready && rob dispatch ready
    val targetReady = Mux(isALU, io.aluIB.ready,
        Mux(isMULT, io.multIB.ready,
        Mux(isBRU, io.bruIB.ready,
        Mux(isLSU, io.lsuIB.ready, false.B))))
    
    io.instInput.ready := targetReady && readyForDispatch

    // Set Busy
    io.setBusy.valid := io.instInput.valid && io.instInput.ready && inst.pdst =/= 0.U
    io.setBusy.bits := inst.pdst

}
