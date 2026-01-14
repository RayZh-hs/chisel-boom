package components.memory

import chisel3._
import chisel3.util._

class MockDRAM(conf: MemConfig, latency: Int, sizeBytes: Int) extends Module {
  val io = IO(new SimpleMemIO(conf))

  // 1. The Memory Storage
  // We use a Vec of Bytes to make masking easy. 
  // Depth = sizeBytes / (dataWidth / 8)
  val bytesPerWord = conf.dataWidth / 8
  val depth        = sizeBytes / bytesPerWord
  
  // This Mem is "Magic" (Simulation only usually) - synchronous read/write
  val ram = SyncReadMem(depth, Vec(bytesPerWord, UInt(8.W)))

  // 2. Address Calculation
  // Convert byte-address to word-index
  // Note: specific to the fact that addr is usually byte-aligned
  val index = io.req.bits.addr >> log2Ceil(bytesPerWord)

  // 3. Write Logic
  // Convert UInt mask to Seq[Bool] for the Mem write mask
  val writeMask = io.req.bits.mask.asBools
  // Split input data into bytes
  val writeBytes = VecInit(Seq.tabulate(bytesPerWord) { i => 
    io.req.bits.data(8*(i+1)-1, 8*i) 
  })

  // Perform Write immediately (DRAMs buffer writes instantly in the controller)
  when(io.req.fire && io.req.bits.isWr) {
    ram.write(index, writeBytes, writeMask)
  }

  // 4. Read Logic
  // We read every cycle if there is a valid read request.
  // SyncReadMem output is available next cycle (latency = 1)
  val memOut = ram.read(index, io.req.fire && !io.req.bits.isWr)
  
  // Reconstruct bytes back to UInt
  val readData = Wire(UInt(conf.dataWidth.W))
  readData := memOut.asUInt

  // 5. The Latency Pipeline (Simulating High Latency)
  // We need to carry the metadata (ID, etc) alongside the memory access delay.
  
  class PipelinePacket extends Bundle {
    val id   = UInt(conf.idWidth.W)
    val data = UInt(conf.dataWidth.W)
    val isRd = Bool() // Only send response if it was a read
  }

  // This pipe models the fixed latency of the DRAM arrays
  // We adjust latency - 1 because SyncReadMem already takes 1 cycle
  val pipeIn = Wire(new PipelinePacket)
  pipeIn.id   := io.req.bits.id
  pipeIn.data := readData // Note: This data is actually from the *previous* cycle's read request in strict hardware terms, 
                          // but for a behavioral mock, we can cheat or use a proper pipeline.
                          // Let's do it cleanly:
  
  // CLEAN PIPELINE APPROACH:
  // Step A: Preserve Request Metadata in a shift register corresponding to Mem Read Latency
  val reqInFlight = RegNext(io.req.bits)
  val reqValid    = RegNext(io.req.fire)
  
  // Step B: Match the data coming out of RAM (1 cycle later) with the metadata
  val pipePayload = Wire(new PipelinePacket)
  pipePayload.id   := reqInFlight.id
  pipePayload.data := memOut.asUInt
  pipePayload.isRd := reqValid

  // Step C: Add the artificial "High Latency" delay
  // Pipe is a Valid(T) shift register.
  // If desired latency is 20, and we already used 1 for RAM, we add 19.
  val extraLatency = if (latency > 1) latency - 1 else 0
  val delayedResp = Pipe(pipePayload.isRd, pipePayload, extraLatency)

  // 6. Output Queue (Simulating Bandwidth & Backpressure)
  // Even if DRAM is fast, the Cache might not be ready to receive.
  // We need a Queue to store completed results.
  val outQueue = Module(new Queue(new MemResponse(conf), entries = 4))

  outQueue.io.enq.valid := delayedResp.valid
  outQueue.io.enq.bits.data := delayedResp.bits.data
  outQueue.io.enq.bits.id   := delayedResp.bits.id

  // 7. Drive IO
  io.resp <> outQueue.io.deq

  // We are ready as long as the Queue has space to buffer responses.
  io.req.ready := outQueue.io.enq.ready

  // Ensure that responses are not lost if the output queue overflows
  assert(!(delayedResp.valid && !outQueue.io.enq.ready), "MockDRAM response dropped: outQueue is full")
}