package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._
import components.memory._

class InstructionMemory(val hexFilePath: String) extends Module {
    val io = IO(new Bundle {
        val addr = Input(UInt(32.W))
        val inst = Output(UInt(32.W))
        val respValid = Output(Bool())
        val ready = Output(Bool())
        val dram = new SimpleMemIO(MemConfig(idWidth = 4, addrWidth = 32, dataWidth = 128))
    })

    // I-Cache Config: 64 Sets, 16 Byte Lines
    // Use idOffset=2 (IDs 2, 3) to avoid conflict with D-Cache (IDs 0, 1)
    val cacheConf = CacheConfig(nSetsWidth = 6, nCacheLineWidth = 4, idOffset = 2)
    val cache = Module(new Cache(cacheConf))

    // Connect to DRAM
    cache.io.dram <> io.dram

    // Connect to Fetcher (ReadOnly Port)
    // We assume request is always valid if address is presented.
    // Ideally we would have a request valid signal from fetcher.
    cache.io.port.valid := true.B 
    cache.io.port.addr := io.addr
    cache.io.port.wdata := 0.U
    cache.io.port.wmask := 0.U
    cache.io.port.isWr := false.B // Read Only
    
    // Output
    io.inst := cache.io.port.rdata
    io.respValid := cache.io.port.respValid
    io.ready := cache.io.port.ready
}
