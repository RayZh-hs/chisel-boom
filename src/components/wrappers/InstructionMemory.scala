package components.wrappers

import chisel3._
import chisel3.util._
import common.Configurables._

class InstructionMemory extends Module {
    val io = IO(new Bundle {
        val addr = Input(UInt(32.W))
        val inst = Output(UInt(32.W))
    })

    
}
