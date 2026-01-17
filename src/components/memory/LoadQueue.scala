package components.memory

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility._
import common.MemOpWidth
import os.size

class LoadBufferEntry(sqIdxWidth: Int) extends Bundle {
    val robTag = UInt(ROB_WIDTH.W)
    val pdst = UInt(PREG_WIDTH.W)

    val addrPreg = UInt(PREG_WIDTH.W)
    val addrReady = Bool()
    val addrVal = UInt(32.W)
    val imm = UInt(32.W)

    val SQtail = UInt(sqIdxWidth.W)

    // AGU state
    val addrComputed = Bool()
    val addrResolved = UInt(32.W)

    // ROB tag of the Store that blocks this Load (if any)
    val sleeping = Bool()
    val wakeTag = UInt(ROB_WIDTH.W)

    // General Memory Operation Info
    val opWidth = MemOpWidth()
    val isUnsigned = Bool()
}

class LoadBuffer(numEntriesLQ: Int, numEntriesSQ: Int)
    extends CycleAwareModule
    with NonOrderedLogic {
    def numEntries = numEntriesLQ
    val sqIdxWidth = log2Ceil(numEntriesSQ)

    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new LoadBufferEntry(sqIdxWidth)))
        val out = Decoupled(new LoadBufferEntry(sqIdxWidth)) // To LSA/Cache
        val forwardOut = Decoupled(new BroadcastBundle())
        

        val broadcastIn = Input(Valid(new BroadcastBundle()))

        val flush = Input(new FlushBundle)

        // Store Queue data
        val SQcontents = Input(Vec(numEntriesSQ, Valid(new StoreQueueEntry())))
        val SQptrs = Input(new Bundle { val head = UInt(sqIdxWidth.W); val tail = UInt(sqIdxWidth.W) ; val maybeFull = Bool() })

        // Profiling
        val count =
            if (common.Configurables.Profiling.Utilization)
                Some(Output(UInt(log2Ceil(numEntries + 1).W)))
            else None
    })

    val buffer = Reg(Vec(numEntriesLQ, new LoadBufferEntry(sqIdxWidth)))

    // Defaults
    io.in.ready := !isFull && !io.flush.valid
    io.out.valid := false.B
    io.out.bits := DontCare
    io.forwardOut.valid := false.B
    io.forwardOut.bits := DontCare

    // Enqueue logic
    when(io.in.fire) {
        val e = Wire(new LoadBufferEntry(sqIdxWidth))
        e := io.in.bits
        e.addrComputed := false.B
        e.sleeping := false.B
        e.SQtail := io.SQptrs.tail
        buffer(enqueuePtr) := e
        onEnqueue()
        printf(p"[LQ] Enqueue: robTag=${e.robTag} pdst=${e.pdst} addrTag=${e.addrPreg} addrReady=${e.addrReady}\n")
    }

    // Broadcast update
    when(io.broadcastIn.valid) {
        val b = io.broadcastIn.bits
        for (i <- 0 until numEntriesLQ) {
            when(valids(i)) {
                // Address ready
                when(buffer(i).addrPreg === b.pdst) {
                    buffer(i).addrReady := true.B
                    buffer(i).addrVal := b.data
                }
                // Wakeup from Store
                when(buffer(i).sleeping && buffer(i).wakeTag === b.robTag) {
                    buffer(i).sleeping := false.B
                }
            }
        }
    }

    // AGU arbitration & calculation
    val calcCandidates = Wire(Vec(numEntriesLQ, Bool()))
    for (i <- 0 until numEntriesLQ) {
        val entry = buffer(i)
        calcCandidates(i) := valids(i) && !entry.addrComputed && entry.addrReady
    }
    val calcIdx = PriorityEncoder(calcCandidates)
    val calcValid = calcCandidates.asUInt.orR
    when(calcValid) {
        val entry = buffer(calcIdx)
        entry.addrComputed := true.B
        entry.addrResolved := entry.addrVal + entry.imm
    }


    def getByteMask(addr: UInt, width: MemOpWidth.Type): UInt = {
        val offset = addr(1, 0)
        val mask = Wire(UInt(4.W))
        mask := 0.U
        switch(width) {
            is(MemOpWidth.BYTE) { mask := 1.U << offset }
            is(MemOpWidth.HALFWORD) { mask := 3.U << offset }
            is(MemOpWidth.WORD) { mask := 15.U }
        }
        mask
    }
    // Load-Store Dependency Check
    val checkCandidates = Wire(Vec(numEntriesLQ, Bool()))
    for (i <- 0 until numEntriesLQ) {
        val entry = buffer(i)
        checkCandidates(i) := valids(i) && entry.addrComputed && !entry.sleeping
    }
    val checkIdx = PriorityEncoder(checkCandidates)
    val checkValid = checkCandidates.asUInt.orR
    when(checkValid) {
        val loadEntry = buffer(checkIdx)
        val loadAddr  = loadEntry.addrResolved
        val loadMask  = getByteMask(loadAddr, loadEntry.opWidth)
        val loadRob   = loadEntry.robTag
        
        val forwardCandidates = Wire(Vec(numEntriesSQ, Bool()))
        val conflictCandidates = Wire(Vec(numEntriesSQ, Bool()))

        for (i <- 0 until numEntriesSQ) {
            val sq = io.SQcontents(i)
            forwardCandidates(i) := false.B
            conflictCandidates(i) := false.B

            when(sq.valid) {
                val isOlder = {
                    val head = io.SQptrs.head
                    val tail = loadEntry.SQtail
                    val idx = i.U
                    
                    Mux(head <= tail,
                        idx >= head && idx < tail,
                        idx >= head || idx < tail
                    )
                }

                when(isOlder) {
                    when(!sq.bits.addrComputed) {
                        conflictCandidates(i) := true.B
                    } .otherwise {
                        val roughMatch = sq.bits.addrResolved(31, 2) === loadAddr(31, 2)
                        
                        when(roughMatch) {
                            val storeMask = getByteMask(sq.bits.addrResolved, sq.bits.opWidth)
                            val canForward = (loadMask & storeMask) === loadMask
                            val overlap = (loadMask & storeMask).orR

                            forwardCandidates(i) := canForward && sq.bits.dataReady
                            conflictCandidates(i) := overlap
                        }
                    }
                }
            } 
        }
        val tailMask = (1.U << loadEntry.SQtail) - 1.U
        
        // Split the overlap vector into two zones
        val matchesUInt = forwardCandidates.asUInt
        val lowerMatches = matchesUInt & tailMask
        val upperMatches = matchesUInt & ~tailMask

        def getHighestIndex(vec: UInt): UInt = {
            val width = numEntriesSQ
            val reversed = Reverse(vec) 
            (width - 1).U - PriorityEncoder(reversed)
        }

        val lowerValid = lowerMatches.orR
        val upperValid = upperMatches.orR

        val bestMatchIdx = Mux(lowerValid, 
                               getHighestIndex(lowerMatches), 
                               getHighestIndex(upperMatches))
        
        val anyMatch = lowerValid || upperValid

        val hasConflict = conflictCandidates.asUInt.orR

        val conflictMatchesUInt = conflictCandidates.asUInt

        val lowerC = conflictMatchesUInt & tailMask
        val upperC = conflictMatchesUInt & ~tailMask

        val youngestConflictIdx = Mux(lowerC.orR, 
                                      getHighestIndex(lowerC), 
                                      getHighestIndex(upperC))

        val storeToWaitOn = io.SQcontents(youngestConflictIdx).bits

        val canForwardNow = anyMatch && (bestMatchIdx === youngestConflictIdx)
        when(canForwardNow) {
            val storeEntry  = io.SQcontents(bestMatchIdx).bits
            val shiftAmt = (loadAddr(1, 0) - storeEntry.addrResolved(1, 0)) << 3
            val rawData  = storeEntry.dataVal >> shiftAmt

            val maskedData = Wire(UInt(32.W))
            maskedData := 0.U
            switch(loadEntry.opWidth) {
                is(MemOpWidth.BYTE)     { maskedData := rawData(7, 0) }
                is(MemOpWidth.HALFWORD) { maskedData := rawData(15, 0) }
                is(MemOpWidth.WORD)     { maskedData := rawData(31, 0) }
            }

            val isByte = loadEntry.opWidth === MemOpWidth.BYTE
            val isHalf = loadEntry.opWidth === MemOpWidth.HALFWORD
            
            val extData = Wire(UInt(32.W))
            when(!loadEntry.isUnsigned && isByte) {
                extData := Cat(Fill(24, maskedData(7)), maskedData(7, 0))
            } .elsewhen(!loadEntry.isUnsigned && isHalf) {
                extData := Cat(Fill(16, maskedData(15)), maskedData(15, 0))
            } .otherwise {
                extData := maskedData
            }

            // We can forward the data,but how?
            io.forwardOut.valid := true.B
            io.forwardOut.bits.pdst := loadEntry.pdst
            io.forwardOut.bits.robTag := loadEntry.robTag
            io.forwardOut.bits.data := extData
            io.forwardOut.bits.writeEn := true.B
            when(io.forwardOut.fire) {
                printf(p"[LQ] Forwarding: robTag=${loadEntry.robTag} from SQ index ${bestMatchIdx}\n")
            }
        }.elsewhen(!hasConflict) {
            // No conflict found, can send out the load
            io.out.valid := true.B
            io.out.bits := loadEntry
            when(io.out.fire) {
                printf(p"[LQ] Issue to Mem: robTag=${loadEntry.robTag} addr=${Hexadecimal(loadEntry.addrResolved)}\n")
            }
        }.otherwise{
            // we have a conflict
            buffer(checkIdx).sleeping := true.B
            buffer(checkIdx).wakeTag  := storeToWaitOn.robTag
            printf(p"[LQ] Load Sleeping: robTag=${loadEntry.robTag} waiting on Store robTag=${storeToWaitOn.robTag}\n")
        }

        when(io.out.fire || io.forwardOut.fire) {
            onIssue(checkIdx)
        }
    }
    

    // Flush logic
    val killMask = Wire(Vec(numEntriesLQ, Bool()))
    for (i <- 0 until numEntriesLQ) {
        killMask(i) := valids(i) && io.flush.checkKilled(buffer(i).robTag)
    }
    when(io.flush.valid) {
        onFlush(killMask)
    }


    // Profiling
    io.count.foreach(_ := getCount)
}
