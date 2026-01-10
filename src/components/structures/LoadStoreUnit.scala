package components.structures

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

class LoadStoreUnit extends Module {
    val io = IO(new Bundle {
        val req = Flipped(Valid(new LoadStoreAction))
        val resp = Valid(UInt(32.W))
    })

    // 4KB Data Memory
    // We use a Vec of 4 bytes to support byte-level masking easily while maintaining 32-bit alignment
    val mem = SyncReadMem(Derived.MEM_SIZE / 4, Vec(4, UInt(8.W)))

    val req = io.req.bits
    val valid = io.req.valid

    // Word aligned address for memory index (4 bytes per index)
    val wordAddr = req.addr(MEM_WIDTH - 1, 2)

    // Write Data and Mask Setup
    val wdata = Wire(Vec(4, UInt(8.W)))
    val wmask = Wire(Vec(4, Bool()))

    // Initialize
    wdata := VecInit(Seq.fill(4)(0.U(8.W)))
    wmask := VecInit(Seq.fill(4)(false.B))

    // Calculate Write Mask and Data based on Address and OpWidth
    when(valid) {
        val addrOffset = req.addr(1, 0)
        when(req.opWidth === MemOpWidth.HALFWORD) {
            assert(addrOffset(0) === 0.U, "Halfword access must be 2-byte aligned")
        }.elsewhen(req.opWidth === MemOpWidth.WORD) {
            assert(addrOffset === 0.U, "Word access must be 4-byte aligned")
        }

        when(!req.isLoad) {
            switch(req.opWidth) {
                is(MemOpWidth.BYTE) {
                    wdata(addrOffset) := req.data(7, 0)
                    wmask(addrOffset) := true.B
                }
                is(MemOpWidth.HALFWORD) {
                    wdata(addrOffset) := req.data(7, 0)
                    wdata(addrOffset + 1.U) := req.data(15, 8)
                    wmask(addrOffset) := true.B
                    wmask(addrOffset + 1.U) := true.B
                }
                is(MemOpWidth.WORD) {
                    wdata(0) := req.data(7, 0)
                    wdata(1) := req.data(15, 8)
                    wdata(2) := req.data(23, 16)
                    wdata(3) := req.data(31, 24)
                    wmask := VecInit(Seq.fill(4)(true.B))
                }
            }
            mem.write(wordAddr, wdata, wmask)
        }
    }

    // Read Logic
    // Memory read is synchronous (1 cycle latency)
    val rdataVec = mem.read(wordAddr, valid && req.isLoad)

    // Pipeline the request info to match the data return
    val reqReg = RegNext(req)
    val validReg = RegNext(valid && req.isLoad)

    io.resp.valid := validReg

    // Reconstruct the word from bytes
    // rdataVec is index 0..3. asUInt packs 3##2##1##0.
    io.resp.bits := rdataVec.asUInt
}
