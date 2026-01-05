package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.FreeList
import components.backend.ROBEntry

/** Instruction Dispatcher
  *
  * Responsible for filling in register renaming data and
  */
class InstDispatcher extends Module {
    val io = IO(new Bundle {
        val instInput = Flipped(Decoupled(new DecodeToDispatchBundle))
        val instOutput = Decoupled(new DecodeToDispatchBundle)

        val robOutput = Decoupled(new DispatchToROBBundle)
        val commit = Flipped(Valid(new ROBEntry))
        val rollback = Flipped(Valid(new Bundle {
            val ldst = UInt(5.W)
            val pdst = UInt(PREG_WIDTH.W)
            val stalePdst = UInt(PREG_WIDTH.W)
        }))
    })

    // Map Table: Logical to Physical Register Mapping
    val mapTable = RegInit(VecInit(Seq.tabulate(32)(i => i.U(PREG_WIDTH.W))))

    // Free List: Manages free physical registers
    val freeList = Module(new FreeList(Derived.PREG_COUNT, 32))

    // When ROB commits an instruction, the stale physical register is freed.
    freeList.io.free.valid := io.commit.valid
    freeList.io.free.bits := io.commit.bits.stalePdst

    // When ROB rolls back, the pdst that was allocated is freed.
    freeList.io.rollbackFree.valid := io.rollback.valid && io.rollback.bits.pdst =/= 0.U
    freeList.io.rollbackFree.bits := io.rollback.bits.pdst

    val inst = io.instInput.bits
    val needAlloc = inst.ldst =/= 0.U
    val canDispatch = io.instInput.valid &&
        io.instOutput.ready &&
        io.robOutput.ready &&
        (!needAlloc || freeList.io.allocate.valid)

    // Consume input and allocate from free list
    io.instInput.ready := canDispatch
    freeList.io.allocate.ready := canDispatch && needAlloc

    // Outputs
    io.instOutput.valid := canDispatch
    io.robOutput.valid := canDispatch

    // Renaming Logic
    val allocPdst = freeList.io.allocate.bits
    val currentPdst = Mux(needAlloc, allocPdst, 0.U) // If x0, pdst is 0

    // Read source operands from Map Table
    val prs1 = mapTable(inst.lrs1)
    val prs2 = mapTable(inst.lrs2)
    val stalePdst = mapTable(inst.ldst)

    // Update Map Table
    when(io.rollback.valid) {
        mapTable(io.rollback.bits.ldst) := io.rollback.bits.stalePdst
    }.elsewhen(canDispatch && needAlloc) {
        mapTable(inst.ldst) := allocPdst
    }

    // Fill Output Bundles
    io.instOutput.bits := inst
    io.instOutput.bits.prs1 := prs1
    io.instOutput.bits.prs2 := prs2
    io.instOutput.bits.pdst := currentPdst
    io.instOutput.bits.stalePdst := stalePdst
    io.instOutput.bits.predict := inst.predict
    io.instOutput.bits.predictedTarget := inst.predictedTarget

    io.robOutput.bits.ldst := inst.ldst
    io.robOutput.bits.pdst := currentPdst
    io.robOutput.bits.stalePdst := stalePdst
    io.robOutput.bits.isStore := inst.isStore
    io.robOutput.bits.brFlag := inst.brFlag
}
