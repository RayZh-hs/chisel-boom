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
    assert(io.addr(1, 0) === 0.U, "Instruction fetch address must be 4-byte aligned")
    val readAddr = io.addr(IMEM_WIDTH + 2, 2) // Word-aligned address matching IMEM_SIZE
    io.inst := imem.read(readAddr)
}
