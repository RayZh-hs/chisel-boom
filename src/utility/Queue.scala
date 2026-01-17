package utility

import chisel3._
import chisel3.util._

trait QueueControlLogic {
    // Requirements
    def entries: Int 
    
    // --- State ---
    val head = RegInit(0.U(log2Ceil(entries).W))
    val tail = RegInit(0.U(log2Ceil(entries).W))
    val maybeFull = RegInit(false.B)
    
    // NEW: Explicit Valid Vector
    // This replaces the need to do (i >= head && i < tail) logic everywhere
    val valids = RegInit(VecInit(Seq.fill(entries)(false.B)))

    // --- Derived Status ---
    val ptrMatch = head === tail
    val isEmpty = ptrMatch && !maybeFull
    val isFull = ptrMatch && maybeFull
    
    def getCount: UInt = {
        Mux(isFull, entries.U, Mux(tail >= head, tail - head, entries.U + tail - head))
    }

    // --- Helper: Index Logic ---
    def incPtr(ptr: UInt): UInt = {
        Mux(ptr === (entries - 1).U, 0.U, ptr + 1.U)
    }

    // --- Action: Enqueue ---
    // Sets the bit at current tail and moves tail forward
    def onEnqueue(): Unit = {
        valids(tail) := true.B // Mark valid
        tail := incPtr(tail)
        maybeFull := true.B
    }

    // --- Action: Dequeue ---
    // Clears the bit at current head and moves head forward
    def onDequeue(): Unit = {
        valids(head) := false.B // Mark invalid
        head := incPtr(head)
        maybeFull := false.B
    }

    // --- Action: Flush ---
    // Updates pointers and CLEARS the valid bits for killed entries.
    // This ensures continuity is maintained.
    def onFlush(newTail: UInt, killMask: Vec[Bool]): Unit = {
        tail := newTail
        maybeFull := false.B // If we flush, we are definitely not full (unless we flush 0 items, handled by logic)
        
        // Instant Clear: The explicit valid bits allow us to just mask them out.
        for (i <- 0 until entries) {
            when(killMask(i)) {
                valids(i) := false.B
            }
        }
    }
}