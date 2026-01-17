package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.{MulDivUnit, MultInfo, IssueBufferEntry}

/** Mul-Div Adaptor
  *
  * Bridges an Issue Buffer to the Mult/Div execution unit.
  */
class MulDivAdaptor extends Module {
    // IO Definition
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new MultInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)

        // PRF interface
        val prfRead = new PRFReadBundle

        val flush = Input(new FlushBundle)
        val busy =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
    })

    val mult = Module(new MulDivUnit)
    val fetch = Module(new OperandFetchStage(new MultInfo))

    val s2_valid = RegInit(false.B)
    val s2_pdst = Reg(UInt(PREG_WIDTH.W))
    val s2_rob = Reg(UInt(ROB_WIDTH.W))
    val s2_killed = RegInit(false.B)

    val s3_valid = RegInit(false.B)
    val s3_pdst = Reg(UInt(PREG_WIDTH.W))
    val s3_rob = Reg(UInt(ROB_WIDTH.W))
    val s3_result = Reg(UInt(32.W))

    // Stage 1: Issue Logic & Operand Fetch
    fetch.io.issueIn <> io.issueIn
    io.prfRead       <> fetch.io.prfRead
    fetch.io.flush   := io.flush

    val s1Valid = fetch.io.out.valid
    val s1Info  = fetch.io.out.bits.info
    val s1Op1   = fetch.io.out.bits.op1
    val s1Op2   = fetch.io.out.bits.op2

    // Stage 2: Mul / Div Execution
    mult.io.req.valid   := s1Valid
    mult.io.req.bits.fn := s1Info.info.multOp.asUInt
    mult.io.req.bits.a  := s1Op1
    mult.io.req.bits.b  := s1Op2

    val dispatchFire = s1Valid && mult.io.req.ready && !s2_valid
    fetch.io.out.ready := mult.io.req.ready && !s2_valid

    when(dispatchFire) {
        s2_valid  := true.B 
        s2_pdst   := s1Info.pdst
        s2_rob    := s1Info.robTag
        s2_killed := false.B
    }

    when(s2_valid && io.flush.checkKilled(s2_rob)) {
        s2_killed := true.B
    }

    mult.io.resp.ready := s2_valid && (s2_killed || !s3_valid)

    when(mult.io.resp.fire) {
        s2_valid := false.B // Clear S2

        when(s2_killed) {
            // Logic: Killed while in Mult.
        }.otherwise {
            // Logic: Normal completion.
            // Action: Move to S3 (Broadcast Buffer)
            s3_valid := true.B
            s3_result := mult.io.resp.bits
            s3_pdst := s2_pdst
            s3_rob := s2_rob
        }
    }

    when(mult.io.resp.fire && !s2_killed && io.flush.checkKilled(s2_rob)) {
        s3_valid := false.B
    }

    io.broadcastOut.valid := s3_valid
    io.broadcastOut.bits.pdst := s3_pdst
    io.broadcastOut.bits.robTag := s3_rob
    io.broadcastOut.bits.data := s3_result
    io.broadcastOut.bits.writeEn := true.B

    when(io.broadcastOut.fire) {
        s3_valid := false.B
    }
    when(s3_valid && io.flush.checkKilled(s3_rob)) {
        s3_valid := false.B
    }

    // Profiling Data
    io.busy.foreach(_ := fetch.io.busy || s2_valid || s3_valid)
}
