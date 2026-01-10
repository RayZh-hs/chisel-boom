package common

import chisel3._
import chisel3.util._
import Configurables._
import Configurables.Derived._

/** Functional unit types.
  *
  * Includes: `ALU`, `BRU`, `MEM`.
  */
object FunUnitType extends ChiselEnum {
    val ALU, BRU, MEM = Value
}

/** ALU operation types.
  *
  * Includes: `ADD`, `SUB`, `AND`, `OR`, `XOR`, `SLL`, `SRL`, `SRA`, `SLT`,
  * `SLTU`, `LUI`.
  */
object ALUOpType extends ChiselEnum {
    val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU, LUI = Value
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
