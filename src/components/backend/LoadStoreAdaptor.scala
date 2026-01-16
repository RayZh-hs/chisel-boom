package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures._
import utility.CycleAwareModule

class LoadStoreAdaptor extends CycleAwareModule {
    val io = IO(new Bundle {
        val issueIn =
            Flipped(Decoupled(new SequentialBufferEntry(new LoadStoreInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        val broadcastIn = Input(Valid(new BroadcastBundle))
        val prfRead = new PRFReadBundle
        val flush = Input(new FlushBundle)
        val robHead = Input(UInt(ROB_WIDTH.W))

        val mem = new MemoryRequest
        val busy = if (common.Configurables.Profiling.Utilization) Some(Output(Bool())) else None
    })

    // --- LSQ Instance ---
    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 8, "LSQ"))
    lsq.io.in <> io.issueIn
    lsq.io.broadcast := io.broadcastIn
    lsq.io.flush := io.flush

    // --- Pipeline Registers ---

    // Stage 1: Decode / Read PRF
    val s1Valid = RegInit(false.B)
    val s1Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    // Stage 2: Address Calc / Store Commit Check / Mem Request
    val s2Valid = RegInit(false.B)
    val s2Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s2Data1 = Reg(UInt(32.W))
    val s2Data2 = Reg(UInt(32.W))

    // S2 Control State Registers (Latched State)
    val s2RegBroadcastDone = RegInit(false.B)
    val s2RegCommitted = RegInit(false.B)

    // Stage 3: Wait for Response / Writeback
    val s3Valid = RegInit(false.B)
    val s3Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s3Data = Reg(UInt(32.W))
    val s3AddrDebug = Reg(UInt(32.W))
    val s3WaitingResp = RegInit(false.B)

    // --- Internal Wires ---
    val s1Ready = Wire(Bool())
    val s2Ready = Wire(Bool())
    val s3Ready = Wire(Bool())

    io.busy.foreach(_ := s1Valid || s2Valid || s3Valid)

    // --- Broadcast Arbiter ---
    val wbArbiter = Module(new RRArbiter(new BroadcastBundle, 2))
    io.broadcastOut <> wbArbiter.io.out

    // =================================================================================
    // Stage 1: Issue & PRF Read
    // =================================================================================

    lsq.io.out.ready := s1Ready
    val s1Killed = io.flush.checkKilled(lsq.io.out.bits.robTag)

    when(s1Ready) {
        s1Valid := lsq.io.out.valid && !s1Killed
        s1Bits := lsq.io.out.bits
    }.elsewhen(io.flush.checkKilled(s1Bits.robTag)) {
        s1Valid := false.B
    }

    io.prfRead.addr1 := s1Bits.src1
    io.prfRead.addr2 := s1Bits.src2

    val s1Fire = s1Valid && s2Ready
    s1Ready := !s1Valid || s2Ready

    // =================================================================================
    // Stage 2: Execute, Address Calc, Store Logic, Mem Request
    // =================================================================================

    val isStoreS2 = s2Bits.info.isStore
    val isLoadS2 = !isStoreS2
    val effAddr = (s2Data1.asSInt + s2Bits.info.imm.asSInt).asUInt

    // -------------------------------------------------------------------------
    // 1. Commit Logic (Store Specific)
    // -------------------------------------------------------------------------
    // Current Event: Is it matching right now?
    val s2IsHeadMatch = s2Valid && isStoreS2 && (io.robHead === s2Bits.robTag)

    // Effective State: It is committed if we remembered it, or if it's happening now.
    val s2CommitComplete = s2RegCommitted || s2IsHeadMatch

    // Latch Update
    when(s2IsHeadMatch) {
        s2RegCommitted := true.B
    }

    // -------------------------------------------------------------------------
    // 2. Flush Logic
    // -------------------------------------------------------------------------
    // Loads: Speculative, always killable.
    // Stores: Speculative until committed. Once committed, they are immune.
    val s2Killed =
        io.flush.checkKilled(s2Bits.robTag) && !s2CommitComplete

    // -------------------------------------------------------------------------
    // 3. Broadcast Logic (Store Specific)
    // -------------------------------------------------------------------------
    // Current Condition: We need to broadcast if it's a valid store, not done, and not killed.
    val s2NeedBroadcast =
        s2Valid && isStoreS2 && !s2RegBroadcastDone && !s2Killed

    // Wire up Arbiter
    wbArbiter.io.in(0).valid := s2NeedBroadcast
    wbArbiter.io.in(0).bits.pdst := s2Bits.pdst
    wbArbiter.io.in(0).bits.robTag := s2Bits.robTag
    wbArbiter.io.in(0).bits.data := 0.U
    wbArbiter.io.in(0).bits.writeEn := false.B

    // Current Event: Did the Arbiter accept our request this cycle?
    val s2IsBroadcastFiring = wbArbiter.io.in(0).fire

    // Effective State: Broadcast is "Complete" if done previously OR doing it right now.
    val s2BroadcastComplete = s2RegBroadcastDone || s2IsBroadcastFiring

    // Latch Update
    when(s2IsBroadcastFiring) {
        s2RegBroadcastDone := true.B
    }

    // -------------------------------------------------------------------------
    // 4. Memory Request Logic (Point of No Return)
    // -------------------------------------------------------------------------

    // Condition to attempt Memory Request:
    // 1. Loads:  Valid & Not Killed.
    // 2. Stores: Valid & Not Killed & Committed & Broadcast Complete.
    val s2CanIssueStore = s2CommitComplete && s2BroadcastComplete
    val s2MemReqValid = s2Valid && !s2Killed && (isLoadS2 || s2CanIssueStore)

    io.mem.req.valid := s2MemReqValid && s3Ready
    io.mem.req.bits.addr := effAddr
    io.mem.req.bits.data := s2Data2
    io.mem.req.bits.isLoad := isLoadS2
    io.mem.req.bits.opWidth := s2Bits.info.opWidth
    io.mem.req.bits.isUnsigned := s2Bits.info.isUnsigned
    io.mem.req.bits.targetReg := s2Bits.pdst

    // -------------------------------------------------------------------------
    // 5. Transition Logic
    // -------------------------------------------------------------------------

    val s2Fire = io.mem.req.fire

    s2Ready := !s2Valid || s2Fire || s2Killed

    when(s2Ready) {
        val validNext = s1Fire && !io.flush.checkKilled(s1Bits.robTag)

        s2Valid := validNext
        s2Bits := s1Bits
        s2Data1 := io.prfRead.data1
        s2Data2 := io.prfRead.data2

        // Reset Latched State for new instruction
        s2RegCommitted := false.B
        s2RegBroadcastDone := false.B
    }.elsewhen(s2Killed) {
        s2Valid := false.B
    }

    // =================================================================================
    // Stage 3: Variable Latency Response & Writeback
    // =================================================================================

    val isStoreS3 = s3Bits.info.isStore
    val isLoadS3 = !isStoreS3

    // S3 Flush: Stores here are already committed (passed S2 check), only loads are killable.
    val s3Killed = io.flush.checkKilled(s3Bits.robTag) && isLoadS3

    // Handle Response
    // We expect a response for every request sent (Data for Load, Ack for Store).
    when(io.mem.resp.valid) {
        s3Data := io.mem.resp.bits
        s3WaitingResp := false.B
    }
    io.mem.resp.ready := true.B

    // Completion Logic
    val s3IsDone = s3Valid && !s3WaitingResp && !s3Killed

    // Writeback Arbiter (Priority 1: Loads)
    wbArbiter.io.in(1).valid := s3IsDone && isLoadS3
    wbArbiter.io.in(1).bits.pdst := s3Bits.pdst
    wbArbiter.io.in(1).bits.robTag := s3Bits.robTag
    wbArbiter.io.in(1).bits.data := s3Data
    wbArbiter.io.in(1).bits.writeEn := true.B

    // S3 Fire conditions:
    // 1. Killed (Loads only)
    // 2. Load Finished (Arbiter fired)
    // 3. Store Finished (Response received, just drain)
    val s3Fire = s3Killed ||
        (s3IsDone && isLoadS3 && wbArbiter.io.in(1).ready) ||
        (s3IsDone && isStoreS3)

    s3Ready := !s3Valid || s3Fire

    when(s3Ready) {
        // Logic for incoming S2
        val incomingValid = s2Fire

        s3Valid := incomingValid
        s3Bits := s2Bits
        s3AddrDebug := effAddr
        s3WaitingResp := incomingValid
    }.elsewhen(s3Killed) {
        s3Valid := false.B
        s3WaitingResp := false.B
    }

    // =================================================================================
    // Debug
    // =================================================================================
    when(Elaboration.printOnMemAccess.B) {
        when(io.mem.req.fire && !io.mem.req.bits.isLoad) {
            printf(
              p"STORE_REQ: Addr=0x${Hexadecimal(io.mem.req.bits.addr)} Data=0x${Hexadecimal(io.mem.req.bits.data)}\n"
            )
        }
        when(wbArbiter.io.in(1).fire) {
            printf(
              p"LOAD_WB: Addr=0x${Hexadecimal(s3AddrDebug)} Data=0x${Hexadecimal(wbArbiter.io.in(1).bits.data)}\n"
            )
        }
        when(s3IsDone && isStoreS3 && s3Ready) {
            printf(p"STORE_ACK: Addr=0x${Hexadecimal(s3AddrDebug)}\n")
        }
    }
}
