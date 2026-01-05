package components.backend

import chisel3._
import chisel3.util._
import utility.CircularQueue
import common.{BroadcastBundle, DispatchToROBBundle}
import common.Configurables._

class ROBEntry extends Bundle {
    val ldst = UInt(5.W)
    val pdst = UInt(PREG_WIDTH.W)
    val stalePdst = UInt(PREG_WIDTH.W)
    val isStore = Bool()
    val ready = Bool()
}

class ReOrderBuffer extends Module {
    // IO definition
    val io = IO(new Bundle {
        val dispatch = Flipped(Decoupled(new DispatchToROBBundle))
        val broadcastInput = Flipped(Decoupled(new BroadcastBundle))
        val commit = Decoupled(new ROBEntry)

        // Output the ROB tag assigned to the dispatched instruction
        val robTag = Output(UInt(ROB_WIDTH.W))
        val flush = Input(Bool())

        // Rollback interface
        val brUpdate = Flipped(Valid(new Bundle {
            val robTag = UInt(ROB_WIDTH.W)
            val mispredict = Bool()
        }))
        val rollback = Output(Valid(new Bundle {
            val ldst = UInt(5.W)
            val pdst = UInt(PREG_WIDTH.W)
            val stalePdst = UInt(PREG_WIDTH.W)
        }))
    })

    // ROB entries
    val robEntries = Module(new CircularQueue(new ROBEntry, Derived.ROB_COUNT))
    robEntries.io.flush := io.flush
    robEntries.io.popTail := false.B // Default

    val isRollingBack = RegInit(false.B)
    val rollbackTag = Reg(UInt(ROB_WIDTH.W))

    when(io.brUpdate.valid && io.brUpdate.bits.mispredict) {
        isRollingBack := true.B
        rollbackTag := io.brUpdate.bits.robTag
    }

    val currentTail = robEntries.io.tailPtr
    val atRollbackPoint = currentTail === (rollbackTag +& 1.U) % Derived.ROB_COUNT.U
    
    // We are done rolling back when we reach the instruction after the branch
    when(isRollingBack && atRollbackPoint) {
        isRollingBack := false.B
    }

    // When rolling back, we read the entry at tail - 1
    val prevTail = Mux(currentTail === 0.U, (Derived.ROB_COUNT - 1).U, currentTail - 1.U)
    
    // Use raccess to read the entry to rollback
    // Note: broadcastInput also uses raccessIdx, so we need to mux it.
    robEntries.io.raccessIdx := Mux(isRollingBack, prevTail, io.broadcastInput.bits.robTag)

    val entryToRollback = robEntries.io.raccessOut
    io.rollback.valid := isRollingBack && !atRollbackPoint
    io.rollback.bits.ldst := entryToRollback.ldst
    io.rollback.bits.pdst := entryToRollback.pdst
    io.rollback.bits.stalePdst := entryToRollback.stalePdst

    when(isRollingBack && !atRollbackPoint) {
        robEntries.io.popTail := true.B
    }

    val newEntry = Wire(new ROBEntry)
    newEntry.ldst := io.dispatch.bits.ldst
    newEntry.pdst := io.dispatch.bits.pdst
    newEntry.stalePdst := io.dispatch.bits.stalePdst
    newEntry.isStore := io.dispatch.bits.isStore
    newEntry.ready := false.B

    robEntries.io.enq.valid := io.dispatch.valid && !isRollingBack
    robEntries.io.enq.bits := newEntry
    io.dispatch.ready := robEntries.io.enq.ready && !isRollingBack

    // Output the tail pointer as the assigned ROB tag
    io.robTag := robEntries.io.tailPtr

    // Broadcast Logic
    // robEntries.io.raccessIdx is already set above for rollback or broadcast

    // Read-Modify-Write
    val entryToUpdate = robEntries.io.raccessOut
    val updatedEntry = Wire(new ROBEntry)
    updatedEntry := entryToUpdate
    updatedEntry.ready := true.B

    robEntries.io.raccessIn := updatedEntry
    robEntries.io.raccessWEn := io.broadcastInput.valid && !isRollingBack

    io.broadcastInput.ready := !isRollingBack

    // Commit Logic
    val headEntry = robEntries.io.deq.bits
    val canCommit = robEntries.io.deq.valid && headEntry.ready
    val isP0 = headEntry.stalePdst === 0.U

    io.commit.valid := canCommit && !isP0
    io.commit.bits := headEntry

    // Only dequeue if the consumer is ready AND the instruction is ready to commit
    robEntries.io.deq.ready := headEntry.ready && (io.commit.ready || isP0)
}
