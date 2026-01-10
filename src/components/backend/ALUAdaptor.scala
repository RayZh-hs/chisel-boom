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
    })

    val alu = Module(new ArithmeticLogicUnit)

    // Pipeline Registers
    // Stage 1: Issue -> PRF Read
    val s1_valid = RegInit(false.B)
    val s1_bits = Reg(new IssueBufferEntry(new ALUInfo))

    // Stage 2: PRF Read -> ALU In
    val s2_valid = RegInit(false.B)
    val s2_bits = Reg(new IssueBufferEntry(new ALUInfo))
    val s2_data1 = Reg(UInt(32.W))
    val s2_data2 = Reg(UInt(32.W))

    // Stage 3: ALU Out -> Broadcast
    val s3_valid = RegInit(false.B)
    val s3_bits = Reg(new IssueBufferEntry(new ALUInfo))
    val s3_result = Reg(UInt(32.W))

    // Pipeline Control
    val can_flow = io.broadcastOut.ready
    io.issueIn.ready := can_flow

    // Transition Logic
    when(can_flow) {
        s1_valid := io.issueIn.valid && !io.flush.checkKilled(io.issueIn.bits.robTag)
        s1_bits := io.issueIn.bits

        s2_valid := s1_valid && !io.flush.checkKilled(s1_bits.robTag)
        s2_bits  := s1_bits
        s2_data1 := io.prfRead.data1
        s2_data2 := Mux(s1_bits.useImm, s1_bits.imm, io.prfRead.data2)

        s3_valid := s2_valid && !io.flush.checkKilled(s2_bits.robTag)
        s3_bits  := s2_bits
        s3_result := alu.io.result
    }.otherwise {
        // When stalled, tokens can still be killed
        when(io.flush.checkKilled(s1_bits.robTag)) { s1_valid := false.B }
        when(io.flush.checkKilled(s2_bits.robTag)) { s2_valid := false.B }
        when(io.flush.checkKilled(s3_bits.robTag)) { s3_valid := false.B }
    }

    // Connect Stage 1 to PRF
    io.prfRead.addr1 := s1_bits.src1
    io.prfRead.addr2 := s1_bits.src2

    // Connect Stage 2 to ALU
    alu.io.inA := s2_data1
    alu.io.inB := s2_data2
    alu.io.aluOp := s2_bits.info.aluOp

    // Stage 3 logic (Broadcast)
    io.broadcastOut.valid := s3_valid && !io.flush.checkKilled(s3_bits.robTag)
    io.broadcastOut.bits.pdst := s3_bits.pdst
    io.broadcastOut.bits.robTag := s3_bits.robTag
    io.broadcastOut.bits.data := s3_result
    io.broadcastOut.bits.writeEn := true.B
}
