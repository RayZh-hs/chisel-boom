package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.BranchUnit
import components.structures.{BRUInfo, IssueBufferEntry}
import utility.CycleAwareModule

class BRUAdaptor extends CycleAwareModule {
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new BRUInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        val prfRead = new PRFReadBundle
        val brUpdate = Output(new BranchUpdateBundle)
        val flush = Input(new FlushBundle)
    })

    val bru = Module(new BranchUnit)

    // Pipeline Registers
    val s1_valid = RegInit(false.B)
    val s1_bits = Reg(new IssueBufferEntry(new BRUInfo))

    val s2_valid = RegInit(false.B)
    val s2_bits = Reg(new IssueBufferEntry(new BRUInfo))
    val s2_data1 = Reg(UInt(32.W))
    val s2_data2 = Reg(UInt(32.W))

    val s3_valid = RegInit(false.B)
    val s3_bits = Reg(new IssueBufferEntry(new BRUInfo))
    val s3_result = Reg(UInt(32.W))
    val s3_taken = Reg(Bool())
    val s3_target = Reg(UInt(32.W))
    val s3_upd_sent = RegInit(
      false.B
    ) // Record the update already sent even if CDB not accepted

    val s3_ready = io.broadcastOut.ready || !s3_valid
    val s2_ready = s3_ready || !s2_valid
    val s1_ready = s2_ready || !s1_valid

    io.issueIn.ready := s1_ready

    // Stage 1 Transition
    when(s1_ready) {
        s1_valid := io.issueIn.fire && !io.flush.checkKilled(
          io.issueIn.bits.robTag
        )
        s1_bits := io.issueIn.bits
    }.elsewhen(io.flush.checkKilled(s1_bits.robTag)) {
        s1_valid := false.B
    }

    // Stage 2 Transition
    when(s2_ready) {
        s2_valid := s1_valid && !io.flush.checkKilled(s1_bits.robTag)
        s2_bits := s1_bits
        s2_data1 := io.prfRead.data1
        s2_data2 := io.prfRead.data2
    }.elsewhen(io.flush.checkKilled(s2_bits.robTag)) {
        s2_valid := false.B
    }

    // Stage 3 Transition
    when(s3_ready) {
        s3_valid := s2_valid && !io.flush.checkKilled(s2_bits.robTag)
        s3_bits := s2_bits
        s3_result := bru.io.result
        s3_taken := bru.io.taken
        s3_target := bru.io.target
        s3_upd_sent := false.B // Reset sent flag for the new instruction
    }.otherwise {
        // Handle flushes and update tracking during stall
        when(io.flush.checkKilled(s3_bits.robTag)) { s3_valid := false.B }
        when(io.brUpdate.valid) { s3_upd_sent := true.B }
    }

    // --- Data Path Connections ---
    io.prfRead.addr1 := s1_bits.src1
    io.prfRead.addr2 := s1_bits.src2

    bru.io.inA := s2_data1
    bru.io.inB := s2_data2
    bru.io.pc := s2_bits.info.pc
    bru.io.imm := s2_bits.imm
    bru.io.bruOp := s2_bits.info.bruOp
    bru.io.cmpOp := s2_bits.info.cmpOp

    // --- Outputs ---
    val isWritebackInst_s3 = s3_bits.info.bruOp.isOneOf(
      BRUOpType.JAL,
      BRUOpType.JALR,
      BRUOpType.AUIPC
    )

    io.broadcastOut.valid := s3_valid && !io.flush.checkKilled(s3_bits.robTag)
    io.broadcastOut.bits.pdst := s3_bits.pdst
    io.broadcastOut.bits.robTag := s3_bits.robTag
    io.broadcastOut.bits.data := s3_result
    io.broadcastOut.bits.writeEn := isWritebackInst_s3

    io.brUpdate.valid := s3_valid && !s3_upd_sent
    io.brUpdate.taken := s3_taken
    io.brUpdate.target := s3_target
    io.brUpdate.pc := s3_bits.info.pc
    io.brUpdate.robTag := s3_bits.robTag
    io.brUpdate.predict := s3_bits.info.predict
    io.brUpdate.predictedTarget := s3_bits.info.predictedTarget

    val s3_mispredict = RegEnable(
      (bru.io.taken =/= s2_bits.info.predict) ||
          (bru.io.taken && bru.io.target =/= s2_bits.info.predictedTarget),
      s3_ready
    )
    val mispredict = s3_mispredict
    io.brUpdate.mispredict := mispredict

    printf(
      p"BRU: PC=0x${Hexadecimal(s3_bits.info.pc)}, mispredicted=${s3_bits.info.predict}\n"
    )
    printf(
      p"BRU: taken=${bru.io.taken}, target=0x${Hexadecimal(bru.io.target)}, result=0x${Hexadecimal(bru.io.result)}\n"
    )
}
