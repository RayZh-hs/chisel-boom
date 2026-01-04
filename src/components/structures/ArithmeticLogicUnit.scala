package components.structures

import chisel3._
import chisel3.util._
import common._


class ArithmeticLogicUnit extends Module {
    val io = IO(new Bundle {
        val inA = Input(UInt(32.W))
        val inB = Input(UInt(32.W))
        val aluOp = Input(ALUOpType())
        val result = Output(UInt(32.W))
    })

    val res = WireInit(0.U(32.W))

    switch(io.aluOp) {
        is(ALUOpType.ADD) { res := io.inA + io.inB }
        is(ALUOpType.SUB) { res := io.inA - io.inB }
        is(ALUOpType.AND) { res := io.inA & io.inB }
        is(ALUOpType.OR)  { res := io.inA | io.inB }
        is(ALUOpType.XOR) { res := io.inA ^ io.inB }
        is(ALUOpType.SLL) { res := io.inA << io.inB(4, 0) }
        is(ALUOpType.SRL) { res := io.inA >> io.inB(4, 0) }
        is(ALUOpType.SRA) { res := (io.inA.asSInt >> io.inB(4, 0)).asUInt }
        is(ALUOpType.SLT) { res := (io.inA.asSInt < io.inB.asSInt).asUInt }
        is(ALUOpType.SLTU) { res := (io.inA < io.inB).asUInt }
        is(ALUOpType.LUI) { res := io.inB }
    }

    io.result := res
}