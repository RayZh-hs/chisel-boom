package components.structures
import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility.CycleAwareModule

class MMIORouter(val mappings: Seq[UInt]) extends Module {
    val io = IO(new Bundle {
        val upstream = new MemoryInterface
        // Parameterized ports to connect to devices
        val devices = Vec(mappings.length, Flipped(new MemoryInterface))
    })

    // Routing Logic
    val sel = mappings.map(addr => io.upstream.req.bits.addr === addr)
    val anySel = sel.reduce(_ || _)

    for (i <- 0 until mappings.length) {
        io.devices(i).req.valid := io.upstream.req.valid && sel(i)
        io.devices(i).req.bits := io.upstream.req.bits
    }

    // Response Logic (1 cycle latency)
    io.upstream.resp.valid := RegNext(
      io.upstream.req.valid && anySel && io.upstream.req.bits.isLoad
    )

    // Mux for responses
    val respData = Wire(UInt(32.W))
    respData := 0.U
    for (i <- 0 until mappings.length) {
        when(RegNext(sel(i))) {
            respData := io.devices(i).resp.bits
        }
    }
    io.upstream.resp.bits := respData
}

class MMIODevice extends CycleAwareModule {
    val io = IO(new MemoryInterface)

    io.resp.valid := io.req.valid && io.req.bits.isLoad
    io.resp.bits := 0.U
}

class PrintDevice extends MMIODevice {
    when(io.req.valid && !io.req.bits.isLoad) {
        printf(p"@PRINT:${io.req.bits.data.asSInt}\n")
    }
}

class ExitDevice extends MMIODevice {
    when(io.req.valid && !io.req.bits.isLoad) {
        printf(p"@EXIT: Exit Code = ${io.req.bits.data.asUInt}\n")
    }
}
