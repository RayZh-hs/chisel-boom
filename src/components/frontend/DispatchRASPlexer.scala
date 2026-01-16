package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

/**
  * Dispatch RAS Plexer
  * 
  * This module combines signals from RASAdaptor and InstDispatcher.
  * It pipes the same ready signal to both modules coming from Dispatcher.
  * 
  * When the downstream stage is ready, it extracts data from both slots and,
  * depending on the PC overwrite status, swaps the PC target.
  * 
  * This is a fully combinational module, with no internal state and does not
  * cost one cycle latency to the pipeline.
  * 
  */
class DispatchRASPlexer extends Module {
    val io = IO(new Bundle {
        val instFromDecoder = Flipped(Decoupled(new DecodedInstBundle))
        val rasBundleFromAdaptor = Flipped(Decoupled(new RASAdaptorBundle))
        val instToDispatcher = Decoupled(new DecodedInstBundle)
        val rasSPToDispatcher = Decoupled(UInt(RAS_WIDTH.W))
    })

    io.instFromDecoder.ready := io.instToDispatcher.ready
    io.rasBundleFromAdaptor.ready := io.instToDispatcher.ready
    val bothValid = io.instFromDecoder.valid && io.rasBundleFromAdaptor.valid
    io.instToDispatcher.valid := bothValid
    io.rasSPToDispatcher.valid := bothValid

    val instBundle = io.instFromDecoder.bits
    val rasBundle = io.rasBundleFromAdaptor.bits
    
    // Swap PC if RAS indicates a flush
    val modifiedPC = Mux(rasBundle.flush, rasBundle.flushNextPC, instBundle.predictedTarget)
    io.instToDispatcher.bits := instBundle
    io.instToDispatcher.bits.predictedTarget := modifiedPC

    // Pass on the RAS SP to Dispatcher
    io.rasSPToDispatcher.bits := rasBundle.currentSP
}