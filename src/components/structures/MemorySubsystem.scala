package components.structures

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.memory._

class MemorySubsystem extends Module {
    val io = IO(new Bundle {
        val upstream = Flipped(new MemoryRequest)
        val mmio = new MemoryRequest
        val dram = new SimpleMemIO(MemConfig(idWidth = 4, addrWidth = 32, dataWidth = 128))
    })

    val isMMIO = io.upstream.req.bits.addr(31) === 1.U

    // --- Component Instantiation ---
    // Cache Config: 64 Sets, 16 Byte Lines (128-bit)
    val cacheConf = CacheConfig(nSetsWidth = 6, nCacheLineWidth = 4)
    val cache = Module(new Cache(cacheConf))

    // Connect Cache <-> DRAM
    cache.io.dram <> io.dram

    // --- Routing Logic ---
    
    // MMIO Path
    io.mmio.req.valid := io.upstream.req.valid && isMMIO
    io.mmio.req.bits := io.upstream.req.bits
    
    // Cache Path (Normal Memory)
    // We need to adapt the MemoryRequest to CachePort
    
    // 1. Generate Write Mask for Cache
    // Cache expects wmask relative to the "Write Data" not the "Address"
    // The Shift Logic is inside the Cache (it shifts wmask by addr_offset)
    val wmask = Wire(UInt(4.W))
    wmask := 0.U
    switch(io.upstream.req.bits.opWidth) {
        is(MemOpWidth.BYTE)     { wmask := 1.U } // 0001
        is(MemOpWidth.HALFWORD) { wmask := 3.U } // 0011
        is(MemOpWidth.WORD)     { wmask := 15.U } // 1111
    }
    
    cache.io.port.valid := io.upstream.req.valid && !isMMIO
    cache.io.port.addr := io.upstream.req.bits.addr
    cache.io.port.wdata := io.upstream.req.bits.data
    cache.io.port.wmask := wmask 
    cache.io.port.isWr := !io.upstream.req.bits.isLoad

    // Ready signal
    io.upstream.req.ready := Mux(isMMIO, io.mmio.req.ready, cache.io.port.ready)

    // --- Response Handling ---

    val respValid = cache.io.port.respValid || io.mmio.resp.valid
    
    // Select Data
    // Note: Cache returns aligned word.
    val rawData = Mux(io.mmio.resp.valid, io.mmio.resp.bits, cache.io.port.rdata)

    // Data Processing (Sign Extension / Selection)
    // We need to store request info for the responding data.
    
    val reqInfoQueue = Module(new Queue(new LoadStoreAction, entries = 4))
    
    // Enqueue condition logic:
    // 1. Cache requests (Load & Store) always return a response (Cache is blocking/ack based).
    // 2. MMIO requests: Only Loads return a response. Stores do not.
    val isCacheReq = !isMMIO
    val isMMIOLoad = isMMIO && io.upstream.req.bits.isLoad
    
    reqInfoQueue.io.enq.valid := io.upstream.req.fire && (isCacheReq || isMMIOLoad)
    reqInfoQueue.io.enq.bits := io.upstream.req.bits
    
    val processingResp = respValid // A response is arriving this cycle
    
    // Formatting Logic
    val info = reqInfoQueue.io.deq.bits
    
    val addrOffset = info.addr(1, 0)
    
    val rbyte = MuxLookup(addrOffset, 0.U)(
      Seq(
        0.U -> rawData(7, 0),
        1.U -> rawData(15, 8),
        2.U -> rawData(23, 16),
        3.U -> rawData(31, 24)
      )
    )
    val rhalf = Mux(addrOffset(1) === 0.U, rawData(15, 0), rawData(31, 16))
    val sextByte = Cat(Fill(24, rbyte(7)), rbyte)
    val sextHalf = Cat(Fill(16, rhalf(15)), rhalf)

    val formattedData = Wire(UInt(32.W))
    formattedData := rawData
    switch(info.opWidth) {
        is(MemOpWidth.BYTE) {
            formattedData := Mux(info.isUnsigned, rbyte, sextByte)
        }
        is(MemOpWidth.HALFWORD) {
            formattedData := Mux(info.isUnsigned, rhalf, sextHalf)
        }
    }
    
    // Output Queue
    val respQueue = Module(new Queue(UInt(32.W), entries = 2))
    
    // Push formatted data to RespQueue ONLY if it was a LOAD
    respQueue.io.enq.valid := respValid && info.isLoad
    respQueue.io.enq.bits := formattedData
    
    // Pop info when we process a valid response
    reqInfoQueue.io.deq.ready := respValid 
    
    // Upstream is just the queue output
    io.upstream.resp <> respQueue.io.deq

    // MMIO resp ready
    io.mmio.resp.ready := respQueue.io.enq.ready
}
