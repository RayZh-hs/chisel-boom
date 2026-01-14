package components.memory

import chisel3._
import chisel3.util._

import chisel3.util.experimental.{loadMemoryFromFile, loadMemoryFromFileInline}

class MockDRAM(conf: MemConfig, latency: Int, sizeBytes: Int, initFile: Option[String] = None) extends Module {
  val io = IO(Flipped(new SimpleMemIO(conf)))

  // 1. The Memory Storage
  val bytesPerWord = conf.dataWidth / 8
  val depth        = sizeBytes / bytesPerWord
  
  // Use Mem (Combinational Read) to allow Read-Modify-Write for masked writes on UInt
  // We need UInt (Ground Type) to support loadMemoryFromFileInline
  val ram = Mem(depth, UInt(conf.dataWidth.W))
  
  if (initFile.isDefined) {
    loadMemoryFromFileInline(ram, initFile.get)
  }

  // 2. Address Calculation
  val index = io.req.bits.addr >> log2Ceil(bytesPerWord)

  // 3. Write Logic (Read-Modify-Write)
  when(io.req.fire && io.req.bits.isWr) {
    val oldDataBits = ram(index)
    val newDataBits = io.req.bits.data
    val maskBits    = io.req.bits.mask // 1 bit per byte
    
    // Reconstruct new word byte-by-byte
    val mergedBytes = VecInit(Seq.tabulate(bytesPerWord) { i =>
        val byteMask = maskBits(i)
        val oldByte = oldDataBits(8 * i + 7, 8 * i)
        val newByte = newDataBits(8 * i + 7, 8 * i)
        Mux(byteMask, newByte, oldByte)
    })
    
    ram(index) := mergedBytes.asUInt
  }

  // 4. Read Logic
  // Mem has 0 latency. We add 1 cycle register to match SyncReadMem behavior.
  val memOut = RegNext(ram(index))
  
  // Reconstruct bytes back to UInt
  val readData = Wire(UInt(conf.dataWidth.W))
  readData := memOut

  // 5. The Latency Pipeline (Simulating High Latency)
  class PipelinePacket extends Bundle {
    val id   = UInt(conf.idWidth.W)
    val data = UInt(conf.dataWidth.W)
    val isRd = Bool() // Only send response if it was a read (or write ack)
  }

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
  val queueSize = latency + 64 // Increased buffer
  val outQueue = Module(new Queue(new MemResponse(conf), entries = queueSize))

  outQueue.io.enq.valid := delayedResp.valid
  outQueue.io.enq.bits.data := delayedResp.bits.data
  outQueue.io.enq.bits.id   := delayedResp.bits.id

  // 7. Drive IO
  io.resp <> outQueue.io.deq

  // Track inflight requests to ensure we don't overflow the queue
  // counting requests that are in the pipeline but haven't reached the queue yet
  val inFlightCount = RegInit(0.U(32.W))
  
  when (io.req.fire && !delayedResp.valid) {
    inFlightCount := inFlightCount + 1.U
  } .elsewhen (!io.req.fire && delayedResp.valid) {
    inFlightCount := inFlightCount - 1.U
  }
  
  // We can convert to SInt to avoid underflow if logic bug, but correct logic shouldn't underflow
  // Logic: Total items (in pipe + in queue) must be < Capacity
  
  val totalOccupancy = inFlightCount + outQueue.io.count
  io.req.ready := totalOccupancy < queueSize.U

  // Ensure that responses are not lost if the output queue overflows
  assert(!(delayedResp.valid && !outQueue.io.enq.ready), "MockDRAM response dropped: outQueue is full")
}