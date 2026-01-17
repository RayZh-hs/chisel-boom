package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.ArithmeticLogicUnit
import components.structures.{ALUInfo, IssueBufferEntry}

/** ALU Adaptor
  *
  * Bridges the Issue Buffer to the ALU execution unit.
  */
class ALUAdaptor extends Module {
    val io = IO(new Bundle {
        val issueIn      = Flipped(Decoupled(new IssueBufferEntry(new ALUInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        val prfRead      = new PRFReadBundle
        val flush        = Input(new FlushBundle)
        val busy         = if (Configurables.Profiling.Utilization) Some(Output(Bool())) else None
    })

    val alu = Module(new ArithmeticLogicUnit)
    val fetch = Module(new OperandFetchStage(new ALUInfo))

    // Connect Fetch Stage
    fetch.io.issueIn <> io.issueIn
    io.prfRead       <> fetch.io.prfRead
    fetch.io.flush   := io.flush

    // --- Stage 3: Writeback/Broadcast Registers ---
    val s3Valid  = RegInit(false.B)
    val s3Bits   = Reg(new IssueBufferEntry(new ALUInfo))
    val s3Result = Reg(UInt(32.W))

    // --- Pipeline Control ---
    // The Fetch stage output (S2) acts as the input to the ALU.
    // The ALU is combinational, results are latched into S3.
    val s3Ready = io.broadcastOut.ready || !s3Valid
    
    // We signal the fetch stage to proceed if S3 can accept data
    fetch.io.out.ready := s3Ready

    // --- ALU Execution Logic (Technically Stage 2) ---
    // The fetch stage provides latched operands.
    // If the instruction uses an immediate, we mux it here.
    val s2Info = fetch.io.out.bits.info
    val op1    = fetch.io.out.bits.op1
    val op2    = Mux(s2Info.useImm, s2Info.imm, fetch.io.out.bits.op2)

    alu.io.inA    := op1
    alu.io.inB    := op2
    alu.io.aluOp  := s2Info.info.aluOp

    // --- Stage 3 Transition ---
    when(s3Ready) {
        val flushS2 = io.flush.checkKilled(s2Info.robTag)
        
        // Valid if fetch provides data and it's not flushed
        s3Valid := fetch.io.out.valid && !flushS2
        s3Bits  := s2Info
        s3Result := alu.io.result
    }.elsewhen(io.flush.checkKilled(s3Bits.robTag)) {
        s3Valid := false.B
    }

    // --- Broadcast Output ---
    io.broadcastOut.valid        := s3Valid && !io.flush.checkKilled(s3Bits.robTag)
    io.broadcastOut.bits.pdst    := s3Bits.pdst
    io.broadcastOut.bits.robTag  := s3Bits.robTag
    io.broadcastOut.bits.data    := s3Result
    io.broadcastOut.bits.writeEn := true.B

    // --- Busy Signal ---
    io.busy.foreach(_ := fetch.io.busy || s3Valid)
}
