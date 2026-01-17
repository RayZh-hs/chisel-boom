package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility.CycleAwareModule

class ROBEntry extends Bundle {
    val ldst = UInt(5.W)
    val pdst = UInt(PREG_WIDTH.W)
    val stalePdst = UInt(PREG_WIDTH.W)
    val isStore = Bool()
    val ready = Bool()

    // pc field for easier debugging, requires elaboration option
    val pc = if (Configurables.Elaboration.pcInROB) Some(UInt(32.W)) else None
}

class ReOrderBuffer extends CycleAwareModule {
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
        val isRollingBack = if (Configurables.Profiling.RollbackTime) Some(Output(Bool())) else None
        val head = Output(UInt(ROB_WIDTH.W))
        val count = if (common.Configurables.Profiling.Utilization) Some(Output(UInt((ROB_WIDTH + 1).W))) else None
    })

    private val entries = Derived.ROB_COUNT
    private val robRam = Reg(Vec(entries, new ROBEntry))
    private val head = RegInit(0.U(ROB_WIDTH.W))
    private val tail = RegInit(0.U(ROB_WIDTH.W))
    private val maybeFull = RegInit(false.B)

    // entries is a power of 2, so we can use bitmask for efficient wrapping
    private def nextPtr(p: UInt): UInt = (p + 1.U)(ROB_WIDTH - 1, 0)
    private def prevPtr(p: UInt): UInt = (p - 1.U)(ROB_WIDTH - 1, 0)

    val tailPrev = prevPtr(tail)
    val tailNext = nextPtr(tail)

    val isRollingBack = RegInit(false.B)
    val targetTail = Reg(UInt(ROB_WIDTH.W))

    val rollbackDone = tail === targetTail
    val doPopTail = isRollingBack && !rollbackDone

    when(isRollingBack && rollbackDone) {
        isRollingBack := false.B
    }
    when(io.brUpdate.valid && io.brUpdate.bits.mispredict) {
        isRollingBack := true.B
        targetTail := nextPtr(io.brUpdate.bits.robTag)
    }

    val ptrMatch = head === tail
    val isFull = ptrMatch && maybeFull
    val isEmpty = ptrMatch && !maybeFull

    io.count.foreach { c =>
        c := Mux(isFull, entries.U, Mux(tail >= head, tail - head, entries.U + tail - head))
    }

    val doEnq = io.dispatch.fire && !isRollingBack
    val doDeq = io.commit.fire

    when(doEnq) { tail := tailNext }
    when(doDeq) { head := nextPtr(head) }
    when(doPopTail) { tail := tailPrev }

    when(doEnq && !doDeq) {
        maybeFull := tailNext === head
    }.elsewhen((doDeq || doPopTail) && !doEnq) {
        maybeFull := false.B
    }

    // Dispatch
    io.dispatch.ready := !isFull && !isRollingBack
    when(doEnq) {
        val entry = Wire(new ROBEntry)
        entry.ldst := io.dispatch.bits.ldst
        entry.pdst := io.dispatch.bits.pdst
        entry.stalePdst := io.dispatch.bits.stalePdst
        entry.isStore := io.dispatch.bits.isStore
        entry.ready := false.B
        if (Configurables.Elaboration.pcInROB) {
            entry.pc.get := io.dispatch.bits.pc.get
        }
        robRam(tail) := entry
    }
    io.robTag := tail

    // Broadcast
    io.broadcastInput.ready := true.B // Always ready to accept broadcasts
    when(io.broadcastInput.valid) {
        robRam(io.broadcastInput.bits.robTag).ready := true.B
    }
    // Rollback Output
    val entryToRollback = robRam(tailPrev)
    io.rollback.valid := doPopTail
    io.rollback.bits.ldst := entryToRollback.ldst
    io.rollback.bits.pdst := entryToRollback.pdst
    io.rollback.bits.stalePdst := entryToRollback.stalePdst
    io.isRollingBack.foreach(_ := isRollingBack)

    // Commit
    val headEntry = robRam(head)
    // We can commit if: Not empty AND ready AND (not rolling back OR hasn't reached the bad instructions)
    val headReachedTarget = isRollingBack && (head === targetTail)
    val canCommit = !isEmpty && headEntry.ready && !headReachedTarget

    io.commit.valid := canCommit
    io.commit.bits := headEntry

    io.head := head

    when(doEnq) {
        if (Configurables.Elaboration.pcInROB) {
            printf(
              p"ROB: Alloc Idx=$tail ldst=${io.dispatch.bits.ldst} pdst=${io.dispatch.bits.pdst} pc=0x${Hexadecimal(io.dispatch.bits.pc.get)}\n"
            )
        } else {
            printf(
              p"ROB: Alloc Idx=$tail ldst=${io.dispatch.bits.ldst} pdst=${io.dispatch.bits.pdst}\n"
            )
        }
    }
    when(doDeq) {
        if (Configurables.Elaboration.pcInROB) {
            printf(
              p"ROB: Commit Idx=$head ldst=${headEntry.ldst} pdst=${headEntry.pdst} pc=0x${Hexadecimal(headEntry.pc.get)}\n"
            )
        } else {
            printf(
              p"ROB: Commit Idx=$head ldst=${headEntry.ldst} pdst=${headEntry.pdst}\n"
            )
        }
    }
    when(io.brUpdate.valid && io.brUpdate.bits.mispredict) {
        if (Configurables.Elaboration.pcInROB) {
            printf(
              p"ROB: Mispredict detected at tag=${io.brUpdate.bits.robTag} pc=0x${Hexadecimal(robRam(io.brUpdate.bits.robTag).pc.get)}\n"
            )
        } else {
            printf(
              p"ROB: Mispredict detected at tag=${io.brUpdate.bits.robTag}\n"
            )
        }
    }
}
