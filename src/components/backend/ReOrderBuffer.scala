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
    })

    // ROB entries
    val robEntries = Module(new CircularQueue(new ROBEntry, Derived.ROB_COUNT))
    robEntries.io.flush := io.flush

    val newEntry = Wire(new ROBEntry)
    newEntry.ldst := io.dispatch.bits.ldst
    newEntry.pdst := io.dispatch.bits.pdst
    newEntry.stalePdst := io.dispatch.bits.stalePdst
    newEntry.isStore := io.dispatch.bits.isStore
    newEntry.ready := false.B

    robEntries.io.enq.valid := io.dispatch.valid
    robEntries.io.enq.bits := newEntry
    io.dispatch.ready := robEntries.io.enq.ready

    // Output the tail pointer as the assigned ROB tag
    io.robTag := robEntries.io.tailPtr

    // Broadcast Logic
    robEntries.io.raccessIdx := io.broadcastInput.bits.robTag

    // Read-Modify-Write
    val entryToUpdate = robEntries.io.raccessOut
    val updatedEntry = Wire(new ROBEntry)
    updatedEntry := entryToUpdate
    updatedEntry.ready := true.B

    robEntries.io.raccessIn := updatedEntry
    robEntries.io.raccessWEn := io.broadcastInput.valid

    io.broadcastInput.ready := true.B

    // Commit Logic
    val headEntry = robEntries.io.deq.bits
    val canCommit = robEntries.io.deq.valid && headEntry.ready

    io.commit.valid := canCommit
    io.commit.bits := headEntry

    // Only dequeue if the consumer is ready AND the instruction is ready to commit
    robEntries.io.deq.ready := io.commit.ready && headEntry.ready
}
