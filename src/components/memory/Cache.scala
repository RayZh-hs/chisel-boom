package components.memory

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

case class CacheConfig(
    nSetsWidth: Int,
    nCacheLineWidth: Int,
    idOffset: Int = 0
)

class CachePort extends Bundle {
    val addr = Input(UInt(32.W))
    val wdata = Input(UInt(32.W))
    val wmask = Input(UInt(4.W))
    val isWr = Input(Bool())
    val valid = Input(Bool())
    val rdata = Output(UInt(32.W))
    val respValid = Output(Bool())
    val ready = Output(Bool())
}

class CacheEvents extends Bundle {
    val hit = Output(Bool())
    val miss = Output(Bool())
}

/** A simple direct-mapped write-back cache
  *
  * @param conf
  *   Cache configuration parameters
  */
class Cache(conf: CacheConfig) extends Module {
    val io = IO(new Bundle {
        val port = new CachePort
        val dram = new SimpleMemIO(
          MemConfig(
            idWidth = 4,
            addrWidth = 32,
            dataWidth = (1 << conf.nCacheLineWidth) * 8
          )
        )
        val events = new CacheEvents
    })

    val nSets = 1 << conf.nSetsWidth
    val nBytes = 1 << conf.nCacheLineWidth
    val tagWidth = 32 - conf.nSetsWidth - conf.nCacheLineWidth

    val WR_ID = (0 + conf.idOffset).U(4.W)
    val RD_ID = (1 + conf.idOffset).U(4.W)

    class CacheEntry extends Bundle {
        val valid = Bool()
        val tag = UInt(tagWidth.W)
        val dirty = Bool()
    }

    val mem = SyncReadMem(nSets, Vec(nBytes, UInt(8.W)))
    val tags = SyncReadMem(nSets, new CacheEntry)

    val sIdle :: sTagCheck :: sDramAccess :: sReplayRead :: Nil = Enum(4)
    val state = RegInit(sIdle)

    val reqReg = Reg(new Bundle {
        val addr = UInt(32.W)
        val wdata = UInt(32.W)
        val wmask = UInt(4.W)
        val isWr = Bool()
    })

    val sentWrite = RegInit(false.B)
    val sentRead = RegInit(false.B)
    val gotWriteResp = RegInit(false.B)
    val gotReadResp = RegInit(false.B)
    val isRefill = RegInit(false.B)

    io.events.hit := false.B
    io.events.miss := false.B

    // Signals for Logic
    val reg_index = reqReg.addr(
      conf.nCacheLineWidth + conf.nSetsWidth - 1,
      conf.nCacheLineWidth
    )
    val reg_tag = reqReg.addr(31, conf.nCacheLineWidth + conf.nSetsWidth)
    val reg_offset = reqReg.addr(conf.nCacheLineWidth - 1, 0)
    val port_index = io.port.addr(
      conf.nCacheLineWidth + conf.nSetsWidth - 1,
      conf.nCacheLineWidth
    )

    val isReplay = (state === sReplayRead)
    val read_index = Mux(isReplay, reg_index, port_index)
    val read_enable = (io.port.valid && io.port.ready) || isReplay

    // Single Read Calls
    val tagRead = tags.read(read_index, read_enable)
    val dataRead = mem.read(read_index, read_enable)

    // Write Wires
    val mem_wen = WireInit(false.B)
    val mem_wmask = Wire(Vec(nBytes, Bool()))
    val mem_wdata = Wire(Vec(nBytes, UInt(8.W)))
    mem_wmask := VecInit(Seq.fill(nBytes)(false.B))
    mem_wdata := VecInit(Seq.fill(nBytes)(0.U(8.W)))

    val tags_wen = WireInit(false.B)
    val tags_wdata = Wire(new CacheEntry)
    tags_wdata := DontCare

    // Logic
    val hitAndRead = Wire(Bool())
    val reqFire = io.port.valid && io.port.ready
    hitAndRead := false.B

    io.port.ready := (state === sIdle) || hitAndRead

    when(io.port.valid && io.port.ready) {
        reqReg.addr := io.port.addr
        reqReg.wdata := io.port.wdata
        reqReg.wmask := io.port.wmask
        reqReg.isWr := io.port.isWr
        isRefill := false.B
        state := sTagCheck
    }

