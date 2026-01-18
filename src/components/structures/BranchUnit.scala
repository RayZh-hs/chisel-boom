package components.structures

import chisel3._
import chisel3.util._
import common._
import utility.CycleAwareModule

/** Branch Unit
  *
  * Detects branch mispredictions and corrects branch targets.
  */
class BranchUnit extends CycleAwareModule {
    // IO Definition
    val io = IO(new Bundle {
        val inA = Input(UInt(32.W)) // rs1
        val inB = Input(UInt(32.W)) // rs2
        val pc = Input(UInt(32.W))
        val imm = Input(UInt(32.W))
        val bruOp = Input(BRUOpType())
        val cmpOp = Input(CmpOpType())

        val taken = Output(Bool())
        val target = Output(UInt(32.W))
        val result = Output(UInt(32.W)) // for JAL/JALR/AUIPC
    })

    val taken = WireInit(false.B)

    // Shared adder for target calculation
    val targetBase = Mux(io.bruOp === BRUOpType.JALR, io.inA, io.pc)
    val targetRaw = targetBase + io.imm
    val target =
        Mux(io.bruOp === BRUOpType.JALR, targetRaw & ~1.U(32.W), targetRaw)

    // Shared adder for result calculation
    val npc = io.pc + 4.U
    val result = Mux(io.bruOp === BRUOpType.AUIPC, targetRaw, npc)

    switch(io.bruOp) {
        is(BRUOpType.CBR) {
            val cmpRes = WireInit(false.B)
            switch(io.cmpOp) {
                is(CmpOpType.EQ) { cmpRes := io.inA === io.inB }
                is(CmpOpType.NEQ) { cmpRes := io.inA =/= io.inB }
                is(CmpOpType.LT) { cmpRes := io.inA.asSInt < io.inB.asSInt }
                is(CmpOpType.LTU) { cmpRes := io.inA < io.inB }
                is(CmpOpType.GE) { cmpRes := io.inA.asSInt >= io.inB.asSInt }
                is(CmpOpType.GEU) { cmpRes := io.inA >= io.inB }
            }
            taken := cmpRes
        }
        is(BRUOpType.JAL) {
            taken := true.B
        }
        is(BRUOpType.JALR) {
            taken := true.B
        }
        is(BRUOpType.AUIPC) {
            taken := false.B
        }
    }

    io.taken := taken
    io.target := target
    io.result := result
}
