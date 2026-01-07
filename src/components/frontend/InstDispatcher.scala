package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.{FreeList, RegisterAliasTable}
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
        
        // New interfaces for external RAT and FreeList
        val rat = new Bundle {
            val lrs1 = Output(UInt(5.W))
            val lrs2 = Output(UInt(5.W))
            val ldst = Output(UInt(5.W))
            val prs1 = Input(UInt(PREG_WIDTH.W))
            val prs2 = Input(UInt(PREG_WIDTH.W))
            val stalePdst = Input(UInt(PREG_WIDTH.W))
            val update = Valid(new Bundle {
                val ldst = UInt(5.W)
                val pdst = UInt(PREG_WIDTH.W)
            })
        }

        val freeList = new Bundle {
            val allocate = Flipped(Decoupled(UInt(PREG_WIDTH.W)))
        }
    })

    val inst = io.instInput.bits
    val needAlloc = inst.ldst =/= 0.U
    val canDispatch = io.instInput.valid &&
        io.instOutput.ready &&
        io.robOutput.ready &&
        (!needAlloc || io.freeList.allocate.valid)

    // Consume input and allocate from free list
    io.instInput.ready := canDispatch
    io.freeList.allocate.ready := canDispatch && needAlloc

    // Outputs
    io.instOutput.valid := canDispatch
    io.robOutput.valid := canDispatch

    // Renaming Logic
    val allocPdst = io.freeList.allocate.bits
    val currentPdst = Mux(needAlloc, allocPdst, 0.U) // If x0, pdst is 0

    // Connect RAT
    io.rat.lrs1 := inst.lrs1
    io.rat.lrs2 := inst.lrs2
    io.rat.ldst := inst.ldst

    io.rat.update.valid := canDispatch && needAlloc
    io.rat.update.bits.ldst := inst.ldst
    io.rat.update.bits.pdst := allocPdst

    // Read source operands from RAT
    val prs1 = io.rat.prs1
    val prs2 = io.rat.prs2
    val stalePdst = io.rat.stalePdst

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
