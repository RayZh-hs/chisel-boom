package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.BranchUnit
import components.structures.{BRUInfo, IssueBufferEntry}
import utility.CycleAwareModule
import chisel3.util.experimental.BoringUtils
import utility.ExitAwarenessProfiling

/** BRU Adaptor
  *
  * Bridges an Issue Buffer to the Branch Unit execution unit.
  */
class BRUAdaptor extends CycleAwareModule with ExitAwarenessProfiling {
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
    val fetch = Module(new OperandFetchStage(new BRUInfo))

    // Pipeline Registers
    val s3Valid = RegInit(false.B)
    val s3Bits = Reg(new IssueBufferEntry(new BRUInfo))
    val s3Result = Reg(UInt(32.W))
    val s3Taken = Reg(Bool())
    val s3Target = Reg(UInt(32.W))
    val s3UpdSent = RegInit(
      false.B
    ) // Record the update already sent even if CDB not accepted

    io.busy.foreach(_ := fetch.io.busy || s3Valid)

    val s3Ready = io.broadcastOut.ready || !s3Valid

    // Connect Fetch Stage
    fetch.io.issueIn <> io.issueIn
    io.prfRead <> fetch.io.prfRead
    fetch.io.flush := io.flush
    fetch.io.out.ready := s3Ready

    val s2Info = fetch.io.out.bits.info
    val s2op1 = fetch.io.out.bits.op1
    val s2op2 = fetch.io.out.bits.op2

    // Stage 3 Transition
    when(s3Ready) {
        s3Valid := fetch.io.out.valid && !io.flush.checkKilled(s2Info.robTag)
        s3Bits := s2Info
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
    bru.io.inA := s2op1
    bru.io.inB := s2op2
    bru.io.pc := s2Info.info.pc
    bru.io.imm := s2Info.imm
    bru.io.bruOp := s2Info.info.bruOp
    bru.io.cmpOp := s2Info.info.cmpOp

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
      (bru.io.taken =/= s2Info.info.predict) ||
          (bru.io.taken && bru.io.target =/= s2Info.info.predictedTarget),
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

        when(io.brUpdate.valid && !exited) {
            totalBranchCounter := totalBranchCounter + 1.U
            when(io.brUpdate.mispredict) {
                mispredictCounter := mispredictCounter + 1.U
            }
        }

        BoringUtils.addSource(totalBranchCounter, "total_branches")
        BoringUtils.addSource(mispredictCounter, "branch_mispredictions")
    }
}
