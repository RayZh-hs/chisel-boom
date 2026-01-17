package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.{RiscVMultiplier, MulFunc, MultInfo, IssueBufferEntry}

class MultAdaptor extends Module {
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new MultInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)

        // PRF interface
        val prfRead = new PRFReadBundle

        val flush = Input(new FlushBundle)
        val busy = if (common.Configurables.Profiling.Utilization) Some(Output(Bool())) else None
    })

    val mult = Module(new RiscVMultiplier)

    val s1_valid = RegInit(false.B)
    val s1_info  = Reg(new IssueBufferEntry(new MultInfo))
    val s1_op1   = Reg(UInt(32.W))
    val s1_op2   = Reg(UInt(32.W))

    val s2_valid  = RegInit(false.B)
    val s2_pdst   = Reg(UInt(PREG_WIDTH.W))
    val s2_rob    = Reg(UInt(ROB_WIDTH.W))
    val s2_killed = RegInit(false.B)

    val s3_valid  = RegInit(false.B)
    val s3_pdst   = Reg(UInt(PREG_WIDTH.W))
    val s3_rob    = Reg(UInt(ROB_WIDTH.W))
    val s3_result = Reg(UInt(32.W))

    // =================================================================================
    // Stage 1: Issue Logic
    // =================================================================================
    
    io.prfRead.addr1 := io.issueIn.bits.src1
    io.prfRead.addr2 := io.issueIn.bits.src2

    io.issueIn.ready := !s1_valid

    when(io.issueIn.fire) {
        s1_valid := true.B
        s1_info  := io.issueIn.bits
        s1_op1   := io.prfRead.data1
        s1_op2   := io.prfRead.data2
    }

    val flushHitS1 = io.flush.checkKilled(s1_info.robTag)
    when(s1_valid && flushHitS1) {
        s1_valid := false.B
    }
    when(io.issueIn.fire && io.flush.checkKilled(io.issueIn.bits.robTag)) {
        s1_valid := false.B
    }

    // =================================================================================
    // Stage 2: Mult Execution
    // =================================================================================

    mult.io.req.valid   := s1_valid
    mult.io.req.bits.fn := s1_info.info.multOp.asUInt
    mult.io.req.bits.a  := s1_op1
    mult.io.req.bits.b  := s1_op2

    val dispatchFire = s1_valid && mult.io.req.ready && !s2_valid

    when(dispatchFire) {
        s1_valid  := false.B
        s2_valid  := true.B 
        s2_pdst   := s1_info.pdst
        s2_rob    := s1_info.robTag
        s2_killed := false.B
    }

    when(s2_valid && io.flush.checkKilled(s2_rob)) {
        s2_killed := true.B
    }

    mult.io.resp.ready := s2_valid && (s2_killed || !s3_valid)

    when(mult.io.resp.fire) {
        s2_valid := false.B // Clear S2

        when (s2_killed) {
            // Logic: Killed while in Mult.
        } .otherwise {
            // Logic: Normal completion.
            // Action: Move to S3 (Broadcast Buffer)
            s3_valid  := true.B
            s3_result := mult.io.resp.bits
            s3_pdst   := s2_pdst
            s3_rob    := s2_rob
        }
    }
    
    when(mult.io.resp.fire && !s2_killed && io.flush.checkKilled(s2_rob)) {
        s3_valid := false.B
    }

    io.broadcastOut.valid        := s3_valid
    io.broadcastOut.bits.pdst    := s3_pdst
    io.broadcastOut.bits.robTag  := s3_rob
    io.broadcastOut.bits.data    := s3_result
    io.broadcastOut.bits.writeEn := true.B

    when(io.broadcastOut.fire) {
        s3_valid := false.B
    }
    when(s3_valid && io.flush.checkKilled(s3_rob)) {
        s3_valid := false.B
    }

    // =================================================================================
    // Profiling
    // =================================================================================
    io.busy.foreach(_ := s1_valid || s2_valid || s3_valid)
}
