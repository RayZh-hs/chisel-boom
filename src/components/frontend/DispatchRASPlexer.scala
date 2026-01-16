package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

/** Dispatch RAS Plexer
  *
  * This module combines signals from RASAdaptor and InstDispatcher. It pipes
  * the same ready signal to both modules coming from Dispatcher.
  *
  * When the downstream stage is ready, it extracts data from both slots and,
  * depending on the PC overwrite status, swaps the PC target.
  *
  * This is a fully combinational module, with no internal state and does not
  * cost one cycle latency to the pipeline.
  */
class DispatchRASPlexer extends Module {
    val io = IO(new Bundle {
        val instFromDecoder = Flipped(Decoupled(new DecodedInstBundle))
        val rasBundleFromAdaptor = Flipped(Decoupled(new RASAdaptorBundle))
        val plexToDispatcher = Decoupled(new DecodedInstWithRAS)
    })

    io.instFromDecoder.ready := io.plexToDispatcher.ready
    io.rasBundleFromAdaptor.ready := io.plexToDispatcher.ready
    val bothValid = io.instFromDecoder.valid && io.rasBundleFromAdaptor.valid
    io.plexToDispatcher.valid := bothValid

    val instBundle = io.instFromDecoder.bits
    val rasBundle = io.rasBundleFromAdaptor.bits

    // Swap PC if RAS indicates a flush (i.e., RAS made a prediction)
    val modifiedPC =
        Mux(rasBundle.flush, rasBundle.flushNextPC, instBundle.predictedTarget)
    io.plexToDispatcher.bits.inst := instBundle
    io.plexToDispatcher.bits.inst.predictedTarget := modifiedPC
    // When RAS makes a prediction (flush=true), mark this instruction as predicted
    io.plexToDispatcher.bits.inst.predict := instBundle.predict || rasBundle.flush

    // Pass on the RAS SP to Dispatcher
    io.plexToDispatcher.bits.rasSP := rasBundle.currentSP
}
