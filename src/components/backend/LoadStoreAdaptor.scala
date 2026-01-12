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
    })

    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 8, "LSQ"))

    // Connect LSQ
    lsq.io.in <> io.issueIn
    lsq.io.broadcast := io.broadcastIn
    lsq.io.flush := io.flush

    // --- Pipeline Registers ---
    // S1: LSQ Head -> PRF Read
    val s1_valid = RegInit(false.B)
    val s1_bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    // S2: PRF Data -> AGU -> Memory Request
    val s2_valid = RegInit(false.B)
    val s2_bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s2_data1 = Reg(UInt(32.W)) // Base
    val s2_data2 = Reg(UInt(32.W)) // Store Data

    // Tracking for Store-Ready notification
    val s2_st_ready_sent = RegInit(false.B)

    // S3: Memory Response -> Broadcast
    val s3_valid = RegInit(false.B)
    val s3_bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    // --- Pipeline Control (Flexible Flow) ---
    // S3 moves if broadcast is accepted
    val s3_ready = io.broadcastOut.ready || !s3_valid

    // S2 moves if S3 is ready AND memory/commit conditions are met
    // Note: Store must wait for robHead to commit to memory
    val isStoreS2 = s2_bits.info.isStore
    val isLoadS2 = !s2_bits.info.isStore
    val canCommitStoreS2 = isStoreS2 && (io.robHead === s2_bits.robTag)

    // S2 is ready to advance if:
    // 1. It's a Load (it will fire memory req and move to S3)
    // 2. It's a Store and it's time to commit
    val s2_fire_req = (isLoadS2 || canCommitStoreS2) && s3_ready
    val s2_ready = s2_fire_req || !s2_valid

    val s1_ready = s2_ready || !s1_valid

    lsq.io.out.ready := s1_ready

    // --- Stage 1 Transition ---
    when(s1_ready) {
        s1_valid := lsq.io.out.fire && !io.flush.checkKilled(
          lsq.io.out.bits.robTag
        )
        s1_bits := lsq.io.out.bits
    }.elsewhen(io.flush.checkKilled(s1_bits.robTag)) {
        s1_valid := false.B
    }

    // --- Stage 2 Transition ---
    io.prfRead.addr1 := s1_bits.src1
    io.prfRead.addr2 := s1_bits.src2

    when(s2_ready) {
        s2_valid := s1_valid && !io.flush.checkKilled(s1_bits.robTag)
        s2_bits := s1_bits
        s2_data1 := io.prfRead.data1
        s2_data2 := io.prfRead.data2
        s2_st_ready_sent := false.B
    }.otherwise {
        // Track if we sent the "Store Ready" notification while stalled waiting for robHead
        when(io.broadcastOut.fire && isStoreS2) { s2_st_ready_sent := true.B }
        when(io.flush.checkKilled(s2_bits.robTag)) { s2_valid := false.B }
    }

    // --- AGU (Address Generation Unit) in Stage 2 ---
    val effAddr = (s2_data1.asSInt + s2_bits.info.imm.asSInt).asUInt

    // Pipeline address to S3 for debug printing
    val s3_addr = Reg(UInt(32.W))

    // Memory Request (Stage 2 -> Stage 3)
    io.mem.req.valid := s2_valid && (isLoadS2 || canCommitStoreS2) && s3_ready
    io.mem.req.bits.isLoad := isLoadS2
    io.mem.req.bits.opWidth := s2_bits.info.opWidth
    io.mem.req.bits.isUnsigned := s2_bits.info.isUnsigned
    io.mem.req.bits.addr := effAddr
    io.mem.req.bits.data := s2_data2
    io.mem.req.bits.targetReg := s2_bits.pdst

    // --- Stage 3 Transition ---
    when(s3_ready) {
        s3_valid := s2_valid && s2_fire_req && !io.flush.checkKilled(
          s2_bits.robTag
        )
        s3_bits := s2_bits
        s3_addr := effAddr
    }.elsewhen(io.flush.checkKilled(s3_bits.robTag)) {
        s3_valid := false.B
    }

    // --- Broadcast Arbitration (Output Logic) ---
    io.broadcastOut.valid := false.B
    io.broadcastOut.bits := DontCare

    // Priority 1: Load Results (Stage 3)
    // Priority 2: Store "Ready to Commit" Notification (Stage 2)

    val s3_killed = io.flush.checkKilled(s3_bits.robTag)
    val isLoadS3 = !s3_bits.info.isStore

    // Debug Print for Memory Accesses
    when(Elaboration.printOnMemAccess.B) {
        // Store Print (at Issue to Memory)
        when(io.mem.req.valid && !io.mem.req.bits.isLoad) {
            printf(
              p"STORE: Addr=0x${Hexadecimal(io.mem.req.bits.addr)} Data=0x${Hexadecimal(io.mem.req.bits.data)}\n"
            )
        }
        // Load Print (at Result Broadcast)
        when(s3_valid && !s3_killed && isLoadS3) {
            printf(
              p"LOAD: Addr=0x${Hexadecimal(s3_addr)} Data=0x${Hexadecimal(io.mem.resp.bits)}\n"
            )
        }
    }

    when(s3_valid && !s3_killed) {
        // Broadcast Load Result or Store-Writeback-Done
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := s3_bits.pdst
        io.broadcastOut.bits.robTag := s3_bits.robTag
        io.broadcastOut.bits.data := io.mem.resp.bits // From MemorySubsystem
        io.broadcastOut.bits.writeEn := isLoadS3
    }.elsewhen(
      s2_valid && isStoreS2 && !s2_st_ready_sent && !io.flush.checkKilled(
        s2_bits.robTag
      )
    ) {
        // Store notification: "I have my operands, I'm ready for the ROB head to find me"
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := s2_bits.pdst
        io.broadcastOut.bits.robTag := s2_bits.robTag
        io.broadcastOut.bits.writeEn := false.B
    }
}
