package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures._
import utility.CycleAwareModule

class LoadStoreAdaptor extends CycleAwareModule {
    val io = IO(new Bundle {
        val issueIn      = Flipped(Decoupled(new SequentialBufferEntry(new LoadStoreInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        val broadcastIn  = Input(Valid(new BroadcastBundle))
        val prfRead      = new PRFReadBundle
        val flush        = Input(new FlushBundle)
        val robHead      = Input(UInt(ROB_WIDTH.W))

        val mem          = new MemoryRequest
    })

    // --- LSQ Instance ---
    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 8, "LSQ"))
    lsq.io.in <> io.issueIn
    lsq.io.broadcast := io.broadcastIn
    lsq.io.flush     := io.flush

    // --- Pipeline Registers ---
    
    // Stage 1: Decode / Read PRF
    val s1Valid = RegInit(false.B)
    val s1Bits  = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    // Stage 2: Address Calc / Store Commit Check / Mem Request
    val s2Valid = RegInit(false.B)
    val s2Bits  = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s2Data1 = Reg(UInt(32.W))
    val s2Data2 = Reg(UInt(32.W))
    
    // S2 Control State
    val s2StoreReadySent = RegInit(false.B) 
    val s2IsCommitted    = RegInit(false.B) // Latch for Bug #1: Remember if we saw robHead

    // Stage 3: Wait for Response / Writeback
    val s3Valid       = RegInit(false.B)
    val s3Bits        = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s3Data        = Reg(UInt(32.W))
    val s3AddrDebug   = Reg(UInt(32.W)) 
    val s3WaitingResp = RegInit(false.B) // Bug #2: Wait for resp for Stores too

    // --- Internal Wires ---
    val s1Ready = Wire(Bool())
    val s2Ready = Wire(Bool())
    val s3Ready = Wire(Bool())

    // --- Broadcast Arbiter ---
    val wbArbiter = Module(new RRArbiter(new BroadcastBundle, 2))
    io.broadcastOut <> wbArbiter.io.out

    // =================================================================================
    // Stage 1: Issue & PRF Read
    // =================================================================================

    lsq.io.out.ready := s1Ready

    // Standard Flush Logic for S1 (nothing here is committed yet)
    val s1Killed = io.flush.checkKilled(lsq.io.out.bits.robTag)

    when (s1Ready) {
        s1Valid := lsq.io.out.valid && !s1Killed
        s1Bits  := lsq.io.out.bits
    }.elsewhen(io.flush.checkKilled(s1Bits.robTag)) {
        s1Valid := false.B
    }

    io.prfRead.addr1 := s1Bits.src1
    io.prfRead.addr2 := s1Bits.src2

    // Handshake S1 -> S2
    val s1Fire = s1Valid && s2Ready
    s1Ready := !s1Valid || s2Ready

    // =================================================================================
    // Stage 2: Execute, Address Calc, Store Logic, Mem Request
    // =================================================================================

    val isStoreS2 = s2Bits.info.isStore
    val isLoadS2  = !isStoreS2
    val effAddr   = (s2Data1.asSInt + s2Bits.info.imm.asSInt).asUInt

    // --- Commit Logic (Bug #1 & #3) ---
    // A store is committed if we already latched it, OR if it's currently at ROB head.
    val storeMatchesHead = s2Valid && isStoreS2 && (io.robHead === s2Bits.robTag)
    
    when(storeMatchesHead && s2StoreReadySent) {
        s2IsCommitted := true.B
    }

    // Determine if S2 is effectively committed this cycle
    val s2EffectiveCommitted = s2IsCommitted || storeMatchesHead

    // Flush Logic S2:
    // 1. Loads: Always killable (speculative).
    // 2. Stores: Killable ONLY if not yet committed. 
    //    If s2EffectiveCommitted is true, it ignores flush (Bug #3).
    val s2Killed = io.flush.checkKilled(s2Bits.robTag) && !(isStoreS2 && s2EffectiveCommitted)

    // --- Store Broadcast Logic ---
    // Notify ROB that Store is Ready (Addr/Data calculated). 
    // We do this before commit. Must check flush normally here (if killed, don't broadcast).
    val s2BroadcastingReady = s2Valid && isStoreS2 && !s2StoreReadySent && !s2Killed

    wbArbiter.io.in(0).valid        := s2BroadcastingReady
    wbArbiter.io.in(0).bits.pdst    := s2Bits.pdst
    wbArbiter.io.in(0).bits.robTag  := s2Bits.robTag
    wbArbiter.io.in(0).bits.data    := 0.U
    wbArbiter.io.in(0).bits.writeEn := false.B

    when(wbArbiter.io.in(0).fire) {
        s2StoreReadySent := true.B
    }

    // --- Memory Request Logic ---
    // Load: Fire if valid.
    // Store: Fire only if committed.
    val s2MemReqRequired = s2Valid && !s2Killed && (isLoadS2 || (isStoreS2 && s2EffectiveCommitted))
    
    io.mem.req.valid           := s2MemReqRequired && s3Ready
    io.mem.req.bits.addr       := effAddr
    io.mem.req.bits.data       := s2Data2
    io.mem.req.bits.isLoad     := isLoadS2
    io.mem.req.bits.opWidth    := s2Bits.info.opWidth
    io.mem.req.bits.isUnsigned := s2Bits.info.isUnsigned
    io.mem.req.bits.targetReg  := s2Bits.pdst

    // Transition S2 -> S3
    // Fire if we successfully issue to memory
    val s2Fire = s2MemReqRequired && s3Ready && io.mem.req.ready

    s2Ready := !s2Valid || s2Fire || s2Killed

    when (s2Ready) {
        // If s2Killed, we effectively invalidate the next state update
        val validNext = s1Fire && !io.flush.checkKilled(s1Bits.robTag)
        
        s2Valid := validNext
        s2Bits  := s1Bits
        s2Data1 := io.prfRead.data1
        s2Data2 := io.prfRead.data2
        
        // Reset control flags for new instruction
        s2StoreReadySent := false.B
        s2IsCommitted    := false.B
    }.elsewhen(s2Killed) {
        // Asynchronous kill for uncommitted ops
        s2Valid := false.B
    }

    // =================================================================================
    // Stage 3: Variable Latency Response & Writeback
    // =================================================================================

    val isStoreS3 = s3Bits.info.isStore
    val isLoadS3  = !isStoreS3

    // Flush Logic S3 (Bug #3):
    // Stores in S3 are inherently committed (passed S2 check), so never kill them.
    // Loads in S3 are speculative, kill them on flush.
    val s3Killed = io.flush.checkKilled(s3Bits.robTag) && isLoadS3

    // Memory Response Handling
    // We assume the response correlates to the instruction currently waiting in S3.
    // Bug #2: We wait for response for BOTH Load (Data) and Store (Ack).
    when(io.mem.resp.valid) {
        s3Data        := io.mem.resp.bits
        s3WaitingResp := false.B
    }
    
    // Always ready to latch response
    io.mem.resp.ready := true.B 

    // --- S3 Output Logic ---
    // Done if Valid, Not Waiting for Resp, and Not Killed
    val s3IsDone = s3Valid && !s3WaitingResp && !s3Killed

    // Writeback Arbiter (Priority 1)
    // Only Loads need to broadcast data. Stores just drain.
    wbArbiter.io.in(1).valid        := s3IsDone && isLoadS3
    wbArbiter.io.in(1).bits.pdst    := s3Bits.pdst
    wbArbiter.io.in(1).bits.robTag  := s3Bits.robTag
    wbArbiter.io.in(1).bits.data    := s3Data
    wbArbiter.io.in(1).bits.writeEn := true.B

    // S3 clear conditions:
    // 1. Killed (Loads only)
    // 2. Load Finished (Arbiter fired)
    // 3. Store Finished (Response received, just drain)
    val s3Fire = s3Killed || 
                 (s3IsDone && isLoadS3 && wbArbiter.io.in(1).ready) || 
                 (s3IsDone && isStoreS3)

    s3Ready := !s3Valid || s3Fire

    when (s3Ready) {
        // Logic for incoming S2
        val incomingValid = s2Fire // If S2 fired, it wasn't killed or was immune
        
        s3Valid       := incomingValid
        s3Bits        := s2Bits
        s3AddrDebug   := effAddr
        
        // Bug #2: Always wait for response initially when entering S3
        s3WaitingResp := incomingValid 
    }.elsewhen(s3Killed) {
        s3Valid       := false.B
        s3WaitingResp := false.B
    }

    // =================================================================================
    // Debug
    // =================================================================================
    when(Elaboration.printOnMemAccess.B) {
        when(io.mem.req.fire && !io.mem.req.bits.isLoad) {
            printf(p"STORE_REQ: Addr=0x${Hexadecimal(io.mem.req.bits.addr)} Data=0x${Hexadecimal(io.mem.req.bits.data)}\n")
        }
        when(wbArbiter.io.in(1).fire) {
             printf(p"LOAD_WB: Addr=0x${Hexadecimal(s3AddrDebug)} Data=0x${Hexadecimal(wbArbiter.io.in(1).bits.data)}\n")
        }
        when(s3IsDone && isStoreS3 && s3Ready) {
             printf(p"STORE_ACK: Addr=0x${Hexadecimal(s3AddrDebug)}\n")
        }
    }
}