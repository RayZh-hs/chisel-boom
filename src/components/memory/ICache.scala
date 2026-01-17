package components.memory

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

class ICache(conf: CacheConfig) extends Module {
    val io = IO(new Bundle {
        // Request Interface
        val req  = Flipped(Decoupled(UInt(32.W))) // .bits = addr, .valid = req
        
        // Response Interface
        val resp = Decoupled(UInt(32.W))          // .bits = data, .valid = hit
        
        // DRAM Interface
        val dram = new SimpleMemIO(
          MemConfig(idWidth = 4, addrWidth = 32, dataWidth = (1 << conf.nCacheLineWidth) * 8)
        )
        val events = new CacheEvents
    })

    val nSets = 1 << conf.nSetsWidth
    val nBytes = 1 << conf.nCacheLineWidth
    val tagWidth = 32 - conf.nSetsWidth - conf.nCacheLineWidth
    val RD_ID = (1 + conf.idOffset).U(4.W)

    // Memories (1 cycle latency)
    val mem  = SyncReadMem(nSets, Vec(nBytes, UInt(8.W)))
    class TagEntry extends Bundle {
        val valid = Bool()
        val tag   = UInt(tagWidth.W)
    }
    val tags = SyncReadMem(nSets, new TagEntry)

    // Helper functions
    def get_index(addr: UInt) = addr(conf.nCacheLineWidth + conf.nSetsWidth - 1, conf.nCacheLineWidth)
    def get_tag(addr: UInt)   = addr(31, conf.nCacheLineWidth + conf.nSetsWidth)
    def get_offset(addr: UInt) = addr(conf.nCacheLineWidth - 1, 0)

    // State Machine
    val sReady :: sRefill :: Nil = Enum(2)
    val state = RegInit(sReady)
    val refillAddr = Reg(UInt(32.W)) // Used only to talk to DRAM, not to replay to CPU
    val refillSent = RegInit(false.B)
    val justRefilled = RegInit(false.B)

    io.events.hit := false.B
    io.events.miss := false.B

    // -----------------------------------------------------------
    // Stage 1: Request (Cycle 0)
    // -----------------------------------------------------------
    
    // We only accept requests if we are Ready (not refilling)
    // AND if the previous cycle wasn't a miss (pipelining stall)
    io.req.ready := (state === sReady)

    val reqValid    = io.req.valid && io.req.ready
    val reqAddr     = io.req.bits

    // Access Memories
    val tagRead  = tags.read(get_index(reqAddr), reqValid)
    val dataRead = mem.read(get_index(reqAddr), reqValid)
    
    // Pipeline Register to match SRAM latency (Cycle 0 -> Cycle 1)
    val s1_valid = RegNext(reqValid, init=false.B)
    val s1_addr  = RegNext(reqAddr)

    // -----------------------------------------------------------
    // Stage 2: Tag Check & Hit/Miss (Cycle 1)
    // -----------------------------------------------------------
    
    val hit = s1_valid && tagRead.valid && (tagRead.tag === get_tag(s1_addr))
    val miss = s1_valid && !hit
    
    io.events.hit := hit && !justRefilled
    io.events.miss := miss && (state === sReady)
    when(s1_valid) { justRefilled := false.B }

    // If Hit: Present Data
    val aligned_offset = Cat(get_offset(s1_addr)(conf.nCacheLineWidth - 1, 2), 0.U(2.W))
    val dataWord = Cat(
        dataRead((aligned_offset + 3.U).asUInt),
        dataRead((aligned_offset + 2.U).asUInt),
        dataRead((aligned_offset + 1.U).asUInt),
        dataRead(aligned_offset.asUInt)
    )

    // Output Logic
    // Valid only if we hit. 
    // If we missed, valid is low, and the Fetcher must retry later.
    io.resp.valid := hit
    io.resp.bits  := dataWord

    // -----------------------------------------------------------
    // Miss Handling & Refill
    // -----------------------------------------------------------

    when(miss && state === sReady) {
        state       := sRefill
        refillAddr  := s1_addr // Snapshot address for DRAM only
        refillSent  := false.B
    }

    // DRAM Request
    val dram_addr = Cat(get_tag(refillAddr), get_index(refillAddr), 0.U(conf.nCacheLineWidth.W))
    
    io.dram.req.valid     := (state === sRefill) && !refillSent
    io.dram.req.bits.id   := RD_ID
    io.dram.req.bits.addr := dram_addr
    io.dram.req.bits.isWr := false.B
    io.dram.req.bits.mask := 0.U
    io.dram.req.bits.data := 0.U

    when(io.dram.req.fire) { refillSent := true.B }

    // DRAM Response
    io.dram.resp.ready := true.B
    when(io.dram.resp.valid && (io.dram.resp.bits.id === RD_ID)) {
        // Refill Cache Arrays
        val refill_data = io.dram.resp.bits.data
        val refill_vec = VecInit(Seq.tabulate(nBytes)(i => refill_data(8*i+7, 8*i)))
        
        mem.write(get_index(refillAddr), refill_vec)
        
        val newTag = Wire(new TagEntry)
        newTag.valid := true.B
        newTag.tag   := get_tag(refillAddr)
        tags.write(get_index(refillAddr), newTag)
        
        // Go back to Ready. 
        // The Fetcher is still holding the address, so next cycle it will request again
        // and get a Hit.
        state := sReady
        justRefilled := true.B
    }
}