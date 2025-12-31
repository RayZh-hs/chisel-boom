package core

import chisel3._
import chisel3.util._
import BoomCoreSections._

class BoomCore(val hexFile: String) extends Module {
    val frontend = Module(new Frontend(hexFile))
    val backend = Module(new Backend)
    connectFrontendBackend(frontend, backend)
}
