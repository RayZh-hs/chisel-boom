package components.memory

import chisel3._
import chisel3.util._

class DPIDRAMIO(conf: MemConfig) extends Bundle {
    val req = Flipped(Decoupled(new MemRequest(conf)))
    val resp = Decoupled(new MemResponse(conf))
    val clock = Input(Clock())
    val reset = Input(Bool())
}

/** DPIDRAM BlackBox Wrapper
  *
  * Provides a Uniform Memory Interface for the CPU.
  *
  * @param conf
  *   Memory configuration parameters
  * @param hexFile
  *   Hex file to initialize DRAM contents
  *
  * @note
  *   DPI Stands for Direct Programming Interface
  */
class DPIDRAM(conf: MemConfig, hexFile: String)
    extends BlackBox(Map("FILENAME" -> hexFile))
    with HasBlackBoxResource {
    val io = IO(new DPIDRAMIO(conf))

    // Force rebuild 8
    addResource("/DPIDRAM.sv")
    addResource("/dram.cc")
}
