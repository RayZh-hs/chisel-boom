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
    })

    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 8, "LSQ"))

    lsq.io.in <> io.issueIn
    lsq.io.broadcast := io.broadcastIn
    lsq.io.flush := io.flush

    // Always ready to accept memory responses (we latch them immediately)
    io.mem.resp.ready := true.B

    // --- Pipeline Registers ---
    val s1Valid = RegInit(false.B)
    val s1Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))

    val s2Valid = RegInit(false.B)
    val s2Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s2Data1 = Reg(UInt(32.W))
    val s2Data2 = Reg(UInt(32.W))
    val s2StReadySent = RegInit(false.B)

    val s3MetadataValid = RegInit(false.B)
    val s3Bits = Reg(new SequentialBufferEntry(new LoadStoreInfo))
    val s3DataLatched = Reg(UInt(32.W))
    val s3DataLatchedValid = RegInit(false.B)
    val s3Addr = Reg(UInt(32.W)) // For debug

    // --- S3 Control Logic ---

    // 1. Determine if S3 has valid data (Response arrived or it's a store)
    val isStoreS3 = s3Bits.info.isStore
    val s3DataValid = io.mem.resp.valid || s3DataLatchedValid || isStoreS3

    // 2. Determine if S3 is "Done" (Valid AND Data Ready AND Consumer Accepted)
    val s3FireOrCleared = s3MetadataValid && s3DataValid && io.broadcastOut.ready

    // 3. S3 is Ready for new input if it's empty OR if it just finished firing
    val s3Ready = !s3MetadataValid || s3FireOrCleared

    // --- S2 Control Logic ---

    val isStoreS2 = s2Bits.info.isStore
    val isLoadS2 = !s2Bits.info.isStore
    val canCommitStoreS2 = isStoreS2 && (io.robHead === s2Bits.robTag)

    // S2 attempts to fire request if it has a Load or a Committable Store
    val s2ReqRequired = s2Valid && (isLoadS2 || canCommitStoreS2)

    // The actual fire condition: Needs Request, Downstream Ready (S3), and Memory Ready
    val s2FireReq = s2ReqRequired && s3Ready && io.mem.req.ready

    // S2 is ready to accept S1 if:
    // - It isn't valid
    // - OR It fired its request successfully
    // - OR It's a Store waiting for commit (Store buffer logic allows S1 to stall, but here we keep S2 occupied)
    val s2Ready = !s2Valid || s2FireReq

    val s1Ready = s2Ready || !s1Valid
    lsq.io.out.ready := s1Ready

    // --- Stage 1 Update ---
    when(s1Ready) {
        s1Valid := lsq.io.out.fire && !io.flush.checkKilled(
          lsq.io.out.bits.robTag
        )
        s1Bits := lsq.io.out.bits
    }.elsewhen(io.flush.checkKilled(s1Bits.robTag)) {
        s1Valid := false.B
    }

    // --- Stage 2 Update ---
    io.prfRead.addr1 := s1Bits.src1
    io.prfRead.addr2 := s1Bits.src2

    when(s2Ready) {
        s2Valid := s1Valid && !io.flush.checkKilled(s1Bits.robTag)
        s2Bits := s1Bits
        s2Data1 := io.prfRead.data1
        s2Data2 := io.prfRead.data2
        s2StReadySent := false.B
    }.otherwise {
        // Handle stalled Store notification
        when(io.broadcastOut.fire && isStoreS2) { s2StReadySent := true.B }
        when(io.flush.checkKilled(s2Bits.robTag)) { s2Valid := false.B }
    }

    // --- Memory Request Generation ---
    val effAddr = (s2Data1.asSInt + s2Bits.info.imm.asSInt).asUInt

    io.mem.req.valid := s2ReqRequired && s3Ready // Note: fire check happens in io.mem.req.ready
    io.mem.req.bits.isLoad := isLoadS2
    io.mem.req.bits.opWidth := s2Bits.info.opWidth
    io.mem.req.bits.isUnsigned := s2Bits.info.isUnsigned
    io.mem.req.bits.addr := effAddr
    io.mem.req.bits.data := s2Data2
    io.mem.req.bits.targetReg := s2Bits.pdst

    // --- Stage 3 Update ---
    // If S2 fires, S3 takes the data.
    // If S2 doesn't fire, but S3 fired (drained), S3 becomes invalid.
    when(s2FireReq) {
        s3MetadataValid := true.B && !io.flush.checkKilled(s2Bits.robTag)
        s3Bits := s2Bits
        s3Addr := effAddr
        s3DataLatchedValid := false.B // Reset latch for new op
    }.elsewhen(s3FireOrCleared || io.flush.checkKilled(s3Bits.robTag)) {
        s3MetadataValid := false.B
        s3DataLatchedValid := false.B
    }

    // --- S3 Data Latch ---
    when(io.mem.resp.valid) {
        s3DataLatched := io.mem.resp.bits
        s3DataLatchedValid := true.B
    }

    val s3DataResult = Mux(io.mem.resp.valid, io.mem.resp.bits, s3DataLatched)

    // --- Broadcast Output ---
    io.broadcastOut.valid := false.B
    io.broadcastOut.bits := DontCare

    val s3Killed = io.flush.checkKilled(s3Bits.robTag)
    val isLoadS3 = !s3Bits.info.isStore

    when(s3MetadataValid && s3DataValid && !s3Killed) {
        // Priority 1: S3 finishes (Load result or Store done)
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := s3Bits.pdst
        io.broadcastOut.bits.robTag := s3Bits.robTag
        io.broadcastOut.bits.data := s3DataResult
        io.broadcastOut.bits.writeEn := isLoadS3
    }.elsewhen(
      s2Valid && isStoreS2 && !s2StReadySent && !io.flush.checkKilled(
        s2Bits.robTag
      )
    ) {
        // Priority 2: Store in S2 notifying ROB it is ready for commit
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := s2Bits.pdst
        io.broadcastOut.bits.robTag := s2Bits.robTag
        io.broadcastOut.bits.writeEn := false.B
    }

    // Debug Printing
    when(Elaboration.printOnMemAccess.B) {
        when(io.mem.req.fire && !io.mem.req.bits.isLoad) {
            printf(
              p"STORE: Addr=0x${Hexadecimal(io.mem.req.bits.addr)} Data=0x${Hexadecimal(io.mem.req.bits.data)}\n"
            )
        }
        when(io.broadcastOut.fire && s3MetadataValid && isLoadS3) {
            printf(
              p"LOAD: Addr=0x${Hexadecimal(s3Addr)} Data=0x${Hexadecimal(io.broadcastOut.bits.data)}\n"
            )
        }
    }
}
