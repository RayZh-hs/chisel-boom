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

        val flush = Input(new FlushBundle)
    })

    val buffer = Reg(Vec(entries, new SequentialBufferEntry(gen)))
    val head = RegInit(0.U(log2Ceil(entries).W))
    val tail = RegInit(0.U(log2Ceil(entries).W))
    val maybeFull = RegInit(false.B)

    val ptrMatch = head === tail
    val isEmpty = ptrMatch && !maybeFull
    val isFull = ptrMatch && maybeFull

    // --- Enqueue Logic ---
    io.in.ready := !isFull && !io.flush.valid

    when(io.in.fire) {
        buffer(tail) := io.in.bits
        tail := Mux(tail === (entries - 1).U, 0.U, tail + 1.U)
        maybeFull := true.B
    }

    // --- Broadcast Logic ---
    // Note: Only need to update entries between head and tail
    // For simplicity, we update all entries but the buffer semantics ensure only valid entries matter
    when(io.broadcast.valid) {
        val resPdst = io.broadcast.bits.pdst
        for (i <- 0 until entries) {
            // Only update src readiness, actual validity is tracked by head/tail pointers
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

    io.out.valid := canIssue && !io.flush.valid
    io.out.bits := headEntry

    when(io.out.fire) {
        head := Mux(head === (entries - 1).U, 0.U, head + 1.U)
        maybeFull := false.B
    }

    // --- Flush Logic (Optimized) ---
    when(io.flush.valid) {
        val killMask = VecInit((0 until entries).map(i => io.flush.checkKilled(buffer(i).robTag))).asUInt

        val doubledKillMask = Cat(killMask, killMask)
        val shiftedKillMask = doubledKillMask >> head

        val anyKilled = killMask.orR
        val distToFirstKill = PriorityEncoder(shiftedKillMask(entries - 1, 0))
        
        when(anyKilled) {
            val nextTail = head +& distToFirstKill
            tail := Mux(nextTail >= entries.U, nextTail - entries.U, nextTail)
            maybeFull := false.B
        }
    }
}
