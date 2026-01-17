package common

import chisel3._
import chisel3.util._
import Configurables._
import Configurables.Derived._

/** Functional unit types.
  *
  * Includes: `ALU`, `BRU`, `MEM`, `MULT`.
  */
object FunUnitType extends ChiselEnum {
    val ALU, BRU, MEM, MULT = Value
}

/** ALU operation types.
  *
  * Includes: `ADD`, `SUB`, `AND`, `OR`, `XOR`, `SLL`, `SRL`, `SRA`, `SLT`,
  * `SLTU`, `LUI`.
  */
object ALUOpType extends ChiselEnum {
    val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU, LUI = Value
}

/** Multiplier operation types.
  *
  * Includes: `MUL`, `MULH`, `MULHSU`, `MULHU`
  */
object MultOpType extends ChiselEnum {
    val MUL, MULH, MULHSU, MULHU, DIV, DIVU, REM, REMU = Value

    def fromInt(i: UInt): Type = {
        val w = Wire(MultOpType())
        w := 0.U.asTypeOf(MultOpType()) // Default
        switch(i) {
            is(0.U) { w := MUL }
            is(1.U) { w := MULH }
            is(2.U) { w := MULHSU }
            is(3.U) { w := MULHU }
            is(4.U) { w := DIV }
            is(5.U) { w := DIVU }
            is(6.U) { w := REM }
            is(7.U) { w := REMU }
        }
        w
    }
}

/** Branch unit operation types.
  *
  * Includes: `CBR` (conditional branch), `JAL` (jump and link), `JALR` (jump
  * and link register), `AUIPC` (add upper immediate to PC).
  */
object BRUOpType extends ChiselEnum {
    val CBR, JAL, JALR, AUIPC = Value
}

/** Comparison operation types.
  *
  * Includes: `EQ`, `NEQ`, `LT`, `LTU`, `GE`, `GEU`.
  */
object CmpOpType extends ChiselEnum {
    val EQ, NEQ, LT, LTU, GE, GEU = Value
}

/** Memory operation width types.
  *
  * Includes: `BYTE`, `HALFWORD`, `WORD`, `DWORD`.
  */
object MemOpWidth extends ChiselEnum {
    val BYTE, HALFWORD, WORD, DWORD = Value
}

/** Physical Register Busy Status
  *
  * Includes: `free`, `busy`, `ready`.
  */
object RegBusyStatus extends ChiselEnum {
    val free, busy, ready = Value
}
