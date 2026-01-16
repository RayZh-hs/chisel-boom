package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures._
import utility.CycleAwareModule

// State maintained for a load in the writeback pipeline stage
class LoadStoreWBState extends Bundle {
    val valid = Bool()
    val pdst = UInt(PREG_WIDTH.W)
    val robTag = UInt(ROB_WIDTH.W)
    val resultPending = Bool()
    val pendingData = UInt(32.W)
}
class LoadStoreAdaptor extends CycleAwareModule {
    val io = IO(new Bundle {
        val issueIn =
            Flipped(Decoupled(new SequentialBufferEntry(new LoadStoreInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        val broadcastIn = Input(Valid(new BroadcastBundle))
        val prfRead = new PRFReadBundle
        val flush = Input(new FlushBundle)
        val robHead = Input(UInt(ROB_WIDTH.W))

        // Memory Interface
        val mem = Flipped(new MemoryInterface)
        val busy = if (common.Configurables.Profiling.Utilization) Some(Output(Bool())) else None
        val stallCommit = if (common.Configurables.Profiling.Utilization) Some(Output(Bool())) else None
        val lsqCount = if (common.Configurables.Profiling.Utilization) Some(Output(UInt(log2Ceil(9).W))) else None // 8 entries
    })

    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 8, "LSQ"))
    io.lsqCount.foreach(_ := lsq.io.count.get)

    // Connect LSQ
    lsq.io.in <> io.issueIn
    lsq.io.broadcast := io.broadcastIn
    lsq.io.flush := io.flush

    // --- Pipeline Registers ---
    // S1: LSQ Head -> PRF Read
    val s1Valid = RegInit(false.B)
    val s1Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    // S2: PRF Data -> AGU -> Memory Request
    val s2Valid = RegInit(false.B)
    val s2Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s2Data1 = Reg(UInt(32.W)) // Base
    val s2Data2 = Reg(UInt(32.W)) // Store Data

    // Tracking for Store-Ready notification
    val s2StReadySent = RegInit(false.B)

    // S3: Memory Response -> Broadcast
    val s3Valid = RegInit(false.B)
    val s3Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    io.busy.foreach(_ := s1Valid || s2Valid || s3Valid)

    // --- Pipeline Control (Flexible Flow) ---
    // S3 moves if broadcast is accepted
    val s3Ready = io.broadcastOut.ready || !s3Valid

    // S2 moves if S3 is ready AND memory/commit conditions are met
    // Note: Store must wait for robHead to commit to memory
    val isStoreS2 = s2Bits.info.isStore
    val isLoadS2 = !s2Bits.info.isStore
    val canCommitStoreS2 = isStoreS2 && (io.robHead === s2Bits.robTag)
    
    io.stallCommit.foreach(_ := s2Valid && isStoreS2 && !canCommitStoreS2)

    // S2 is ready to advance if:
    // 1. It's a Load (it will fire memory req and move to S3)
    // 2. It's a Store and it's time to commit
    val s2FireReq = (isLoadS2 || canCommitStoreS2) && s3Ready
    val s2Ready = s2FireReq || !s2Valid

    val s1Ready = s2Ready || !s1Valid

    lsq.io.out.ready := s1Ready

    // --- Stage 1 Transition ---
    when(s1Ready) {
        s1Valid := lsq.io.out.fire && !io.flush.checkKilled(
          lsq.io.out.bits.robTag
        )
        s1Bits := lsq.io.out.bits
    }.elsewhen(io.flush.checkKilled(s1Bits.robTag)) {
        s1Valid := false.B
    }

    // --- Stage 2 Transition ---
    io.prfRead.addr1 := s1Bits.src1
    io.prfRead.addr2 := s1Bits.src2

    when(s2Ready) {
        s2Valid := s1Valid && !io.flush.checkKilled(s1Bits.robTag)
        s2Bits := s1Bits
        s2Data1 := io.prfRead.data1
        s2Data2 := io.prfRead.data2
        s2StReadySent := false.B
    }.otherwise {
        // Track if we sent the "Store Ready" notification while stalled waiting for robHead
        when(io.broadcastOut.fire && isStoreS2) { s2StReadySent := true.B }
        when(io.flush.checkKilled(s2Bits.robTag)) { s2Valid := false.B }
    }

    // --- AGU (Address Generation Unit) in Stage 2 ---
    val effAddr = (s2Data1.asSInt + s2Bits.info.imm.asSInt).asUInt

    // Pipeline address to S3 for debug printing
    val s3Addr = Reg(UInt(32.W))

    // Memory Request (Stage 2 -> Stage 3)
    io.mem.req.valid := s2Valid && (isLoadS2 || canCommitStoreS2) && s3Ready
    io.mem.req.bits.isLoad := isLoadS2
    io.mem.req.bits.opWidth := s2Bits.info.opWidth
    io.mem.req.bits.isUnsigned := s2Bits.info.isUnsigned
    io.mem.req.bits.addr := effAddr
    io.mem.req.bits.data := s2Data2
    io.mem.req.bits.targetReg := s2Bits.pdst

    // --- Stage 3 Transition ---
    when(s3Ready) {
        s3Valid := s2Valid && s2FireReq && !io.flush.checkKilled(
          s2Bits.robTag
        )
        s3Bits := s2Bits
        s3Addr := effAddr
    }.elsewhen(io.flush.checkKilled(s3Bits.robTag)) {
        s3Valid := false.B
    }

    // --- S3 Data Capture Logic ---
    // Since latency is 1 cycle, data arrives 1 cycle after a request fires.
    val memRespArriving = RegNext(s2FireReq && isLoadS2, init = false.B)

    // Register to hold the data in case of a stall
    val s3DataLatched = Reg(UInt(32.W))
    when(memRespArriving) {
        s3DataLatched := io.mem.resp.bits
    }

    // --- Broadcast Arbitration (Output Logic) ---
    io.broadcastOut.valid := false.B
    io.broadcastOut.bits := DontCare

    // Priority 1: Load Results (Stage 3)
    // Priority 2: Store "Ready to Commit" Notification (Stage 2)

    val s3Killed = io.flush.checkKilled(s3Bits.robTag)
    val isLoadS3 = !s3Bits.info.isStore

    // Debug Print for Memory Accesses
    when(Elaboration.printOnMemAccess.B) {
        // Store Print (at Issue to Memory)
        when(io.mem.req.valid && !io.mem.req.bits.isLoad) {
            printf(
              p"STORE: Addr=0x${Hexadecimal(io.mem.req.bits.addr)} Data=0x${Hexadecimal(io.mem.req.bits.data)}\n"
            )
        }
        // Load Print (at Result Broadcast)
        when(s3Valid && !s3Killed && isLoadS3) {
            printf(
              p"LOAD: Addr=0x${Hexadecimal(s3Addr)} Data=0x${Hexadecimal(io.broadcastOut.bits.data)}\n"
            )
        }
    }

    when(s3Valid && !s3Killed) {
        // Broadcast Load Result or Store-Writeback-Done
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := s3Bits.pdst
        io.broadcastOut.bits.robTag := s3Bits.robTag
        io.broadcastOut.bits.data := Mux(
          memRespArriving,
          io.mem.resp.bits,
          s3DataLatched
        )
        io.broadcastOut.bits.writeEn := isLoadS3
    }.elsewhen(
      s2Valid && isStoreS2 && !s2StReadySent && !io.flush.checkKilled(
        s2Bits.robTag
      )
    ) {
        // Store notification: "I have my operands, I'm ready for the ROB head to find me"
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := s2Bits.pdst
        io.broadcastOut.bits.robTag := s2Bits.robTag
        io.broadcastOut.bits.writeEn := false.B
    }
}
