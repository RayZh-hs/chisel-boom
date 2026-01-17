package components.memory

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility.{CycleAwareModule, QueueControlLogic}

class StoreQueueEntry extends Bundle {
    val robTag = UInt(ROB_WIDTH.W)
    
    val addrTag   = UInt(PREG_WIDTH.W)
    val addrReady = Bool()

    val dataTag   = UInt(PREG_WIDTH.W)
    val dataReady = Bool()
    val imm       = UInt(32.W)

    val addrVal   = UInt(32.W)         // Base address value
    val dataVal   = UInt(32.W)         // Store data value
    
    val addrComputed = Bool()          // Has the Adder run?
    val addrResolved = UInt(32.W)      // The final address (addrVal + imm)
    
    val opWidth     = MemOpWidth()
    val isUnsigned  = Bool()
    
    // Helper to expose valid bit in the bundle for the LQ
    val valid       = Bool() 
}

class StoreQueue(val entries: Int) extends CycleAwareModule with QueueControlLogic {

    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new StoreQueueEntry()))
        val out = Decoupled(new StoreQueueEntry())
        // CDB
        val broadcastIn = Input(Valid(new BroadcastBundle()))

        val prfRead = new PRFReadBundle
        
        val flush = Input(new FlushBundle)
        val robHead = Input(UInt(ROB_WIDTH.W)) 

        // Commit Broadcast to notify rob ready
        val broadcastOut = Output(Valid(new BroadcastBundle())) 

        // Monitor for LQ
        val contents = Output(Vec(entries, new StoreQueueEntry))
        val ptrs = Output(new Bundle { val head = UInt(); val tail = UInt() })
    })

    val buffer = Reg(Vec(entries, new StoreQueueEntry()))

    for(i <- 0 until entries) {
        io.contents(i) := buffer(i)
        io.contents(i).valid := valids(i) 
    }
    io.ptrs.head := head
    io.ptrs.tail := tail

    // Enqueue
    io.in.ready := !isFull
    when(io.in.fire) {
        val e = io.in.bits
        e.addrComputed := false.B         
        buffer(tail) := e
        onEnqueue()
    }

    // CDB Broadcast Handling
    when(io.broadcastIn.valid) {
        val tag = io.broadcastIn.bits.pdst
        val value = io.broadcastIn.bits.data
        
        for (i <- 0 until entries) {
            when(valids(i)) {
                when(!buffer(i).addrReady && buffer(i).addrTag === tag) {
                    buffer(i).addrReady := true.B
                    buffer(i).addrVal   := value
                }
                when(!buffer(i).dataReady && buffer(i).dataTag === tag) {
                    buffer(i).dataReady := true.B
                    buffer(i).dataVal   := value
                }
            }
        }
    }

    // =========================================================================
    // 4. Background Calculator (One Shared Adder)
    // =========================================================================
    // Finds a valid entry that has Base Ready but Address Not Computed
    
    val calcCandidates = Wire(Vec(entries, Bool()))
    for (i <- 0 until entries) {
        calcCandidates(i) := valids(i) && buffer(i).addrReady && !buffer(i).addrComputed
    }

    val calcIdx = PriorityEncoder(calcCandidates)
    val doCalc = calcCandidates.asUInt.orR

    when(doCalc) {
        // This is the ONLY adder for address generation in the SQ
        val entry = buffer(calcIdx)
        buffer(calcIdx).addrResolved := entry.addrVal + entry.imm
        buffer(calcIdx).addrComputed := true.B
    }

    // =========================================================================
    // 5. Commit / Issue Logic
    // =========================================================================
    val headEntry = buffer(head)
    
    // We can commit if: 
    // 1. Address is calculated
    // 2. Data is captured
    // 3. We are the oldest (ROB Head)
    val headReady = headEntry.addrComputed && headEntry.dataReady
    val isCommitting = (io.robHead === headEntry.robTag)
    
    // --- Broadcast "Ready to Commit" (Optional per your design) ---
    val headBroadcastIssued = RegInit(false.B)
    
    // Reset flag if head changes
    val lastHead = RegNext(head)
    when(lastHead =/= head) { headBroadcastIssued := false.B }

    io.broadcastOut.valid := false.B
    io.broadcastOut.bits := DontCare

    // Broadcast only once per instruction
    when(headReady && !headBroadcastIssued) {
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.robTag := headEntry.robTag
        
        when(io.broadcastOut.fire) {
            headBroadcastIssued := true.B
        }
    }

    // --- Output to Memory ---
    io.out.valid := false.B
    io.out.bits := headEntry

    when(headBroadcastIssued && isCommitting) {
        io.out.valid := true.B
        when(io.out.fire) {
            onDequeue() // Clears valids(head) = false
        }
    }

    // =========================================================================
    // 6. Flush Logic (Simplified via Trait)
    // =========================================================================
    when(io.flush.valid) {
        val killMask = Wire(Vec(entries, Bool()))
        
        // 1. Identify who dies
        for(i <- 0 until entries) {
            // Check valid bit so we don't process garbage
            killMask(i) := valids(i) && io.flush.checkKilled(buffer(i).robTag)
        }
        
        val anyKilled = killMask.asUInt.orR
        
        // 2. Calculate where the tail should revert to
        // Logic: Find the oldest killed instruction. That becomes the new tail.
        val doubledKillMask = Cat(killMask.asUInt, killMask.asUInt)
        val shiftedKillMask = doubledKillMask >> head
        val distToFirstKill = PriorityEncoder(shiftedKillMask(entries - 1, 0))
        
        val nextTail = head +& distToFirstKill
        val wrappedNewTail = Mux(nextTail >= entries.U, nextTail - entries.U, nextTail)

        // 3. Apply Flush
        when(anyKilled) {
            onFlush(wrappedNewTail, killMask)
            
            // Note: If head was killed, reset head-specific state
            if(true) { // scoping
               val headKilled = killMask(head)
               when(headKilled) {
                   headBroadcastIssued := false.B
               }
            }
        }
    }
}