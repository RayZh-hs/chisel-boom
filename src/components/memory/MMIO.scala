package components.memory
import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility.CycleAwareModule

/** MMIO Router
  *
  * Handles routing of memory requests to multiple MMIO devices based on address
  * mappings.
  *
  * @param mappings
  *   The mappings in question.
  */
class MMIORouter(val mappings: Seq[UInt]) extends Module {
    // IO Definition
    val io = IO(new Bundle {
        val upstream = Flipped(new MemoryRequest)
        // Parameterized ports to connect to devices
        val devices = Vec(mappings.length, new MemoryRequest)
    })

    // Routing Logic
    val sel = mappings.map(addr => io.upstream.req.bits.addr === addr)
    val anySel = sel.reduce(_ || _)

    // Default ready
    io.upstream.req.ready := false.B

    for (i <- 0 until mappings.length) {
        io.devices(i).req.valid := io.upstream.req.valid && sel(i)
        io.devices(i).req.bits := io.upstream.req.bits
        when(sel(i)) {
            io.upstream.req.ready := io.devices(i).req.ready
        }
    }

    // Response Logic (1 Cycle Latency)
    io.upstream.resp.valid := RegNext(
      io.upstream.req.valid && anySel
    )

    // Broadcast ready to devices
    for (i <- 0 until mappings.length) {
        io.devices(i).resp.ready := io.upstream.resp.ready
    }

    val respData = Wire(UInt(32.W))
    respData := 0.U
    for (i <- 0 until mappings.length) {
        when(RegNext(sel(i))) {
            respData := io.devices(i).resp.bits
        }
    }
    io.upstream.resp.bits := respData
}

/** MMIO Interface Trait
  *
  * Defines a standard interface for MMIO devices.
  */
trait MMIOInterface {
    val io = IO(Flipped(new MemoryRequest))

    io.req.ready := true.B

    io.resp.valid := io.req.valid && io.req.bits.isLoad
    io.resp.bits := 0.U
}

class PrintDevice extends CycleAwareModule with MMIOInterface {
    val debugOut = IO(Decoupled(UInt(32.W)))
    val stopPrinting = IO(Input(Bool()))

    // A large enough queue to buffer output
    // The testbench should drain this frequently enough
    val queue = Module(new Queue(UInt(32.W), 4096))
    chisel3.assert(queue.io.enq.ready, "PrintDevice queue is full!")
    val write = io.req.valid && !io.req.bits.isLoad

    queue.io.enq.valid := write && !stopPrinting
    queue.io.enq.bits := io.req.bits.data

    // Connect to output
    debugOut <> queue.io.deq

    // Backpressure to CPU
    io.req.ready := queue.io.enq.ready
}

class ExitDevice extends CycleAwareModule with MMIOInterface {
    val exitOut = IO(Output(Valid(UInt(32.W))))
    val stopPrinting = IO(Output(Bool()))

    val stopping = RegInit(false.B)
    val exitCode = Reg(UInt(32.W))
    stopPrinting := stopping

    exitOut.valid := stopping
    exitOut.bits := exitCode

    when(io.req.valid && !io.req.bits.isLoad) {
        stopping := true.B
        exitCode := io.req.bits.data
    }
}
