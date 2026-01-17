package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures._
import utility.CycleAwareModule

/**
  * Load Store Adaptor
  *
  * Bridges an Issue Buffer to the Load/Store execution unit.
  * 
  * Handles all load/store logic sequentially.
  */
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
        val busy =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
        val stallCommit =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
        val lsqCount =
            if (common.Configurables.Profiling.Utilization)
                Some(Output(UInt(log2Ceil(9).W)))
            else None
    })

    // LSQ Instance
    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 16, "LSQ"))
    io.lsqCount.foreach(_ := lsq.io.count.get)
    lsq.io.in <> io.issueIn
    lsq.io.broadcast := io.broadcastIn
    lsq.io.flush := io.flush

    // Pipeline Registers
    // Stage 1: Decode / Read PRF
    val s1Valid = RegInit(false.B)
    val s1Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    // Stage 2: Address Calc / Store Commit Check / Mem Request
    val s2Valid = RegInit(false.B)
    val s2Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s2Data1 = Reg(UInt(32.W))
    val s2Data2 = Reg(UInt(32.W))

    // S2 Control State Registers
    val s2RegBroadcastDone = RegInit(false.B)
    val s2RegCommitted = RegInit(false.B)

    // Stage 3: Wait for Response / Writeback
    val s3Valid = RegInit(false.B)
    val s3Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s3Data = Reg(UInt(32.W))
    val s3AddrDebug = Reg(UInt(32.W))
    val s3WaitingResp = RegInit(false.B)

    /** Fix: Track if S3 is "dead" (flushed) but waiting for Ghost Response
      * @author rogerflowey
      */
    val s3IsDead = RegInit(false.B)
    val s1Ready = Wire(Bool())
    val s2Ready = Wire(Bool())
    val s3Ready = Wire(Bool())

    // Broadcast Arbiter
    val wbArbiter = Module(new RRArbiter(new BroadcastBundle, 2))
    io.broadcastOut <> wbArbiter.io.out

    // Profiling
    io.busy.foreach(_ := s1Valid || s2Valid || s3Valid)
    
    // Replicate commit stall logic for profiling
    val isStoreS2_prof = s2Bits.info.isStore
    val isCommitted_prof = s2RegCommitted || (isStoreS2_prof && io.robHead === s2Bits.robTag)
    io.stallCommit.foreach(_ := s2Valid && isStoreS2_prof && !isCommitted_prof)

    // Stage 1: Issue & PRF Read
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

    // Stage 2: Execute, Address Calc, Store Logic, Mem Request
    val isStoreS2 = s2Bits.info.isStore
    val isLoadS2 = !isStoreS2
    val effAddr = (s2Data1.asSInt + s2Bits.info.imm.asSInt).asUInt

    val s2IsHeadMatch = s2Valid && isStoreS2 && (io.robHead === s2Bits.robTag)
    val s2CommitComplete = s2RegCommitted || s2IsHeadMatch
    io.stallCommit.foreach(_ := s2Valid && isStoreS2 && !s2CommitComplete)

    when(s2IsHeadMatch) { s2RegCommitted := true.B }

    val s2Killed = io.flush.checkKilled(s2Bits.robTag) && !s2CommitComplete
    val s2NeedBroadcast =
        s2Valid && isStoreS2 && !s2RegBroadcastDone && !s2Killed

    wbArbiter.io.in(0).valid := s2NeedBroadcast
    wbArbiter.io.in(0).bits.pdst := s2Bits.pdst
    wbArbiter.io.in(0).bits.robTag := s2Bits.robTag
    wbArbiter.io.in(0).bits.data := 0.U
    wbArbiter.io.in(0).bits.writeEn := false.B

    val s2IsBroadcastFiring = wbArbiter.io.in(0).fire
    val s2BroadcastComplete = s2RegBroadcastDone || s2IsBroadcastFiring

    when(s2IsBroadcastFiring) { s2RegBroadcastDone := true.B }

    val s2CanIssueStore = s2CommitComplete && s2BroadcastComplete
    val s2MemReqValid = s2Valid && !s2Killed && (isLoadS2 || s2CanIssueStore)

    io.mem.req.valid := s2MemReqValid && s3Ready
    io.mem.req.bits.addr := effAddr
    io.mem.req.bits.data := s2Data2
    io.mem.req.bits.isLoad := isLoadS2
    io.mem.req.bits.opWidth := s2Bits.info.opWidth
    io.mem.req.bits.isUnsigned := s2Bits.info.isUnsigned
    io.mem.req.bits.targetReg := s2Bits.pdst

    val s2Fire = io.mem.req.fire
    s2Ready := !s2Valid || s2Fire || s2Killed

    when(s2Ready) {
        val validNext = s1Fire && !io.flush.checkKilled(s1Bits.robTag)
        s2Valid := validNext
        s2Bits := s1Bits
        s2Data1 := io.prfRead.data1
        s2Data2 := io.prfRead.data2
        s2RegCommitted := false.B
        s2RegBroadcastDone := false.B
    }.elsewhen(s2Killed) {
        s2Valid := false.B
    }

    // Stage 3: Variable Latency Response & Writeback

    val isStoreS3 = s3Bits.info.isStore
    val isLoadS3 = !isStoreS3

    // Stage 3.1: Detect Flush (fix bfd662b4dbc5cb5c67767885417a3e171009ee94)
    // If flushed, do not clear s3Valid immediately if we are waiting for a response.
    // Instead, mark as "Dead".
    val s3FlushHit = io.flush.checkKilled(s3Bits.robTag) && isLoadS3
    when(s3FlushHit) {
        s3IsDead := true.B
    }

    // Stage 3.2: Handle Response
    when(io.mem.resp.valid) {
        s3Data := io.mem.resp.bits
        s3WaitingResp := false.B
    }
    io.mem.resp.ready := true.B

    // Stage 3.3: Completion Logic
    val s3IsPending = s3WaitingResp
    val s3MemDone = s3Valid && !s3IsPending

    // Stage 3.4: Writeback Arbiter (Priority 1: Loads)
    // Only request writeback if done, is Load, not marked dead, and not currently being flushed.
    wbArbiter.io
        .in(1)
        .valid := s3MemDone && isLoadS3 && !s3IsDead && !s3FlushHit
    wbArbiter.io.in(1).bits.pdst := s3Bits.pdst
    wbArbiter.io.in(1).bits.robTag := s3Bits.robTag
    wbArbiter.io.in(1).bits.data := s3Data
    wbArbiter.io.in(1).bits.writeEn := true.B

    // Stage 3.5: Fire/Drain Logic
    // We can empty S3 (Fire) if:
    // A. Not waiting for a response (s3IsPending is false).
    // B. It is either:
    //    1. A Load that finished Writeback.
    //    2. A Store that finished (ACK received).
    //    3. A Load that was Killed/Dead (just drop it).

    val s3IsDeadOrFlushed = s3IsDead || s3FlushHit

    val s3Fire = !s3IsPending && (
      (s3MemDone && isLoadS3 && wbArbiter.io.in(1).ready) || // Normal Load
          (s3MemDone && isStoreS3) || // Normal Store
          (s3Valid && isLoadS3 && s3IsDeadOrFlushed) // Killed Load (Drain)
    )

    s3Ready := !s3Valid || s3Fire

    when(s3Ready) {
        val incomingValid = s2Fire

        s3Valid := incomingValid
        s3Bits := s2Bits
        s3AddrDebug := effAddr
        s3WaitingResp := incomingValid

        // Reset Dead status for the new instruction
        s3IsDead := false.B
    }

    // Debug Printing
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
        when(s3MemDone && isStoreS3 && s3Ready) {
            printf(p"STORE_ACK: Addr=0x${Hexadecimal(s3AddrDebug)}\n")
        }
    }
}
