package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures.{
    LoadStoreUnit,
    SequentialIssueBuffer,
    LoadStoreInfo,
    SequentialBufferEntry,
    MMIORouter,
    MemorySubsystem
}

// State maintained for a load in the writeback pipeline stage
class LoadStoreWBState extends Bundle {
    val valid = Bool()
    val pdst = UInt(PREG_WIDTH.W)
    val robTag = UInt(ROB_WIDTH.W)
    val resultPending = Bool()
    val pendingData = UInt(32.W)
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

        val robHead = Input(UInt(ROB_WIDTH.W))
    })

    val lsq = Module(new SequentialIssueBuffer(new LoadStoreInfo, 8))
    val memory = Module(new MemorySubsystem)

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
    val wbState = RegInit(0.U.asTypeOf(new LoadStoreWBState))

    // Flush WB state on flush
    when(io.flush.checkKilled(wbState.robTag)) {
        wbState.valid := false.B
        wbState.resultPending := false.B
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

    // Default Memory Request
    memory.io.req.valid := false.B
    memory.io.req.bits := DontCare

    // Load Section
    // Condition: LSQ has valid load, and WB stage is free (to accept result in next cycle).
    val canIssueLoad =
        headValid && isLoad && !wbState.valid && !wbState.resultPending

    when(canIssueLoad) {
        // Send request to Memory System
        memory.io.req.valid := true.B
        memory.io.req.bits.isLoad := true.B
        memory.io.req.bits.opWidth := opWidth
        memory.io.req.bits.isUnsigned := isUnsigned
        memory.io.req.bits.addr := effAddr
        memory.io.req.bits.data := 0.U // Unused for load
        memory.io.req.bits.targetReg := pdst

        // Advance State
        fireLoad := true.B

        // Reserve WB stage
        wbState.valid := true.B
        wbState.pdst := pdst
        wbState.robTag := robTag
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
        // Send request to Memory System
        memory.io.req.valid := true.B
        memory.io.req.bits.isLoad := false.B
        memory.io.req.bits.opWidth := opWidth
        memory.io.req.bits.isUnsigned := isUnsigned
        memory.io.req.bits.addr := effAddr
        memory.io.req.bits.data := storeData
        memory.io.req.bits.targetReg := 0.U

        // Advance State
        fireStore := true.B
    }

    // Dequeue Logic
    lsq.io.out.ready := fireLoad || fireStore

    // Default Memory Req
    when(!fireLoad && !fireStore) {
        memory.io.req.valid := false.B
        memory.io.req.bits := DontCare
    }

    // Writeback & Broadcast
    // Capture data from Memory System (1 cycle latency from Request)
    val lsuRespValid = memory.io.resp.valid
    val finalData = memory.io.resp.bits

    // Handle Pending Result (if broadcast was blocked)
    when(lsuRespValid) {
        // If we can't broadcast immediately, save it
        when(!io.broadcastOut.ready) {
            wbState.resultPending := true.B
            wbState.pendingData := finalData
        }
    }

    // Broadcast Arbitration
    // Priority: 1. Load Result (Pending or New) 2. Store Ready Notification

    io.broadcastOut.valid := false.B
    io.broadcastOut.bits := DontCare
    io.broadcastOut.bits.writeEn := false.B

    // Pending Load Result Logic
    when(wbState.resultPending) {
        io.broadcastOut.valid := true.B
        io.broadcastOut.bits.pdst := wbState.pdst
        io.broadcastOut.bits.robTag := wbState.robTag
        io.broadcastOut.bits.data := wbState.pendingData
        io.broadcastOut.bits.writeEn := true.B

        when(io.broadcastOut.ready) {
            wbState.resultPending := false.B
            wbState.valid := false.B // Free the stage
        }
    }.elsewhen(lsuRespValid) {
        val killed = io.flush.checkKilled(wbState.robTag)
        io.broadcastOut.valid := !killed
        io.broadcastOut.bits.pdst := wbState.pdst
        io.broadcastOut.bits.robTag := wbState.robTag
        io.broadcastOut.bits.data := finalData
        io.broadcastOut.bits.writeEn := true.B

        when(io.broadcastOut.ready || killed) {
            wbState.valid := false.B // Free the stage
        }
    }.elsewhen(readyToBroadcastStore) {
        io.broadcastOut.valid := true.B
        // For stores, we just broadcast completion tag to ROB. pdst is usually 0.
        io.broadcastOut.bits.pdst := pdst
        io.broadcastOut.bits.robTag := robTag
        io.broadcastOut.bits.writeEn := false.B
    }
}
