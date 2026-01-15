package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.ArithmeticLogicUnit
import components.structures.{ALUInfo, IssueBufferEntry}

class ALUAdaptor extends Module {
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new ALUInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)

        // PRF interface - Read only now, writeback via broadcast
        val prfRead = new PRFReadBundle

        val flush = Input(new FlushBundle)
        val busy = if (common.Configurables.Profiling.Utilization) Some(Output(Bool())) else None
    })

    val alu = Module(new ArithmeticLogicUnit)

    // Pipeline Registers
    // Stage 1: Issue -> PRF Read
    val s1Valid = RegInit(false.B)
    val s1Bits = Reg(new IssueBufferEntry(new ALUInfo))

    // Stage 2: PRF Read -> ALU In
    val s2Valid = RegInit(false.B)
    val s2Bits = Reg(new IssueBufferEntry(new ALUInfo))
    val s2Data1 = Reg(UInt(32.W))
    val s2Data2 = Reg(UInt(32.W))

    // Stage 3: ALU Out -> Broadcast
    val s3Valid = RegInit(false.B)
    val s3Bits = Reg(new IssueBufferEntry(new ALUInfo))
    val s3Result = Reg(UInt(32.W))

    io.busy.foreach(_ := s1Valid || s2Valid || s3Valid)

    // Pipeline Control: A stage moves if the next stage can accept it
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
        s2Data2 := Mux(s1Bits.useImm, s1Bits.imm, io.prfRead.data2)
    }.elsewhen(io.flush.checkKilled(s2Bits.robTag)) {
        s2Valid := false.B
    }

    // Stage 3 Transition
    when(s3Ready) {
        s3Valid := s2Valid && !io.flush.checkKilled(s2Bits.robTag)
        s3Bits := s2Bits
        s3Result := alu.io.result
    }.elsewhen(io.flush.checkKilled(s3Bits.robTag)) {
        s3Valid := false.B
    }

    // Connect Stage 1 to PRF
    io.prfRead.addr1 := s1Bits.src1
    io.prfRead.addr2 := s1Bits.src2

    // Connect Stage 2 to ALU
    alu.io.inA := s2Data1
    alu.io.inB := s2Data2
    alu.io.aluOp := s2Bits.info.aluOp

    // Stage 3 logic (Broadcast)
    io.broadcastOut.valid := s3Valid && !io.flush.checkKilled(s3Bits.robTag)
    io.broadcastOut.bits.pdst := s3Bits.pdst
    io.broadcastOut.bits.robTag := s3Bits.robTag
    io.broadcastOut.bits.data := s3Result
    io.broadcastOut.bits.writeEn := true.B
}
