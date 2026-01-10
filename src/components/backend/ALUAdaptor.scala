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

    // Flush logic
    val killed = io.flush.checkKilled(io.issueIn.bits.robTag)

    // Connect Issue Buffer to ALU
    io.issueIn.ready := io.broadcastOut.ready

    io.prfRead.addr1 := io.issueIn.bits.src1
    io.prfRead.addr2 := io.issueIn.bits.src2

    alu.io.inA := io.prfRead.data1
    alu.io.inB := Mux(
      io.issueIn.bits.useImm,
      io.issueIn.bits.imm,
      io.prfRead.data2
    )
    alu.io.aluOp := io.issueIn.bits.info.aluOp

    // Broadcast logic (includes PRF write data)
    io.broadcastOut.valid := io.issueIn.valid && !killed
    io.broadcastOut.bits.pdst := io.issueIn.bits.pdst
    io.broadcastOut.bits.robTag := io.issueIn.bits.robTag
    io.broadcastOut.bits.data := alu.io.result
    io.broadcastOut.bits.writeEn := true.B
}
