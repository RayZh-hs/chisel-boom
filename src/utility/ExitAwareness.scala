package utility

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

trait ExitAwareness {
    val exited = IO(Input(Bool()))
    BoringUtils.addSink(exited, "exited")
    dontTouch(exited)
}

trait ExitAwarenessProfiling {
    import common.Configurables.Profiling.isAnyEnabled
    val exited = Wire(Bool())
    if (isAnyEnabled) {
        BoringUtils.addSink(exited, "exited")
        dontTouch(exited)
    } else {
        exited := false.B
    }
}
