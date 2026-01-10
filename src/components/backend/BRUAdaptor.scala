package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.BranchUnit
import components.structures.{BRUInfo, IssueBufferEntry}

class BRUAdaptor extends Module {
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new BRUInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)

        // PRF interface - Read only now, writeback via broadcast
        val prfRead = new PRFReadBundle

        // Branch update (to frontend/ROB)
        val brUpdate = Output(new BranchUpdateBundle)

        val flush = Input(new FlushBundle)
    })

    val bru = Module(new BranchUnit)

    // Pipeline Registers
    // Stage 1: Issue -> PRF Read
    val s1_valid = RegInit(false.B)
    val s1_bits = Reg(new IssueBufferEntry(new BRUInfo))

    // Stage 2: PRF Read -> BRU In
    val s2_valid = RegInit(false.B)
    val s2_bits = Reg(new IssueBufferEntry(new BRUInfo))
    val s2_data1 = Reg(UInt(32.W))
    val s2_data2 = Reg(UInt(32.W))

    // Stage 3: BRU Out -> Broadcast/Update
    val s3_valid = RegInit(false.B)
    val s3_bits = Reg(new IssueBufferEntry(new BRUInfo))
    val s3_result = Reg(UInt(32.W))
    val s3_taken = Reg(Bool())
    val s3_target = Reg(UInt(32.W))

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
        s2_data2 := io.prfRead.data2

        s3_valid := s2_valid && !io.flush.checkKilled(s2_bits.robTag)
        s3_bits  := s2_bits
        s3_result := bru.io.result
        s3_taken := bru.io.taken
        s3_target := bru.io.target
    }.otherwise {
        // When stalled, tokens can still be killed
        when(io.flush.checkKilled(s1_bits.robTag)) { s1_valid := false.B }
        when(io.flush.checkKilled(s2_bits.robTag)) { s2_valid := false.B }
        when(io.flush.checkKilled(s3_bits.robTag)) { s3_valid := false.B }
    }

    // Connect Stage 1 to PRF
    io.prfRead.addr1 := s1_bits.src1
    io.prfRead.addr2 := s1_bits.src2

    // Connect Stage 2 to BRU
    bru.io.inA := s2_data1
    bru.io.inB := s2_data2
    bru.io.pc := s2_bits.info.pc
    bru.io.imm := s2_bits.imm
    bru.io.bruOp := s2_bits.info.bruOp
    bru.io.cmpOp := s2_bits.info.cmpOp

    // Stage 3 logic
    val isWritebackInst_s3 = s3_bits.info.bruOp.isOneOf(BRUOpType.JAL, BRUOpType.JALR, BRUOpType.AUIPC)

    // Pulse brUpdate only once per instruction
    val s3_upd_sent = RegInit(false.B)
    when(can_flow) {
        s3_upd_sent := false.B
    } .elsewhen(io.brUpdate.valid) {
        s3_upd_sent := true.B
    }

    // Outputs from s3
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

    val mispredict = (io.brUpdate.taken =/= io.brUpdate.predict) || 
                     (io.brUpdate.taken && io.brUpdate.target =/= io.brUpdate.predictedTarget)
    io.brUpdate.mispredict := mispredict
}
