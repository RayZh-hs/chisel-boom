package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

class ROBEntry extends Bundle {
    val ldst = UInt(5.W)
    val pdst = UInt(PREG_WIDTH.W)
    val stalePdst = UInt(PREG_WIDTH.W)
    val isStore = Bool()
    val ready = Bool()
}

class ReOrderBuffer extends Module {
    val io = IO(new Bundle {
        val dispatch = Flipped(Decoupled(new DispatchToROBBundle))
        val broadcastInput = Flipped(Decoupled(new BroadcastBundle))
        val commit = Decoupled(new ROBEntry)

        val robTag = Output(UInt(ROB_WIDTH.W))
        val flush = Input(Bool())

        val brUpdate = Flipped(Valid(new Bundle {
            val robTag = UInt(ROB_WIDTH.W)
            val mispredict = Bool()
        }))
        val rollback = Output(Valid(new RollbackBundle))

        val head = Output(
          UInt(ROB_WIDTH.W)
        ) // used to determine whether a robTag is older
    })

    // --- Internal Storage ---
    private val entries = Derived.ROB_COUNT
    private val robRam = Reg(Vec(entries, new ROBEntry))
    private val head = RegInit(0.U(ROB_WIDTH.W))
    private val tail = RegInit(0.U(ROB_WIDTH.W))
    private val maybeFull = RegInit(false.B)

    // --- Private Helper Functions ---
    private def nextPtr(p: UInt): UInt =
        Mux(p === (entries - 1).U, 0.U, p + 1.U)
    private def prevPtr(p: UInt): UInt =
        Mux(p === 0.U, (entries - 1).U, p - 1.U)
    private def isFull: Bool = (head === tail) && maybeFull
    private def isEmpty: Bool = (head === tail) && !maybeFull

    // --- Rollback Control ---
    val isRollingBack = RegInit(false.B)
    val rollbackTag = Reg(UInt(ROB_WIDTH.W))

    // Logic: stop when tail points to the instruction AFTER the branch
    val targetTail = nextPtr(rollbackTag)
    val rollbackDone = tail === targetTail
    val doPopTail = isRollingBack && !rollbackDone

    when(io.brUpdate.valid && io.brUpdate.bits.mispredict) {
        isRollingBack := true.B
        rollbackTag := io.brUpdate.bits.robTag
    }
    when(isRollingBack && rollbackDone) {
        isRollingBack := false.B
    }

    // --- Queue Management Logic ---
    val doEnq = io.dispatch.fire && !isRollingBack
    val doDeq = io.commit.fire

    when(io.flush) {
        head := 0.U
        tail := 0.U
        maybeFull := false.B
    }.otherwise {
        // Pointer updates
        when(doEnq) { tail := nextPtr(tail) }
        when(doDeq) { head := nextPtr(head) }
        when(doPopTail) { tail := prevPtr(tail) }

        // Full/Empty state update
        when(doPopTail) {
            maybeFull := false.B
        }.elsewhen(doEnq =/= doDeq) {
            maybeFull := doEnq
        }
    }

    // --- ROB Business Logic ---

    // 1. Dispatch (Enqueue)
    when(doEnq) {
        val entry = Wire(new ROBEntry)
        entry.ldst := io.dispatch.bits.ldst
        entry.pdst := io.dispatch.bits.pdst
        entry.stalePdst := io.dispatch.bits.stalePdst
        entry.isStore := io.dispatch.bits.isStore
        entry.ready := false.B
        robRam(tail) := entry
    }
    io.dispatch.ready := !isFull && !isRollingBack
    io.robTag := tail // The current tail is the tag for the incoming instr

    // 2. Broadcast (Writeback - Random Access)
    when(io.broadcastInput.valid) {
        robRam(io.broadcastInput.bits.robTag).ready := true.B
    }
    io.broadcastInput.ready := !isRollingBack

    // 3. Rollback (Read-while-popping)
    // We read the entry that is currently at the "end" before decrementing tail
    val entryToRollback = robRam(prevPtr(tail))
    io.rollback.valid := doPopTail
    io.rollback.bits.ldst := entryToRollback.ldst
    io.rollback.bits.pdst := entryToRollback.pdst
    io.rollback.bits.stalePdst := entryToRollback.stalePdst

    io.head := head

    // 4. Commit (Dequeue)
    val headEntry = robRam(head)
    val isP0 = headEntry.stalePdst === 0.U
    val canCommit = !isEmpty && headEntry.ready && !isRollingBack

    io.commit.valid := canCommit && !isP0
    io.commit.bits := headEntry

    // Dequeue logic: logic is ready AND (consumer is ready OR it's a register-zero bypass)
    io.commit.ready := true.B // Placeholder: Connect to MapTable/FreeList ready
}
