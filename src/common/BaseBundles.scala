package common

import chisel3._
import chisel3.util._
import Configurables._

class BrFlagBundle extends Bundle {
    val brFlag = UInt(BR_DEPTH.W)
}
