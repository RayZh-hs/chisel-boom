package components.memory

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility.{CycleAwareModule, QueueControlLogic}

class StoreQueueEntry extends Bundle {
    val robTag = UInt(ROB_WIDTH.W)
    
    val addrPreg   = UInt(PREG_WIDTH.W)
    val addrReady = Bool()
    val addrVal   = UInt(32.W)

    val dataPreg   = UInt(PREG_WIDTH.W)
    val dataReady = Bool()
    val dataVal   = UInt(32.W)

    val imm       = UInt(32.W)
    
    val addrComputed = Bool()          // Has the Adder run?
    val addrResolved = UInt(32.W)      // The final address (addrVal + imm)

    val broadcasted = Bool()        // Has this entry been broadcasted as ready to commit?
    val committed    = Bool()        // Has this entry been committed?
    
    // General Memory Operation Info
    val opWidth     = MemOpWidth()
    val isUnsigned  = Bool()
}

class StoreQueue(entries: Int) extends CycleAwareModule with QueueControlLogic {
    def numEntries = entries

    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new StoreQueueEntry()))
        val out = Decoupled(new StoreQueueEntry())
        // CDB
        val broadcastIn = Input(Valid(new BroadcastBundle()))

        val flush = Input(new FlushBundle)
        val robHead = Input(UInt(ROB_WIDTH.W)) 

        // Commit Broadcast to notify rob ready
        val broadcastOut = Decoupled(new BroadcastBundle()) 

        // Monitor for LQ
        val contents = Output(Vec(numEntries, Valid(new StoreQueueEntry())))
        val ptrs = Output(new Bundle { val head = UInt(); val tail = UInt(); val maybeFull = Bool() })

        // Profiling
        val count =
            if (common.Configurables.Profiling.Utilization)
                Some(Output(UInt(log2Ceil(numEntries + 1).W)))
            else None
    })

    val buffer = Reg(Vec(numEntries, new StoreQueueEntry()))

    for(i <- 0 until numEntries) {
        io.contents(i).bits := buffer(i)
        io.contents(i).valid := valids(i) 
    }
    io.ptrs.head := head
    io.ptrs.tail := tail
    io.ptrs.maybeFull := maybeFull

    // Enqueue
    io.in.ready := !isFull && !io.flush.valid
    when(io.in.fire) {
        val e = Wire(new StoreQueueEntry())
        e := io.in.bits
        e.addrComputed := false.B
        e.committed := false.B
        e.broadcasted := false.B
        buffer(tail) := e
        onEnqueue()
        printf(p"[SQ] Enqueue: robTag=${e.robTag} addrTag=${e.addrPreg} addrReady=${e.addrReady} dataPreg=${e.dataPreg} dataReady=${e.dataReady}\n")
    }

    // CDB Broadcast Handling
    when(io.broadcastIn.valid) {
        val tag = io.broadcastIn.bits.pdst
        val value = io.broadcastIn.bits.data
        
        for (i <- 0 until numEntries) {
            when(valids(i)) {
                when(!buffer(i).addrReady && buffer(i).addrPreg === tag) {
                    buffer(i).addrReady := true.B
                    buffer(i).addrVal   := value
                }
                when(!buffer(i).dataReady && buffer(i).dataPreg === tag) {
                    buffer(i).dataReady := true.B
                    buffer(i).dataVal   := value
                }
            }
        }
    }

    // Background Calculator (One Shared Adder)
    
    val calcCandidates = Wire(Vec(numEntries, Bool()))
    for (i <- 0 until numEntries) {
        calcCandidates(i) := valids(i) && buffer(i).addrReady && !buffer(i).addrComputed
    }
    val calcIdx = PriorityEncoder(calcCandidates)
    val doCalc = calcCandidates.asUInt.orR
    when(doCalc) {
        val entry = buffer(calcIdx)
        buffer(calcIdx).addrResolved := entry.addrVal + entry.imm
        buffer(calcIdx).addrComputed := true.B
    }

    // Defaults
    io.broadcastOut.valid := false.B
    io.broadcastOut.bits := DontCare

    // Broadcast & Commit
    val readyMask = Wire(Vec(numEntries, Bool()))
    for(i <- 0 until numEntries){
        when(buffer(i).robTag === io.robHead) {
            buffer(i).committed := true.B
        }
        readyMask(i) := valids(i) && buffer(i).addrComputed && buffer(i).dataReady && !buffer(i).broadcasted
    }
    val anyBroadcast = readyMask.asUInt.orR
    val broadcastIdx = PriorityEncoder(readyMask)
    when(anyBroadcast){
        val entry = buffer(broadcastIdx)
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.robTag := entry.robTag
        io.broadcastOut.bits.pdst := 0.U
        io.broadcastOut.bits.data := 0.U
        io.broadcastOut.bits.writeEn := false.B
        when(io.broadcastOut.fire){
            buffer(broadcastIdx).broadcasted := true.B
            printf(p"[SQ] Broadcast Done: robTag=${entry.robTag}\n")
        }
    }


    // --- Output to Memory ---
    val headEntry = buffer(head)
    io.out.valid := false.B
    io.out.bits := headEntry

    when(headEntry.broadcasted && headEntry.committed && valids(head)) {
        io.out.valid := true.B
        when(io.out.fire) {
            onDequeue() // Clears valids(head) = false
            printf(p"[SQ] Dequeue (Writeback): robTag=${headEntry.robTag} addr=${Hexadecimal(headEntry.addrResolved)} data=${Hexadecimal(headEntry.dataVal)}\n")
        }
    }

    // =========================================================================
    // 6. Flush Logic (Simplified via Trait)
    // =========================================================================
    when(io.flush.valid) {
        val killMask = Wire(Vec(numEntries, Bool()))
        
        // 1. Identify who dies
        for(i <- 0 until numEntries) {
            // Check valid bit so we don't process garbage
            killMask(i) := valids(i) && !buffer(i).committed && io.flush.checkKilled(buffer(i).robTag)
        }
        
        val anyKilled = killMask.asUInt.orR
        
        // 2. Calculate where the tail should revert to
        // Logic: Find the oldest killed instruction. That becomes the new tail.
        val doubledKillMask = Cat(killMask.asUInt, killMask.asUInt)
        val shiftedKillMask = doubledKillMask >> head
        val distToFirstKill = PriorityEncoder(shiftedKillMask(numEntries - 1, 0))
        
        val nextTail = head +& distToFirstKill
        val wrappedNewTail = Mux(nextTail >= numEntries.U, nextTail - numEntries.U, nextTail)

        // 3. Apply Flush
        when(anyKilled) {
            onFlush(wrappedNewTail, killMask)
        }
    }

    // Profiling
    io.count.foreach(_ := getCount)
}