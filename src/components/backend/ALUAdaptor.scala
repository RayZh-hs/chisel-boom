package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.ArithmeticLogicUnit

class ALUAdaptor extends Module {
    val io = IO(new Bundle {
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(new ALUInfo)))
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
    })

    val alu = Module(new ArithmeticLogicUnit)

    // Connect Issue Buffer to ALU
    io.issueIn.ready := io.broadcastOut.ready
    
    io.prfRead.addr1 := io.issueIn.bits.src1
    io.prfRead.addr2 := io.issueIn.bits.src2

    alu.io.inA := io.prfRead.data1
    alu.io.inB := Mux(io.issueIn.bits.useImm, io.issueIn.bits.imm, io.prfRead.data2)
    alu.io.aluOp := io.issueIn.bits.info.aluOp

    // Connect ALU to PRF Write and Broadcast
    io.prfWrite.addr := io.issueIn.bits.pdst
    io.prfWrite.data := alu.io.result
    io.prfWrite.en := io.issueIn.valid && io.issueIn.ready

    io.broadcastOut.valid := io.issueIn.valid
    io.broadcastOut.bits.pdst := io.issueIn.bits.pdst
    io.broadcastOut.bits.robTag := io.issueIn.bits.robTag
}
