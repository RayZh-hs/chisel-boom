package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._
import common._
import utility.CycleAwareModule

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

    val pc =
        if (Configurables.Elaboration.pcInIssueBuffer) Some(UInt(32.W))
        else None
}

class SequentialIssueBuffer[T <: Data](gen: T, entries: Int, name: String)
    extends CycleAwareModule {
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

    // --- Enqueue Logic ---
    io.in.ready := !isFull && !io.flush.valid

    when(io.in.fire) {
        val entry = io.in.bits
        val broadcastMatch1 =
            io.broadcast.valid && (entry.src1 === io.broadcast.bits.pdst)
        val broadcastMatch2 =
            io.broadcast.valid && (entry.src2 === io.broadcast.bits.pdst)

        val updatedEntry = Wire(new SequentialBufferEntry(gen))
        updatedEntry := entry
        when(broadcastMatch1) { updatedEntry.src1Ready := true.B }
        when(broadcastMatch2) { updatedEntry.src2Ready := true.B }

        if (Configurables.Elaboration.pcInIssueBuffer) {
            printf(
              p"${name}: Enq robTag=${entry.robTag} pdst=${entry.pdst} pc=0x${Hexadecimal(entry.pc.get)} tail=${tail}\n"
            )
        } else {
            printf(
              p"${name}: Enq robTag=${entry.robTag} pdst=${entry.pdst} tail=${tail}\n"
            )
        }

        buffer(tail) := updatedEntry
        tail := Mux(tail === (entries - 1).U, 0.U, tail + 1.U)
        maybeFull := true.B
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
        val killMaskVec = Wire(Vec(entries, Bool()))
        val validMaskVec = Wire(Vec(entries, Bool()))
        val isWrapped = head > tail

        for (i <- 0 until entries) {
            killMaskVec(i) := io.flush.checkKilled(buffer(i).robTag)

            val idx = i.U
            // When full (head == tail && maybeFull), all entries are valid.
            // When empty (head == tail && !maybeFull), no entries are valid.
            // Otherwise, compute based on head/tail positions.
            validMaskVec(i) := Mux(
              isFull,
              true.B,
              Mux(
                isEmpty,
                false.B,
                Mux(
                  isWrapped,
                  idx >= head || idx < tail,
                  idx >= head && idx < tail
                )
              )
            )
        }
        val killMask = killMaskVec.asUInt & validMaskVec.asUInt

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
