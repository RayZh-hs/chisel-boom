package components.structures

import chisel3._
import chisel3.util._

class FreeList(numRegs: Int, numArchRegs: Int) extends Module {
    val width = log2Ceil(numRegs)
    val capacity = numRegs - numArchRegs

    val io = IO(new Bundle {
        val allocate = Decoupled(UInt(width.W)) // Dequeue: Get a free register
        val free = Flipped(Decoupled(UInt(width.W))) // Enqueue: Return a register to free list
        val rollbackFree = Flipped(Decoupled(UInt(width.W))) // Enqueue: Return a register to free list during rollback
    })

    // Initialize the RAM with the registers that are free at reset (numArchRegs to numRegs-1)
    // We use RegInit to ensure the free list is populated at reset.
    val initialRegs = Seq.tabulate(capacity)(i => (i + numArchRegs).U(width.W))
    val ram = RegInit(VecInit(initialRegs))

    val head = RegInit(0.U(log2Ceil(capacity).W))
    val tail = RegInit(0.U(log2Ceil(capacity).W))
    val maybeFull = RegInit(true.B) // Starts full

    val ptrMatch = head === tail
    val empty = ptrMatch && !maybeFull
    val full = ptrMatch && maybeFull

    // Allocation (Dequeue) logic
    io.allocate.valid := !empty
    io.allocate.bits := ram(head)

    val doAlloc = io.allocate.ready && io.allocate.valid
    when(doAlloc) {
        head := Mux(head === (capacity - 1).U, 0.U, head + 1.U)
    }

    // Freeing (Enqueue) logic
    // TODO: simplify the logic for handling 2 enqueues
    io.free.ready := !full
    io.rollbackFree.ready := !full && (io.free.ready && !io.free.valid || tail =/= Mux(head === 0.U, (capacity - 1).U, head - 1.U)) // Simplified check for 2 spaces

    val doFree = io.free.ready && io.free.valid
    val doRollbackFree = io.rollbackFree.ready && io.rollbackFree.valid

    when(doFree) {
        ram(tail) := io.free.bits
    }
    when(doRollbackFree) {
        val targetIdx = Mux(doFree, Mux(tail === (capacity - 1).U, 0.U, tail + 1.U), tail)
        ram(targetIdx) := io.rollbackFree.bits
    }

    val numFreed = doFree.asUInt +& doRollbackFree.asUInt
    val nextTail = tail +& numFreed
    tail := Mux(nextTail >= capacity.U, nextTail - capacity.U, nextTail)

    // Update full/empty state
    when(numFreed > doAlloc.asUInt) {
        maybeFull := true.B
    }.elsewhen(doAlloc && numFreed === 0.U) {
        maybeFull := false.B
    }
}
