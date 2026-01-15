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

class Cache(conf: CacheConfig) extends Module {
    val io = IO(new Bundle {
        val port = new CachePort
        val dram = new SimpleMemIO(
          MemConfig(idWidth = 4, addrWidth = 32, dataWidth = (1 << conf.nCacheLineWidth) * 8)
        )
    })

    // --- Definitions ---
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

    // Use SyncReadMem with Vec for realistic synthesis mapping
    val mem = SyncReadMem(nSets, Vec(nBytes, UInt(8.W)))
    // val mem = Mem(nSets, UInt((nBytes * 8).W)) 
    val tags = SyncReadMem(nSets, new CacheEntry)

    // Simplified State Machine: Unified DRAM Access State
    val sIdle :: sTagCheck :: sDramAccess :: sReplayRead :: Nil = Enum(4)
    val state = RegInit(sIdle)
    
    val reqReg = Reg(new Bundle {
        val addr = UInt(32.W)
        val wdata = UInt(32.W)
        val wmask = UInt(4.W)
        val isWr = Bool()
    })

    // --- Scoreboard Registers for Parallel DRAM Access ---
    val sentWrite = RegInit(false.B)
    val sentRead  = RegInit(false.B)
    val gotWriteResp = RegInit(false.B)
    val gotReadResp  = RegInit(false.B)

    // --- Helper Signals ---
    val reg_index = reqReg.addr(conf.nCacheLineWidth + conf.nSetsWidth - 1, conf.nCacheLineWidth)
    val reg_tag = reqReg.addr(31, conf.nCacheLineWidth + conf.nSetsWidth)
    val reg_offset = reqReg.addr(conf.nCacheLineWidth - 1, 0)
    val port_index = io.port.addr(conf.nCacheLineWidth + conf.nSetsWidth - 1, conf.nCacheLineWidth)

    val isReplay = (state === sReplayRead)
    val read_index = Mux(isReplay, reg_index, port_index)
    val read_enable = (state === sIdle && io.port.valid) || isReplay

    // --- Cycle 0: Memory Access ---
    io.port.ready := (state === sIdle)

    val tagRead = tags.read(read_index, read_enable)
    // Read directly as Vec
    val dataRead = mem.read(read_index, read_enable)
    // val dataReadUInt = RegNext(mem(read_index))
    // val dataRead = dataReadUInt.asTypeOf(Vec(nBytes, UInt(8.W)))

    when(io.port.valid && io.port.ready) {
        reqReg.addr := io.port.addr
        reqReg.wdata := io.port.wdata
        reqReg.wmask := io.port.wmask
        reqReg.isWr := io.port.isWr
        state := sTagCheck
    }

    // --- DRAM Address/Data Holding ---
    val dramWriteBackData = Reg(Vec(nBytes, UInt(8.W)))
    val dramWriteBackAddr = Reg(UInt(32.W))
    val dramReadAddr = Reg(UInt(32.W))

    // --- Cycle 1+: State Machine ---
    io.port.respValid := false.B
    io.port.rdata := 0.U
    
    // DRAM Interface Defaults
    io.dram.req.valid := false.B
    io.dram.req.bits := DontCare
    io.dram.resp.ready := true.B 

