package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.{
    LoadStoreUnit,
    SequentialIssueBuffer,
    LoadStoreInfo,
    SequentialBufferEntry
}
import components.structures.LoadStoreUnit

// The Load Store Action is what actually drives the memory system
class LoadStoreAction extends Bundle {
    val isLoad = Bool()
    val opWidth = MemOpWidth()
    val addr = UInt(MEM_WIDTH.W)
    val data = UInt(32.W)
    val targetReg = UInt(PREG_WIDTH.W)
}

// Wiring:
// - Load Store Queue -> Load Store Operator -> Load Store Executor
// Queue top: READ on ready, WRITE on commit
// Write behavior: When all ready and in front, broadcast it as ready, and dequeue it on commit.
// On receiving commit, send action to Operator and ensure that execution follows in the next cycle immediately, so that the PRegs don't have time to be re-written (should take at least 3 cycles).
class LoadStoreAdaptor extends Module {
    val io = IO(new Bundle {
        // Issue Interface (from Dispatch)
        val issueIn =
            Flipped(Decoupled(new SequentialBufferEntry(new LoadStoreInfo)))

        // Broadcast Interface (Output) - to Wakeup/ROB
        val broadcastOut = Decoupled(new BroadcastBundle)

        // Broadcast Interface (Input) - for waking up operands in Queue
        val broadcastIn = Input(Valid(new BroadcastBundle))

        // PRF Interface - Read Operands
        val prfRead = new PRFReadBundle

        // ROB Interface and Flush
        val flush = Input(new FlushBundle)
    })

    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 8))
    val lsu = Module(new LoadStoreUnit)

    lsq.io.in <> io.issueIn
    lsq.io.broadcast := io.broadcastIn
    lsq.io.flush := io.flush

    io.prfRead.addr1 := lsq.io.out.bits.src1 // Base Address
    io.prfRead.addr2 := lsq.io.out.bits.src2 // Store Data (or unused for Load)

    // Helper signals from Head
    val head = lsq.io.out.bits
    val headValid = lsq.io.out.valid
    // src1Ready and src2Ready are handled inside SequentialIssueBuffer's validity logic.

    val info = head.info
    val isLoad = !info.isStore
    val isStore = info.isStore
    val opWidth = info.opWidth
    val imm = info.imm
    val robTag = head.robTag
    val pdst = head.pdst
    val isUnsigned = info.isUnsigned

    // Calculate Effective Address
    val baseAddr = io.prfRead.data1
    val storeData = io.prfRead.data2
    val effAddr = (baseAddr.asSInt + imm.asSInt).asUInt

    // Regs for Writeback Stage
    val wbValid = RegInit(false.B)
    val wbPdst = RegInit(0.U(PREG_WIDTH.W))
    val wbTag = RegInit(0.U(ROB_WIDTH.W))
    val wbUnsigned = RegInit(false.B)
    val wbOpWidth = RegInit(MemOpWidth.WORD)
    val wbAddrOffset = RegInit(0.U(2.W))
    val wbResultPending = RegInit(false.B)
    val wbPendingData = Reg(UInt(32.W))

    // Flush WB state on flush
    when(io.flush.checkKilled(wbTag)) {
        wbValid := false.B
        wbResultPending := false.B
    }

    // Regs for Store status
    val storeReadyBroadcasted = RegInit(false.B)
    // Clear when we advance queue or flush
    when(lsq.io.out.fire || io.flush.valid) {
        storeReadyBroadcasted := false.B
    }

    // Handshake Signals
    val fireLoad = WireInit(false.B)
    val fireStore = WireInit(false.B)
    val readyToBroadcastStore = WireInit(false.B)

    // Load Section
    // Condition: LSQ has valid load, and WB stage is free (to accept result in next cycle).
    val canIssueLoad = headValid && isLoad && !wbValid && !wbResultPending

    when(canIssueLoad) {
        // Send request to LSU
        lsu.io.req.valid := true.B
        lsu.io.req.bits.isLoad := true.B
        lsu.io.req.bits.opWidth := opWidth
        lsu.io.req.bits.addr := effAddr
        lsu.io.req.bits.data := 0.U // Unused for load
        lsu.io.req.bits.targetReg := pdst

        // Advance State
        fireLoad := true.B

        // Reserve WB stage
        wbValid := true.B
        wbPdst := pdst
        wbTag := robTag
        wbUnsigned := isUnsigned
        wbOpWidth := opWidth
        wbAddrOffset := effAddr(1, 0)
    }

    // Store Section
    // 1. Notify ROB that store is "Ready" (Operands available)
    when(headValid && isStore && !storeReadyBroadcasted) {
        readyToBroadcastStore := true.B
    }

    when(readyToBroadcastStore && io.broadcastOut.ready) {
        storeReadyBroadcasted := true.B
    }

    // 2. Commit -> Write to Memory
    // We wait until the ROB head pointer matches our ROB tag
    val cancommitStore = headValid && isStore && (io.flush.robHead === robTag)

    when(cancommitStore) {
        // Send request to LSU
        lsu.io.req.valid := true.B
        lsu.io.req.bits.isLoad := false.B
        lsu.io.req.bits.opWidth := opWidth
        lsu.io.req.bits.addr := effAddr
        lsu.io.req.bits.data := storeData
        lsu.io.req.bits.targetReg := 0.U

        // Advance State
        fireStore := true.B
    }

    // Dequeue Logic
    lsq.io.out.ready := fireLoad || fireStore

    // Default LSU Req
    when(!fireLoad && !fireStore) {
        lsu.io.req.valid := false.B
        lsu.io.req.bits := DontCare
    }

    // Writeback & Broadcast
    // Capture data from LSU (1 cycle latency from Request)
    val lsuRespValid = lsu.io.resp.valid
    val lsuData = lsu.io.resp.bits

    // Process Data (Sign Extension)
    val rbyte = MuxLookup(wbAddrOffset, 0.U)(
      Seq(
        0.U -> lsuData(7, 0),
        1.U -> lsuData(15, 8),
        2.U -> lsuData(23, 16),
        3.U -> lsuData(31, 24)
      )
    )
    val rhalf = Mux(wbAddrOffset(1) === 0.U, lsuData(15, 0), lsuData(31, 16))
    val sextByte = Cat(Fill(24, rbyte(7)), rbyte)
    val sextHalf = Cat(Fill(16, rhalf(15)), rhalf)

    val finalData = Wire(UInt(32.W))
    finalData := lsuData
    switch(wbOpWidth) {
        is(MemOpWidth.BYTE) { finalData := Mux(wbUnsigned, rbyte, sextByte) }
        is(MemOpWidth.HALFWORD) {
            finalData := Mux(wbUnsigned, rhalf, sextHalf)
        }
    }

    // Handle Pending Result (if broadcast was blocked)
    when(lsuRespValid) {
        // If we can't broadcast immediately, save it
        when(!io.broadcastOut.ready) {
            wbResultPending := true.B
            wbPendingData := finalData
        }
    }

    // Broadcast Arbitration
    // Priority: 1. Load Result (Pending or New) 2. Store Ready Notification

    io.broadcastOut.valid := false.B
    io.broadcastOut.bits := DontCare
    io.broadcastOut.bits.writeEn := false.B

    // Pending Load Result Logic
    when(wbResultPending) {
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := wbPdst
        io.broadcastOut.bits.robTag := wbTag
        io.broadcastOut.bits.data := wbPendingData
        io.broadcastOut.bits.writeEn := true.B

        when(io.broadcastOut.ready) {
            wbResultPending := false.B
            wbValid := false.B // Free the stage
        }
    }.elsewhen(lsuRespValid) {
        val killed = io.flush.checkKilled(wbTag)
        io.broadcastOut.valid := !killed
        io.broadcastOut.bits.pdst := wbPdst
        io.broadcastOut.bits.robTag := wbTag
        io.broadcastOut.bits.data := finalData
        io.broadcastOut.bits.writeEn := true.B

        when(io.broadcastOut.ready || killed) {
            wbValid := false.B // Free the stage
        }
    }.elsewhen(readyToBroadcastStore) {
        io.broadcastOut.valid := true.B
        // For stores, we just broadcast completion tag to ROB. pdst is usually 0.
        io.broadcastOut.bits.pdst := pdst
        io.broadcastOut.bits.robTag := robTag
        io.broadcastOut.bits.writeEn := false.B
    }
}
