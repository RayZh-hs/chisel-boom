package common

import chisel3._
import chisel3.util._
import Configurables._

class PRFReadBundle extends Bundle {
    val addr1 = Output(UInt(PREG_WIDTH.W))
    val data1 = Input(UInt(32.W))
    val addr2 = Output(UInt(PREG_WIDTH.W))
    val data2 = Input(UInt(32.W))
}

class PRFWriteBundle extends Bundle {
    val addr = Output(UInt(PREG_WIDTH.W))
    val data = Output(UInt(32.W))
    val en = Output(Bool())
}
