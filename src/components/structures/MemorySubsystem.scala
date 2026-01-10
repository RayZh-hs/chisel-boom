package components.structures

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

class MemorySubsystem extends Module {
    val io = IO(new Bundle {
        val req = Flipped(Valid(new LoadStoreAction))
        val resp = Valid(UInt(32.W))
    })

    val lsu = Module(new LoadStoreUnit)
    val mmio = Module(new MMIORouter)

    val isMMIO = io.req.bits.addr(31) === 1.U

    lsu.io.req.valid := io.req.valid && !isMMIO
    lsu.io.req.bits := io.req.bits

    mmio.io.req.valid := io.req.valid && isMMIO
    mmio.io.req.bits := io.req.bits

    // Response Handling (1 cycle latency)
    val respValid = lsu.io.resp.valid || mmio.io.resp.valid
    val rawData = Mux(lsu.io.resp.valid, lsu.io.resp.bits, mmio.io.resp.bits)

    // Pipeline the request info for formatting
    val reqReg = RegNext(io.req.bits)
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

    io.resp.valid := respValid
    io.resp.bits := formattedData
}
