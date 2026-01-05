package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.BranchUnit

class BRUAdaptor extends Module {
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new BRUInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        
        // PRF interface
        val prfRead = new Bundle {
            val addr1 = Output(UInt(PREG_WIDTH.W))
            val data1 = Input(UInt(32.W))
            val addr2 = Output(UInt(PREG_WIDTH.W))
            val data2 = Input(UInt(32.W))
        }
        val prfWrite = new Bundle {
            val addr = Output(UInt(PREG_WIDTH.W))
            val data = Output(UInt(32.W))
            val en = Output(Bool())
        }

        // Branch update (to frontend/ROB)
        val brUpdate = Output(new Bundle {
            val valid = Bool()
            val taken = Bool()
            val target = UInt(32.W)
            val pc = UInt(32.W)
            val robTag = UInt(ROB_WIDTH.W)
            val predict = Bool()
            val predictedTarget = UInt(32.W)
        })
    })

    val bru = Module(new BranchUnit)

    // Connect Issue Buffer to BRU
    io.issueIn.ready := io.broadcastOut.ready
    
    io.prfRead.addr1 := io.issueIn.bits.src1
    io.prfRead.addr2 := io.issueIn.bits.src2

    bru.io.inA := io.prfRead.data1
    bru.io.inB := io.prfRead.data2
    bru.io.pc := io.issueIn.bits.info.pc
    bru.io.imm := io.issueIn.bits.imm
    bru.io.bruOp := io.issueIn.bits.info.bruOp
    bru.io.cmpOp := io.issueIn.bits.info.cmpOp

    // Connect BRU to PRF Write and Broadcast
    io.prfWrite.addr := io.issueIn.bits.pdst
    io.prfWrite.data := bru.io.result
    io.prfWrite.en := io.issueIn.valid && io.issueIn.ready && (io.issueIn.bits.info.bruOp === BRUOpType.JAL || io.issueIn.bits.info.bruOp === BRUOpType.JALR || io.issueIn.bits.info.bruOp === BRUOpType.AUIPC)

    io.broadcastOut.valid := io.issueIn.valid
    io.broadcastOut.bits.pdst := io.issueIn.bits.pdst
    io.broadcastOut.bits.robTag := io.issueIn.bits.robTag

    // Branch update
    io.brUpdate.valid := io.issueIn.valid && io.issueIn.ready
    io.brUpdate.taken := bru.io.taken
    io.brUpdate.target := bru.io.target
    io.brUpdate.pc := io.issueIn.bits.info.pc
    io.brUpdate.robTag := io.issueIn.bits.robTag
    io.brUpdate.predict := io.issueIn.bits.info.predict
    io.brUpdate.predictedTarget := io.issueIn.bits.info.predictedTarget
}
