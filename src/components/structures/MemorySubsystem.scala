package components.structures

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

class MemorySubsystem extends Module {
    val io = IO(new Bundle {
        val upstream = new MemoryInterface
        val lsu = Flipped(new MemoryInterface)
        val mmio = Flipped(new MemoryInterface)
    })

    val isMMIO = io.upstream.req.bits.addr(31) === 1.U

    io.lsu.req.valid := io.upstream.req.valid && !isMMIO
    io.lsu.req.bits := io.upstream.req.bits

    io.mmio.req.valid := io.upstream.req.valid && isMMIO
    io.mmio.req.bits := io.upstream.req.bits

    // Response Handling (1 cycle latency)
    val respValid = io.lsu.resp.valid || io.mmio.resp.valid
    val rawData = Mux(io.lsu.resp.valid, io.lsu.resp.bits, io.mmio.resp.bits)

    // Pipeline the request info for formatting
    val reqReg = RegNext(io.upstream.req.bits)
    val addrOffset = reqReg.addr(1, 0)
    
    // Process Data (Sign Extension)
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
    switch(reqReg.opWidth) {
        is(MemOpWidth.BYTE) {
            formattedData := Mux(reqReg.isUnsigned, rbyte, sextByte)
        }
        is(MemOpWidth.HALFWORD) {
            formattedData := Mux(reqReg.isUnsigned, rhalf, sextHalf)
        }
    }

    io.upstream.resp.valid := respValid
    io.upstream.resp.bits := formattedData
}
