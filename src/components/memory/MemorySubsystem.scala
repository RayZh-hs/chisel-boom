package components.structures

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.memory._
import utility.CycleAwareModule

/** Memory Subsystem
  *
  * This module capsulates how memory requests are handled. It is connected to
  * the LSU (upstream) and the DRAM (downstream).
  */
class MemorySubsystem extends Module {
    // IO Definition
    val io = IO(new Bundle {
        val upstream = Flipped(new MemoryRequest)
        val mmio = new MemoryRequest
        val dram = new SimpleMemIO(
          MemConfig(idWidth = 4, addrWidth = 32, dataWidth = 128)
        )
        val cacheEvents = new CacheEvents
    })

    // Component Instantiation
    val cacheConf = CacheConfig(nSetsWidth = 6, nCacheLineWidth = 4)
    val cache = Module(new Cache(cacheConf))
    cache.io.dram <> io.dram
    io.cacheEvents := cache.io.events

    val reqInfoQueue = Module(new Queue(new LoadStoreAction, entries = 4))

    // Request Routing
    val isMMIO = io.upstream.req.bits.addr(31) === 1.U
    val req = io.upstream.req.bits

    // WMask Decoding
    val wmask = MuxLookup(req.opWidth, 15.U)(
      Seq(
        MemOpWidth.BYTE -> 1.U,
        MemOpWidth.HALFWORD -> 3.U
      )
    )

    // Drive MMIO Request
    io.mmio.req.valid := io.upstream.req.valid && isMMIO
    io.mmio.req.bits := req

    // Drive Cache Request
    cache.io.port.valid := io.upstream.req.valid && !isMMIO
    cache.io.port.addr := req.addr
    cache.io.port.wdata := req.data
    cache.io.port.wmask := wmask
    cache.io.port.isWr := !req.isLoad

    // Track Metadata (Enqueue if either path fires)
    val targetReady = Mux(isMMIO, io.mmio.req.ready, cache.io.port.ready)
    io.upstream.req.ready := targetReady && reqInfoQueue.io.enq.ready

    reqInfoQueue.io.enq.valid := io.upstream.req.fire
    reqInfoQueue.io.enq.bits := req

    // Response Routing
    // Merge responses from Cache and MMIO into one stream
    val respArb = Module(new Arbiter(UInt(32.W), 2))

    // Port 0: Cache Response (High Priority)
    /* 
     * @note
     *   If Cache response has no backpressure (no 'ready'), we assume it connects valid-to-valid
     */
    respArb.io.in(0).valid := cache.io.port.respValid
    respArb.io.in(0).bits := cache.io.port.rdata

    // Port 1: MMIO Response
    respArb.io.in(1) <> io.mmio.resp

    // Data Formatting & Output
    val info = reqInfoQueue.io.deq.bits
    val rawData = respArb.io.out.bits

    // Byte/Halfword Alignment & Sign Extension
    val addrOffset = info.addr(1, 0)
    val rbyte = rawData >> (addrOffset * 8.U) // Shift to LSB
    val rhalf = rawData >> ((addrOffset(1) << 4)) // Shift by 0 or 16

    val formattedData = Wire(UInt(32.W))
    formattedData := rawData

    when(info.opWidth === MemOpWidth.BYTE) {
        formattedData := Mux(info.isUnsigned, rbyte(7, 0), signExtByte(rbyte(7, 0)))
    }.elsewhen(info.opWidth === MemOpWidth.HALFWORD) {
        formattedData := Mux(info.isUnsigned, rhalf(15, 0), signExtendHalfWord(rhalf(15, 0)))
    }

    // Connect Arbiter output to Upstream
    io.upstream.resp.valid := respArb.io.out.valid
    io.upstream.resp.bits := formattedData

    // Backpressure flow
    respArb.io.out.ready := io.upstream.resp.ready

    // Dequeue metadata only when a response is successfully sent upstream
    reqInfoQueue.io.deq.ready := io.upstream.resp.fire

    private def signExtByte(v: UInt) = Cat(Fill(24, v(7)), v)
    private def signExtendHalfWord(v: UInt) = Cat(Fill(16, v(15)), v)
}
