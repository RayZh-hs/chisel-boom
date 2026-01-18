package components.backend

import chisel3._
import chisel3.util._
import common.BroadcastBundle
import common.Configurables
import utility.CycleAwareModule

/** Broadcast Channel
  *
  * Arbitrates multiple execution unit broadcast results onto a single broadcast
  * channel. Carries that information to all components that need it.
  */
class BroadcastChannel extends CycleAwareModule {
    // IO definition
    val io = IO(new Bundle {
        val aluResult = Flipped(Decoupled(new BroadcastBundle))
        val multResult = Flipped(Decoupled(new BroadcastBundle))
        val bruResult = Flipped(Decoupled(new BroadcastBundle))
        val memResult = Flipped(Decoupled(new BroadcastBundle))

        val broadcastOut = Valid(new BroadcastBundle)
    })

    val arbiter = Module(new RRArbiter(new BroadcastBundle, 4))
    arbiter.io.in <> Seq(
      io.aluResult,
      io.multResult,
      io.bruResult,
      io.memResult
    )
    io.broadcastOut.valid := arbiter.io.out.valid
    io.broadcastOut.bits := arbiter.io.out.bits
    arbiter.io.out.ready := true.B

    when(
      Configurables.Elaboration.printOnBroadcast.B && io.broadcastOut.valid && io.broadcastOut.bits.pdst =/= 0.U
    ) {
        printf(
          p"CDB: pdst=${io.broadcastOut.bits.pdst} data=0x${Hexadecimal(io.broadcastOut.bits.data)}\n"
        )
    }
}
