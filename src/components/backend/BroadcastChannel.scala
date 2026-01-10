package components.backend

import chisel3._
import chisel3.util._
import common.BroadcastBundle

class BroadcastChannel extends Module {
    // IO definition
    val io = IO(new Bundle {
        val aluResult = Flipped(Decoupled(new BroadcastBundle))
        val bruResult = Flipped(Decoupled(new BroadcastBundle))
        val memResult = Flipped(Decoupled(new BroadcastBundle))

        val broadcastOut = Valid(new BroadcastBundle)
    })

    val arbiter = Module(new RRArbiter(new BroadcastBundle, 3))
    arbiter.io.in <> Seq(io.aluResult, io.bruResult, io.memResult)
    io.broadcastOut.valid := arbiter.io.out.valid
    io.broadcastOut.bits := arbiter.io.out.bits
    arbiter.io.out.ready := true.B
}
