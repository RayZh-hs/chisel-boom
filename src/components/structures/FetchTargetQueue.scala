package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._

class FetchTargetQueue extends Module {
    val io = IO(new Bundle {
        ???
    })

    val queue = SyncReadMem(Derived.FTQ_SIZE, UInt(32.W))
}
