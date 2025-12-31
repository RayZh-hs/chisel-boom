package utility

import chisel3._
import chisel3.util._

class CircularQueue[T <: Data](gen: T, entries: Int) extends Module {
    val io = IO(new Bundle {
        val enq = Flipped(Decoupled(gen))
        val deq = Decoupled(gen)
        val count = Output(UInt(log2Ceil(entries + 1).W))

        // Pointers are useful for ROBs to use as IDs (ROB Tags)
        val headPtr = Output(UInt(log2Ceil(entries).W))
        val tailPtr = Output(UInt(log2Ceil(entries).W))
        val flush = Input(Bool())
    })

    val ram = Reg(Vec(entries, gen))
    val head = RegInit(0.U(log2Ceil(entries).W))
    val tail = RegInit(0.U(log2Ceil(entries).W))
    val maybeFull = RegInit(false.B)

    val ptrMatch = head === tail
    val empty = ptrMatch && !maybeFull
    val full = ptrMatch && maybeFull

    val doEnq = io.enq.ready && io.enq.valid
    val doDeq = io.deq.ready && io.deq.valid

    // Enqueue logic
    when(doEnq) {
        ram(tail) := io.enq.bits
        tail := Mux(tail === (entries - 1).U, 0.U, tail + 1.U)
    }

    // Dequeue logic
    when(doDeq) {
        head := Mux(head === (entries - 1).U, 0.U, head + 1.U)
    }

    // Full/Empty state update
    when(doEnq =/= doDeq) {
        maybeFull := doEnq
    }

    // Flush logic
    when(io.flush) {
        head := 0.U
        tail := 0.U
        maybeFull := false.B
    }

    io.enq.ready := !full
    io.deq.valid := !empty
    io.deq.bits := ram(head)

    io.headPtr := head
    io.tailPtr := tail

    // Calculate count
    val ptrDiff = tail - head
    if (isPow2(entries)) {
        io.count := Mux(full, entries.U, ptrDiff)
    } else {
        io.count := Mux(
          full,
          entries.U,
          Mux(tail >= head, ptrDiff, entries.U + ptrDiff)
        )
    }
}