    val dramWriteBackData = Reg(Vec(nBytes, UInt(8.W)))
    val dramWriteBackAddr = Reg(UInt(32.W))
    val dramReadAddr = Reg(UInt(32.W))

    io.port.respValid := false.B
    io.port.rdata := 0.U
    io.dram.req.valid := false.B
    io.dram.req.bits := DontCare
    io.dram.resp.ready := true.B

    when(state === sTagCheck) {
        val hit = tagRead.valid && (tagRead.tag === reg_tag)
        io.events.hit := hit && !isRefill
        io.events.miss := !hit

        when(hit) {
            when(reqReg.isWr) {
                val wdataBytesSeq =
                    Seq.tabulate(4)(i => reqReg.wdata(8 * i + 7, 8 * i))
                mem_wdata := VecInit(
                  Seq.fill(nBytes / 4)(wdataBytesSeq).flatten
                )
                val fullMaskUInt = reqReg.wmask << reg_offset
                mem_wmask := VecInit(Seq.tabulate(nBytes)(i => fullMaskUInt(i)))
                mem_wen := true.B

                tags_wdata := tagRead
                tags_wdata.dirty := true.B
                tags_wen := true.B

                io.port.respValid := true.B
                state := sIdle
            }.otherwise {
                val aligned_offset =
                    Cat(reg_offset(conf.nCacheLineWidth - 1, 2), 0.U(2.W))
                io.port.rdata := Cat(
                  dataRead((aligned_offset + 3.U).asUInt),
                  dataRead((aligned_offset + 2.U).asUInt),
                  dataRead((aligned_offset + 1.U).asUInt),
                  dataRead(aligned_offset.asUInt)
                )
                io.port.respValid := true.B
                when(!reqFire){
                    state := sIdle
                }
                hitAndRead := true.B
            }
        }.otherwise {
            val dirty = tagRead.dirty && tagRead.valid
            dramWriteBackAddr := Cat(
              tagRead.tag,
              reg_index,
              0.U(conf.nCacheLineWidth.W)
            )
            dramReadAddr := Cat(reg_tag, reg_index, 0.U(conf.nCacheLineWidth.W))
            when(dirty) { dramWriteBackData := dataRead }
            sentWrite := !dirty
            gotWriteResp := !dirty
            sentRead := false.B
            gotReadResp := false.B
            isRefill := true.B
            state := sDramAccess
        }
    }

    when(state === sDramAccess) {
        when(!sentWrite) {
            io.dram.req.valid := true.B
            io.dram.req.bits.id := WR_ID
            io.dram.req.bits.addr := dramWriteBackAddr
            io.dram.req.bits.data := dramWriteBackData.asUInt
            io.dram.req.bits.isWr := true.B
            io.dram.req.bits.mask := Fill(nBytes, 1.U(1.W))
            when(io.dram.req.ready) { sentWrite := true.B }
        }.elsewhen(!sentRead) {
            io.dram.req.valid := true.B
            io.dram.req.bits.id := RD_ID
            io.dram.req.bits.addr := dramReadAddr
            io.dram.req.bits.isWr := false.B
            io.dram.req.bits.mask := 0.U
            when(io.dram.req.ready) { sentRead := true.B }
        }

        when(io.dram.resp.valid) {
            when(io.dram.resp.bits.id === WR_ID) { gotWriteResp := true.B }
            when(io.dram.resp.bits.id === RD_ID) {
                mem_wdata := VecInit(
                  Seq.tabulate(nBytes)(i =>
                      io.dram.resp.bits.data(8 * i + 7, 8 * i)
                  )
                )
                mem_wmask := VecInit(Seq.fill(nBytes)(true.B))
                mem_wen := true.B

                tags_wdata.valid := true.B
                tags_wdata.tag := reg_tag
                tags_wdata.dirty := false.B
                tags_wen := true.B
                gotReadResp := true.B
            }
        }
        when(gotWriteResp && gotReadResp) { state := sReplayRead }
    }

    when(state === sReplayRead) { state := sTagCheck }

    // Single Write Calls
    when(mem_wen) { mem.write(reg_index, mem_wdata, mem_wmask) }
    when(tags_wen) { tags.write(reg_index, tags_wdata) }
}
