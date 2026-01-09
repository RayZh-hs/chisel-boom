package common

import chisel3._
import chisel3.util._
import Configurables._
import Configurables.Derived._

/** Instruction Fetch output bundle definition.
  */
class FetchToDecodeBundle extends Bundle {
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val predict = Bool()
    val predictedTarget = UInt(32.W)
}

/** Micro-operation bundle definition.
  *
  * Full uop structure, used in Decode -> Dispatch stage. Broken up when pushed
  * to IQ and ROB.
  *
  * @note
  *   Memory access uops assume: `paddr` is `prs1`, `psrc` is `prs2`, `pdst` is
  *   `pdst`.
  */
class DecodeToDispatchBundle extends BrFlagBundle {
    // - categorizing operation
    val fUnitType = FunUnitType() // functional unit type
    val aluOpType = ALUOpType()
    val bruOpType = BRUOpType()
    val cmpOpType = CmpOpType()
    val isLoad = Bool()
    val isStore = Bool()
    // - id to pc (needed if it is a branching instruction or AUIPC)
    val pc = UInt(32.W)
    val predict = Bool()
    val predictedTarget = UInt(32.W)
    // - register renaming info
    val lrs1, lrs2, ldst = UInt(5.W) // logical registers
    val prs1, prs2, pdst = UInt(PREG_WIDTH.W) // physical registers
    val stalePdst = UInt(PREG_WIDTH.W) // stale physical destination
    val useImm = Bool()
    val imm = UInt(32.W)
    // - memory access info
    val opWidth = MemOpWidth()
    // paddr is assumed to be prs1
    // psrc is assumed to be prs2
    // pdst is assumed to be pdst
}

class DispatchToROBBundle extends BrFlagBundle {
    val ldst = UInt(5.W)
    val pdst = UInt(PREG_WIDTH.W)
    val stalePdst = UInt(PREG_WIDTH.W)
    val isStore = Bool()
}

class DispatchToALQBundle extends BrFlagBundle {
    val aluOpType = ALUOpType()
    val prs1, prs2 = UInt(PREG_WIDTH.W)
    val (useImm, imm) = (Bool(), UInt(32.W))
    val pdst = UInt(PREG_WIDTH.W)
}

class DispatchToBRQBundle extends BrFlagBundle {
    val bruOpType = BRUOpType()
    val cmpOpType = CmpOpType()
    val prs1, prs2 = UInt(PREG_WIDTH.W)
    val (useImm, imm) = (Bool(), UInt(32.W))
    val pc = UInt(32.W)
}

class DispatchToLSQBundle extends BrFlagBundle {
    val isLoad = Bool() // differentiate load/store
    val opWidth = MemOpWidth()
    val paddr = UInt(PREG_WIDTH.W)
    val (useImm, imm) = (Bool(), UInt(32.W))
    val psrc = UInt(PREG_WIDTH.W)
    val pdst = UInt(PREG_WIDTH.W)
}

class BroadcastBundle extends Bundle {
    val pdst = UInt(PREG_WIDTH.W)
    val robTag = UInt(ROB_WIDTH.W)
}

class RollbackBundle extends Bundle {
    val ldst = UInt(5.W)
    val pdst = UInt(PREG_WIDTH.W)
    val stalePdst = UInt(PREG_WIDTH.W)
}