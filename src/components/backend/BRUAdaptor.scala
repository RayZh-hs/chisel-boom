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

    // Flush logic
    val killed = io.flush.checkKilled(io.issueIn.bits.robTag)

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

    // Broadcast logic (includes PRF write data)
    val isWritebackInst = io.issueIn.bits.info.bruOp.isOneOf(BRUOpType.JAL, BRUOpType.JALR, BRUOpType.AUIPC)
    
    io.broadcastOut.valid := io.issueIn.valid && !killed
    io.broadcastOut.bits.pdst := io.issueIn.bits.pdst
    io.broadcastOut.bits.robTag := io.issueIn.bits.robTag
    io.broadcastOut.bits.data := bru.io.result
    io.broadcastOut.bits.writeEn := isWritebackInst

    // Branch update
    io.brUpdate.valid := io.issueIn.valid && io.issueIn.ready && !killed
    io.brUpdate.taken := bru.io.taken
    io.brUpdate.target := bru.io.target
    io.brUpdate.pc := io.issueIn.bits.info.pc
    io.brUpdate.robTag := io.issueIn.bits.robTag
    io.brUpdate.predict := io.issueIn.bits.info.predict
    io.brUpdate.predictedTarget := io.issueIn.bits.info.predictedTarget

    val mispredict = (io.brUpdate.taken =/= io.brUpdate.predict) || 
                     (io.brUpdate.taken && io.brUpdate.target =/= io.brUpdate.predictedTarget)
    io.brUpdate.mispredict := mispredict
}
