package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._
import common._

class LoadStoreInfo extends Bundle {
    val opWidth = MemOpWidth()
    val isStore = Bool()
    val isUnsigned = Bool()
    val imm = UInt(32.W)
}

class SequentialBufferEntry[T <: Data](gen: T) extends Bundle {
    val robTag = UInt(ROB_WIDTH.W)
    val pdst = UInt(PREG_WIDTH.W)
    val src1Ready = Bool()
    val src2Ready = Bool()
    val src1 = UInt(PREG_WIDTH.W)
    val src2 = UInt(PREG_WIDTH.W)
    val info = gen
}

class SequentialIssueBuffer[T <: Data](gen: T, entries: Int) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new SequentialBufferEntry(gen)))
        val broadcast = Input(Valid(new BroadcastBundle()))
        val out = Decoupled(new SequentialBufferEntry(gen))

        val flush = Input(Bool())
        val flushTag = Input(UInt(ROB_WIDTH.W))
        val robHead = Input(UInt(ROB_WIDTH.W))
    })

    val buffer = Reg(Vec(entries, new SequentialBufferEntry(gen)))
    val head = RegInit(0.U(log2Ceil(entries).W))
    val tail = RegInit(0.U(log2Ceil(entries).W))
    val maybeFull = RegInit(false.B)

    val ptrMatch = head === tail
    val isEmpty = ptrMatch && !maybeFull
    val isFull = ptrMatch && maybeFull

    // --- Enqueue Logic ---
    io.in.ready := !isFull && !io.flush

    when(io.in.fire) {
        buffer(tail) := io.in.bits
        tail := Mux(tail === (entries - 1).U, 0.U, tail + 1.U)
        maybeFull := true.B
    }

    // --- Broadcast Logic ---
    when(io.broadcast.valid) {
        val resPdst = io.broadcast.bits.pdst
        for (i <- 0 until entries) {
            when(buffer(i).src1 === resPdst) {
                buffer(i).src1Ready := true.B
            }
            when(buffer(i).src2 === resPdst) {
                buffer(i).src2Ready := true.B
            }
        }
    }

    // --- Issue Logic (Sequential) ---
    val headEntry = buffer(head)

    // Only the head entry can be issued
    val canIssue = !isEmpty && headEntry.src1Ready && headEntry.src2Ready

    io.out.valid := canIssue && !io.flush
    io.out.bits := headEntry

    when(io.out.fire) {
        head := Mux(head === (entries - 1).U, 0.U, head + 1.U)
        maybeFull := false.B
    }

    // --- Flush Logic ---
    when(io.flush) {
        val validMask = Wire(Vec(entries, Bool()))

        // Construct mask of physically valid entries
        for (i <- 0 until entries) {
            val idx = i.U
            when(isFull) {
                validMask(i) := true.B
            }.elsewhen(isEmpty) {
                validMask(i) := false.B
            }.elsewhen(head < tail) {
                validMask(i) := idx >= head && idx < tail
            }.otherwise {
                validMask(i) := idx >= head || idx < tail
            }
        }

        val keepMask = Wire(Vec(entries, Bool()))
        val head_le_flush = io.robHead <= io.flushTag

        for (i <- 0 until entries) {
            val tag = buffer(i).robTag
            val is_older = Mux(
              head_le_flush,
              (tag >= io.robHead && tag < io.flushTag),
              (tag >= io.robHead || tag < io.flushTag)
            )
            keepMask(i) := validMask(i) && is_older
        }

        // Count how many entries to keep (starting from head)
        val newCount = PopCount(keepMask)

        // Move tail to head + newCount
        val nextTail = head +& newCount
        tail := Mux(nextTail >= entries.U, nextTail - entries.U, nextTail)

        // Update maybeFull state
        // If we keep equal to entries, it must be full
        maybeFull := (newCount === entries.U)
    }
}
