package components.memory

import chisel3._
import chisel3.util._

case class CacheConfig(
    nReadPorts: Int,
    nWritePorts: Int,
    nSetsWidth: Int,
    nCacheLines: Int
)

class ReadPort extends Bundle {
    val addr = Input(UInt(32.W)) // aligned
    val data = Output(UInt(32.W))
    val valid = Input(Bool())
    val ready = Output(Bool())
}

class WritePort extends Bundle {
    val addr = Input(UInt(32.W)) // aligned
    val data = Input(UInt(32.W))
    val mask = Input(UInt(4.W))
    val valid = Input(Bool())
    val ready = Output(Bool())
}


class Cache(conf: CacheConfig) extends Module {
    val io = IO(new Bundle {
        // LoadStore Ports
        val readPorts = Vec(conf.nReadPorts, new ReadPort)
        val writePorts = Vec(conf.nWritePorts, new WritePort)

        // DRAM Interface
        val memReq = Decoupled(new MemRequest(MemConfig()))
        val memResp = Flipped(Decoupled(new MemResponse(MemConfig())))
    })


    class CacheEntry extends Bundle {
        val valid = Bool()
        val tag = UInt((32 - conf.nSetsWidth - 2).W) // 4-byte aligned addresses
    }
    val mem = SyncReadMem((1 << conf.nSetsWidth), Vec(conf.nCacheLines, UInt(8.W)))
    val tags = SyncReadMem((1 << conf.nSetsWidth), new CacheEntry) // Tag memory


    for (i <- 0 until conf.nReadPorts) {
        val tag = io.readPorts(i).addr(31, conf.nSetsWidth + 2)
        val index = io.readPorts(i).addr(conf.nSetsWidth + 1, 2)

        val tagEntry = tags.read(index, io.readPorts(i).valid)
        val hit = tagEntry.valid && (tagEntry.tag === tag)

        
    }


    
}