package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._

object RegBusyStatus extends ChiselEnum {
    val free, busy, ready = Value
}

class PhysicalRegisterFile extends Module {
    // IO definition
    val io = IO(new Bundle {
        // Define IO ports here
    })

    // register arrays that manages physical registers
    val regFile = RegInit(
      VecInit(Seq.fill(Derived.PREG_COUNT)(0.U(32.W)))
    ) // stores actual data
    val mapTable = RegInit(
      VecInit(Seq.fill(32)(0.U(PREG_WIDTH.W)))
    ) // logical to physical reg map
    val freeList = Module(
      new FreeList(Derived.PREG_COUNT, 32)
    ) // lists free physical regs
    val busyList = RegInit(
      VecInit(Seq.fill(Derived.PREG_COUNT)(RegBusyStatus.free))
    ) // mark physical reg busy status
}
