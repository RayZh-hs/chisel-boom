package components.structures

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import common.Configurables._

class InstructionMemory(val hexFilePath: String) extends Module {
    val io = IO(new Bundle {
        val addr = Input(UInt(32.W))
        val inst = Output(UInt(32.W))
    })

    val imem = SyncReadMem(Derived.IMEM_SIZE, UInt(32.W))

    // Initialize instruction memory from hex file
    loadMemoryFromFileInline(imem, hexFilePath)
    val readAddr = io.addr(11, 2) // Word-aligned address
    io.inst := imem.read(readAddr)
}
