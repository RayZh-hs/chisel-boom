package components.structures
import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility.CycleAwareModule

class MMIORouter(val mappings: Seq[UInt]) extends Module {
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

    // Response Logic (1 cycle latency)
    io.upstream.resp.valid := RegNext(
      io.upstream.req.valid && anySel && io.upstream.req.bits.isLoad
    )
    
    // Broadcast ready to devices (simplification)
    for (i <- 0 until mappings.length) {
        io.devices(i).resp.ready := io.upstream.resp.ready
    }

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
    val io = IO(Flipped(new MemoryRequest))

    io.req.ready := true.B

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
