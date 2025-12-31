package components.frontend

import chisel3._
import chisel3.util._

/** Instruction Dispatcher
  *
  * Responsible for filling in register renaming data and
  */
class InstDispatcher extends Module {
    val io = IO(new Bundle {
        val instInput = Flipped(Decoupled(new common.DecodeToDispatchBundle))
    })
}
