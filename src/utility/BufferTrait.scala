package utility

import chisel3._
import chisel3.util._

trait QueueControlLogic {
    // Requirements
    def numEntries: Int 
    
    // --- State ---
    val head = RegInit(0.U(log2Ceil(numEntries).W))
    val tail = RegInit(0.U(log2Ceil(numEntries).W))
    val maybeFull = RegInit(false.B)
    
    // NEW: Explicit Valid Vector
    // This replaces the need to do (i >= head && i < tail) logic everywhere
    val valids = RegInit(VecInit(Seq.fill(numEntries)(false.B)))

    // --- Derived Status ---
    val ptrMatch = head === tail
    val isEmpty = ptrMatch && !maybeFull
    val isFull = ((head + 1.U)(log2Ceil(numEntries) - 1, 0) === tail)
    
    def getCount: UInt = {
        Mux(isFull, numEntries.U, Mux(tail >= head, tail - head, numEntries.U + tail - head))
    }

    // --- Helper: Index Logic ---
    def incPtr(ptr: UInt): UInt = {
        Mux(ptr === (numEntries - 1).U, 0.U, ptr + 1.U)
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
        for (i <- 0 until numEntries) {
            when(killMask(i)) {
                valids(i) := false.B
            }
        }
    }
}

trait NonOrderedLogic {
    // Requirements
    def numEntries: Int 

    val valids = RegInit(VecInit(Seq.fill(numEntries)(false.B)))
    val lastIssuedIndex = RegInit((numEntries - 1).U(log2Ceil(numEntries).W))

    val isFull = valids.asUInt.andR
    val isEmpty = !valids.asUInt.orR
    
    val enqueuePtr = PriorityEncoder(valids.map(!_))

    def getCount: UInt = PopCount(valids)

    // --- Action: Enqueue ---
    // Marks the current 'enqueuePtr' as valid. 
    // Usage: call this when io.in.fire
    def onEnqueue(): Unit = {
        valids(enqueuePtr) := true.B
    }

    // --- Action: Issue (Dequeue) ---
    // Marks the specific index as free and updates Round-Robin state.
    // Usage: call this when io.out.fire with the chosen index
    def onIssue(idx: UInt): Unit = {
        valids(idx) := false.B
        lastIssuedIndex := idx
    }

    // --- Action: Flush ---
    // Clears valid bits based on the mask.
    def onFlush(killMask: Vec[Bool]): Unit = {
        for (i <- 0 until numEntries) {
            when(killMask(i)) {
                valids(i) := false.B
            }
        }
    }

    // --- Helper: Round-Robin Selection ---
    // Calculates the "Head" (issue pointer) based on who is ready.
    // Returns: (isValidCandidate, selectedIndex)
    def getIssuePtr(candidates: Vec[Bool]): (Bool, UInt) = {
        val maskedCandidates = Wire(Vec(numEntries, Bool()))
        // Mask out entries that have already had their turn this cycle
        for (i <- 0 until numEntries) {
            maskedCandidates(i) := candidates(i) && (i.U > lastIssuedIndex)
        }

        val hasCandidateInMask = maskedCandidates.asUInt.orR
        val nextIndex = PriorityEncoder(maskedCandidates)
        val wrapIndex = PriorityEncoder(candidates)

        val finalIndex = Mux(hasCandidateInMask, nextIndex, wrapIndex)
        val hasGrant = candidates.asUInt.orR

        (hasGrant, finalIndex)
    }
}