    when(state === sTagCheck) {
        val hit = tagRead.valid && (tagRead.tag === reg_tag)

        when(hit) {
            when(reqReg.isWr) {
                // Write Hit Logic
                val wdataBytesSeq = Seq.tabulate(4)(i => reqReg.wdata(8 * i + 7, 8 * i))
                val repeatedData = VecInit(Seq.fill(nBytes / 4)(wdataBytesSeq).flatten)

                val fullMaskUInt = reqReg.wmask << reg_offset
                // CORRECT MASK logic
                val maskVec = VecInit(Seq.tabulate(nBytes)(i => fullMaskUInt(i)))
                
                if(Configurables.Elaboration.printOnMemAccess) {
                    printf(p"CACHE HIT WR: Addr=0x${Hexadecimal(reqReg.addr)} Idx=${reg_index} Mask=${Hexadecimal(fullMaskUInt)} MaskVec=${Hexadecimal(maskVec.asUInt)} Data=${Hexadecimal(reqReg.wdata)}\n")
                }

                // Standard Chisel Masked Write
                mem.write(reg_index, repeatedData, maskVec)

                val tag_update = Wire(new CacheEntry)
                tag_update := tagRead
                tag_update.dirty := true.B
                tags.write(reg_index, tag_update)
                
                io.port.respValid := true.B
                state := sIdle
            } .otherwise {
                // Read Hit Logic
                // We always return the word-aligned data to support the
                // byte/halfword selection logic in MemorySubsystem
                val aligned_offset = Cat(reg_offset(conf.nCacheLineWidth - 1, 2), 0.U(2.W))
                
                val rdata = Cat(
                    dataRead((aligned_offset + 3.U).asUInt),
                    dataRead((aligned_offset + 2.U).asUInt),
                    dataRead((aligned_offset + 1.U).asUInt),
                    dataRead(aligned_offset.asUInt)
                )
                io.port.rdata := rdata

                io.port.respValid := true.B
                state := sIdle
            }
        } .otherwise {
            // MISS detected
            val dirty = tagRead.dirty && tagRead.valid
            
            // Setup Addresses
            dramWriteBackAddr := Cat(tagRead.tag, reg_index, 0.U(conf.nCacheLineWidth.W))
            dramReadAddr := Cat(reg_tag, reg_index, 0.U(conf.nCacheLineWidth.W))
            
            // Write Back if dirty
            when(dirty) { dramWriteBackData := dataRead }
            sentWrite := !dirty
            gotWriteResp := !dirty
            
            // We always need to do the Read
            sentRead := false.B
            gotReadResp := false.B
            
            state := sDramAccess
        }
    }

    // --- PARALLEL DRAM ACCESS STATE ---
    when(state === sDramAccess) {
        
        // 1. REQUEST LOGIC (Prioritize Write if not sent)
        when(!sentWrite) {
            io.dram.req.valid := true.B
            io.dram.req.bits.id := WR_ID
            io.dram.req.bits.addr := dramWriteBackAddr
            io.dram.req.bits.data := dramWriteBackData.asUInt
            io.dram.req.bits.isWr := true.B
            io.dram.req.bits.mask := Fill(nBytes, 1.U(1.W))
            
            when(io.dram.req.ready) {
                sentWrite := true.B
            }
        } .elsewhen(!sentRead) {
            // Send Read Request only if Write is already sent (or wasn't needed)
            io.dram.req.valid := true.B
            io.dram.req.bits.id := RD_ID
            io.dram.req.bits.addr := dramReadAddr
            io.dram.req.bits.isWr := false.B
            io.dram.req.bits.mask := 0.U
            
            when(io.dram.req.ready) {
                sentRead := true.B
            }
        }

        // 2. RESPONSE LOGIC (Always listening)
        io.dram.resp.ready := true.B
        
        when(io.dram.resp.valid) {
            // Handle Write Acknowledgement
            when(io.dram.resp.bits.id === WR_ID) {
                gotWriteResp := true.B
            }
            // Handle Read Data Return
            when(io.dram.resp.bits.id === RD_ID) {
                val respDataVec = VecInit(Seq.tabulate(nBytes)(i => io.dram.resp.bits.data(8 * i + 7, 8 * i)))
                
                // Write to Data RAM
                val allOnesMask = VecInit(Seq.fill(nBytes)(true.B))
                mem.write(reg_index, respDataVec, allOnesMask)
                
                // Write to Tag RAM
                val newTag = Wire(new CacheEntry)
                newTag.valid := true.B
                newTag.tag := reg_tag
                newTag.dirty := false.B
                tags.write(reg_index, newTag)
                
                gotReadResp := true.B
            }
        }

        // Transition only when both operations are done
        when(gotWriteResp && gotReadResp) {
            state := sReplayRead
        }
    }

    when(state === sReplayRead) {
        state := sTagCheck
    }
}