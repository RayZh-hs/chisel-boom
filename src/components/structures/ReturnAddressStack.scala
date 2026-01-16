package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._

class ReturnAddressStack extends Module {
    val io = IO(new Bundle {
        val push = Input(Bool())
        val pop = Input(Bool())
        val writeVal = Input(UInt(32.W))
        val recover = Input(Bool())
        val recoverSP = Input(UInt(RAS_WIDTH.W))

        val readVal = Output(UInt(32.W))
        val currentSP = Output(UInt(RAS_WIDTH.W))
    })

    val stack = RegInit(VecInit(Seq.fill(Derived.RAS_SIZE)(0.U(32.W))))
    val sp = RegInit(0.U(RAS_WIDTH.W))

    // Calculate wrapped Next/Prev pointers
    // Subtraction/Addition on UInt results in increased width, so we index [W-1:0] to wrap
    val spNext = (sp + 1.U)(RAS_WIDTH - 1, 0)
    val spPrev = (sp - 1.U)(RAS_WIDTH - 1, 0)

    io.currentSP := sp
    io.readVal := stack(spPrev)

    when(io.recover) {
        sp := io.recoverSP
    }.elsewhen(io.push && io.pop) {
        // Pop then Push: sp unchanged, overwrite top
        stack(spPrev) := io.writeVal
    }.elsewhen(io.push) {
        stack(sp) := io.writeVal
        sp := spNext
    }.elsewhen(io.pop) {
        sp := spPrev
    }
}
