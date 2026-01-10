package components.structures
import chisel3._
import chisel3.util._
import common._
import common.Configurables._

class MMIORouter extends Module {
    val io = IO(new Bundle {
        val req = Flipped(Valid(new LoadStoreAction))
        val resp = Valid(UInt(32.W))
    })

    val printDevice = Module(new PrintDevice)
    val exitDevice = Module(new ExitDevice)

    // Routing Logic
    // 0x8000_0000: Print Device
    // 0x8000_0008: Exit Device
    
    // Address bit 31 is the MMIO indicator, and we check lower bits for device selection
    val isPrint = io.req.bits.addr === "h80000000".U
    val isExit = io.req.bits.addr === "h80000008".U

    printDevice.io.req.valid := io.req.valid && isPrint
    printDevice.io.req.bits := io.req.bits

    exitDevice.io.req.valid := io.req.valid && isExit
    exitDevice.io.req.bits := io.req.bits

    // Response Logic (1 cycle latency to match LSU)
    // We only care about loads for responses
    io.resp.valid := RegNext(io.req.valid && (isPrint || isExit) && io.req.bits.isLoad)
    io.resp.bits := RegNext(Mux(isPrint, printDevice.io.resp.bits, exitDevice.io.resp.bits))
}
class MMIODevice extends Module {
    val io = IO(new Bundle {
        val req = Flipped(Valid(new LoadStoreAction))
        val resp = Valid(UInt(32.W))
    })

    io.resp.valid := io.req.valid && io.req.bits.isLoad
    io.resp.bits := 0.U
}


class PrintDevice extends MMIODevice {
    when(io.req.valid && !io.req.bits.isLoad) {
        printf(p"MMIO Print Device: Data = 0x${Hexadecimal(io.req.bits.data)}\n")
    }
}

class ExitDevice extends MMIODevice {
    when(io.req.valid && !io.req.bits.isLoad) {
        printf(p"MMIO Exit Device: Exiting with code = 0x${Hexadecimal(io.req.bits.data)}\n")
        stop()
    }
}