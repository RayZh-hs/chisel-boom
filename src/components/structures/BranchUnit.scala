package components.structures

import chisel3._
import chisel3.util._
import common._

class BranchUnit extends Module {
    val io = IO(new Bundle {
        val inA = Input(UInt(32.W))
        val inB = Input(UInt(32.W))
        val pc = Input(UInt(32.W))
        val imm = Input(UInt(32.W))
        val bruOp = Input(BRUOpType())
        val cmpOp = Input(CmpOpType())
        
        val taken = Output(Bool())
        val target = Output(UInt(32.W))
        val result = Output(UInt(32.W)) // for JAL/JALR/AUIPC
    })

    val taken = WireInit(false.B)
    val target = WireInit(0.U(32.W))
    val result = WireInit(0.U(32.W))

    switch(io.bruOp) {
        is(BRUOpType.CBR) {
            val cmpRes = WireInit(false.B)
            switch(io.cmpOp) {
                is(CmpOpType.EQ)  { cmpRes := io.inA === io.inB }
                is(CmpOpType.NEQ) { cmpRes := io.inA =/= io.inB }
                is(CmpOpType.LT)  { cmpRes := io.inA.asSInt < io.inB.asSInt }
                is(CmpOpType.LTU) { cmpRes := io.inA < io.inB }
                is(CmpOpType.GE)  { cmpRes := io.inA.asSInt >= io.inB.asSInt }
                is(CmpOpType.GEU) { cmpRes := io.inA >= io.inB }
            }
            taken := cmpRes
            target := io.pc + io.imm
        }
        is(BRUOpType.JAL) {
            taken := true.B
            target := io.pc + io.imm
            result := io.pc + 4.U
        }
        is(BRUOpType.JALR) {
            taken := true.B
            target := (io.inA + io.imm) & ~1.U(32.W)
            result := io.pc + 4.U
        }
        is(BRUOpType.AUIPC) {
            taken := false.B
            target := io.pc + io.imm
            result := io.pc + io.imm
        }
    }

    io.taken := taken
    io.target := target
    io.result := result
}