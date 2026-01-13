package components.memory

import chisel3._
import chisel3.util._

// Configuration parameters
case class MemConfig(
    idWidth: Int = 4,
    addrWidth: Int = 32,
    dataWidth: Int = 32
)

// 1. Request Bundle (Cache -> DRAM)
class MemRequest(val conf: MemConfig) extends Bundle {
    val id = UInt(conf.idWidth.W)
    val addr = UInt(conf.addrWidth.W)
    val data = UInt(conf.dataWidth.W)
    val isWr = Bool()
    val mask = UInt((conf.dataWidth / 8).W)
}

// 2. Response Bundle (DRAM -> Cache)
class MemResponse(val conf: MemConfig) extends Bundle {
    val id = UInt(conf.idWidth.W)
    val data = UInt(conf.dataWidth.W)
}

// 3. The Main Interface
class SimpleMemIO(val conf: MemConfig) extends Bundle {
    val req = Decoupled(new MemRequest(conf))
    val resp = Flipped(Decoupled(new MemResponse(conf)))
}
