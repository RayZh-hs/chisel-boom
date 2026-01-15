package components.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import chisel3.util.experimental.loadMemoryFromFile

class MockDRAM(conf: MemConfig, latency: Int, sizeBytes: Int, initFile: Option[String] = None) extends Module {
  val io = IO(Flipped(new SimpleMemIO(conf)))

  // 1. Internal Storage: Use 32-bit width to match standard RISC-V Hex Files
  //    This ensures that loadMemoryFromFile places instructions consecutively (Addr 0, 4, 8...)
  val internalWidth = 32
  val wordsPerBlock = conf.dataWidth / internalWidth // 128 / 32 = 4
  val depth = sizeBytes / (internalWidth / 8)
  
  // RAM is 32-bits wide. 
  // Line 1 of Hex File -> ram(0) -> PC=0
  // Line 2 of Hex File -> ram(1) -> PC=4
  val ram = Mem(depth, UInt(internalWidth.W))
  
  if (initFile.isDefined) {
    loadMemoryFromFileInline(ram, initFile.get)
  }

  // 2. Address Logic
  // io.req.bits.addr is in Bytes. Convert to 32-bit word index.
  // Example: Addr 0 -> BaseIndex 0. Addr 16 -> BaseIndex 4.
  val baseIndex = io.req.bits.addr >> 2

  // 3. Write Logic (Split 128-bit write into 4x 32-bit writes)
  when(io.req.fire && io.req.bits.isWr) {
    val fullData = io.req.bits.data
    val fullMask = io.req.bits.mask 

    for (i <- 0 until wordsPerBlock) {
      val idx = baseIndex + i.U
      
      // Extract 32-bit chunk
      val subData = fullData((i + 1) * 32 - 1, i * 32)
      val subMask = fullMask((i + 1) * 4 - 1, i * 4)

      // Manual Read-Modify-Write for sub-word masking
      val oldWord = ram(idx)
      val newWordBytes = VecInit(Seq.tabulate(4) { b =>
        val byteMask = subMask(b)
        val oldByte = oldWord(b * 8 + 7, b * 8)
        val newByte = subData(b * 8 + 7, b * 8)
        Mux(byteMask, newByte, oldByte)
      })
      ram(idx) := newWordBytes.asUInt
    }
  }

  // 4. Read Logic (Gather 4x 32-bit words into 128-bit line)
  // We read 4 consecutive words from the 32-bit RAM to form one 128-bit Cache Line
  val readChunks = Wire(Vec(wordsPerBlock, UInt(internalWidth.W)))
  for (i <- 0 until wordsPerBlock) {
    readChunks(i) := ram(baseIndex + i.U)
  }
  
  // Concatenate them. Little Endian: Word 0 is LSB.
  val memOut = RegNext(readChunks.asUInt)

  // 5. Latency Pipeline 
  class PipelinePacket extends Bundle {
    val id   = UInt(conf.idWidth.W)
    val data = UInt(conf.dataWidth.W)
    val isRd = Bool() 
  }

  val reqInFlight = RegNext(io.req.bits)
  val reqValid    = RegNext(io.req.fire)
  
  val pipePayload = Wire(new PipelinePacket)
  pipePayload.id   := reqInFlight.id
  pipePayload.data := memOut
  pipePayload.isRd := reqValid

  // Adjust latency (subtract 1 because we already used 1 cycle for RegNext(ram))
  val extraLatency = if (latency > 1) latency - 1 else 0
  val delayedResp = Pipe(pipePayload.isRd, pipePayload, extraLatency)

  // 6. Output Queue
  val queueSize = latency + 64
  val outQueue = Module(new Queue(new MemResponse(conf), entries = queueSize))

  outQueue.io.enq.valid := delayedResp.valid
  outQueue.io.enq.bits.data := delayedResp.bits.data
  outQueue.io.enq.bits.id   := delayedResp.bits.id

  io.resp <> outQueue.io.deq

  // Backpressure Logic
  val inFlightCount = RegInit(0.U(32.W))
  when (io.req.fire && !delayedResp.valid) {
    inFlightCount := inFlightCount + 1.U
  } .elsewhen (!io.req.fire && delayedResp.valid) {
    inFlightCount := inFlightCount - 1.U
  }
  
  val totalOccupancy = inFlightCount + outQueue.io.count
  io.req.ready := totalOccupancy < queueSize.U
}