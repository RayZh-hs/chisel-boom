package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._

import common.RegBusyStatus

class PhysicalRegisterFile extends Module {
    // IO definition
    val io = IO(new Bundle {
        // Define IO ports here
    })

    // register arrays that manages physical registers
    val regFile = RegInit(
      VecInit(Seq.fill(Derived.PREG_COUNT)(0.U(32.W)))
    )
}
