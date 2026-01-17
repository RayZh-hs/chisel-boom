package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.BranchUnit
import components.structures.{BRUInfo, IssueBufferEntry}
import utility.CycleAwareModule
import chisel3.util.experimental.BoringUtils

/** BRU Adaptor
  *
  * Bridges an Issue Buffer to the Branch Unit execution unit.
  */
class BRUAdaptor extends CycleAwareModule {
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new BRUInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        val prfRead = new PRFReadBundle
        val brUpdate = Output(new BranchUpdateBundle)
        val flush = Input(new FlushBundle)
        val busy =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
    })

    val bru = Module(new BranchUnit)

    // Pipeline Registers
    val s1Valid = RegInit(false.B)
    val s1Bits = Reg(new IssueBufferEntry(new BRUInfo))

    val s2Valid = RegInit(false.B)
    val s2Bits = Reg(new IssueBufferEntry(new BRUInfo))
    val s2Data1 = Reg(UInt(32.W))
    val s2Data2 = Reg(UInt(32.W))

    val s3Valid = RegInit(false.B)
    val s3Bits = Reg(new IssueBufferEntry(new BRUInfo))
    val s3Result = Reg(UInt(32.W))
    val s3Taken = Reg(Bool())
    val s3Target = Reg(UInt(32.W))
    val s3UpdSent = RegInit(
      false.B
    ) // Record the update already sent even if CDB not accepted

    io.busy.foreach(_ := s1Valid || s2Valid || s3Valid)

    val s3Ready = io.broadcastOut.ready || !s3Valid
    val s2Ready = s3Ready || !s2Valid
    val s1Ready = s2Ready || !s1Valid

    io.issueIn.ready := s1Ready

    // Stage 1 Transition
    when(s1Ready) {
        s1Valid := io.issueIn.fire && !io.flush.checkKilled(
          io.issueIn.bits.robTag
        )
        s1Bits := io.issueIn.bits
    }.elsewhen(io.flush.checkKilled(s1Bits.robTag)) {
        s1Valid := false.B
    }

    // Stage 2 Transition
    when(s2Ready) {
        s2Valid := s1Valid && !io.flush.checkKilled(s1Bits.robTag)
        s2Bits := s1Bits
        s2Data1 := io.prfRead.data1
        s2Data2 := io.prfRead.data2
    }.elsewhen(io.flush.checkKilled(s2Bits.robTag)) {
        s2Valid := false.B
    }

    // Stage 3 Transition
    when(s3Ready) {
        s3Valid := s2Valid && !io.flush.checkKilled(s2Bits.robTag)
        s3Bits := s2Bits
        s3Result := bru.io.result
        s3Taken := bru.io.taken
        s3Target := bru.io.target
        s3UpdSent := false.B // Reset sent flag for the new instruction
    }.otherwise {
        // Handle flushes and update tracking during stall
        when(io.flush.checkKilled(s3Bits.robTag)) { s3Valid := false.B }
        when(io.brUpdate.valid) { s3UpdSent := true.B }
    }

    // Data Path Connections
    io.prfRead.addr1 := s1Bits.src1
    io.prfRead.addr2 := s1Bits.src2

    bru.io.inA := s2Data1
    bru.io.inB := s2Data2
    bru.io.pc := s2Bits.info.pc
    bru.io.imm := s2Bits.imm
    bru.io.bruOp := s2Bits.info.bruOp
    bru.io.cmpOp := s2Bits.info.cmpOp

    // Outputs
    val isWritebackInstS3 = s3Bits.info.bruOp.isOneOf(
      BRUOpType.JAL,
      BRUOpType.JALR,
      BRUOpType.AUIPC
    )

    io.broadcastOut.valid := s3Valid && !io.flush.checkKilled(s3Bits.robTag)
    io.broadcastOut.bits.pdst := s3Bits.pdst
    io.broadcastOut.bits.robTag := s3Bits.robTag
    io.broadcastOut.bits.data := s3Result
    io.broadcastOut.bits.writeEn := isWritebackInstS3

    io.brUpdate.valid := s3Valid && !s3UpdSent
    io.brUpdate.taken := s3Taken
    io.brUpdate.target := s3Target
    io.brUpdate.pc := s3Bits.info.pc
    io.brUpdate.robTag := s3Bits.robTag
    io.brUpdate.predict := s3Bits.info.predict
    io.brUpdate.predictedTarget := s3Bits.info.predictedTarget
    io.brUpdate.rasSP := s3Bits.info.rasSP

    val s3Mispredict = RegEnable(
      (bru.io.taken =/= s2Bits.info.predict) ||
          (bru.io.taken && bru.io.target =/= s2Bits.info.predictedTarget),
      s3Ready
    )
    val mispredict = s3Mispredict
    io.brUpdate.mispredict := mispredict
    when(io.brUpdate.valid) {
        printf(
          p"BRU Update, PC: 0x${Hexadecimal(io.brUpdate.pc)}, Taken: ${io.brUpdate.taken}, Target: 0x${Hexadecimal(io.brUpdate.target)}, Mispredict: ${io.brUpdate.mispredict}\n"
        )
    }

    if (Configurables.Profiling.branchMispredictionRate) {
        val totalBranchCounter = RegInit(0.U(32.W))
        val mispredictCounter = RegInit(0.U(32.W))

        when(io.brUpdate.valid) {
            totalBranchCounter := totalBranchCounter + 1.U
            when(io.brUpdate.mispredict) {
                mispredictCounter := mispredictCounter + 1.U
            }
        }

        BoringUtils.addSource(totalBranchCounter, "total_branches")
        BoringUtils.addSource(mispredictCounter, "branch_mispredictions")
    }
}
