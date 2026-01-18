package components.backend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import components.structures._
import utility.CycleAwareModule
import components.memory._

/** Load Store Adaptor
  *
  * Integrates the Load/Store Queues and manages the interface to the memory system.
  *
  * Handles out-of-order load/store execution, dependency checking, and forwarding.
  */
class LoadStoreAdaptor extends CycleAwareModule {
    val io = IO(new Bundle {
        val issueIn =
            Flipped(Decoupled(new SequentialBufferEntry(new LoadStoreInfo)))
        val broadcastOut = Decoupled(new BroadcastBundle)
        val broadcastIn = Input(Valid(new BroadcastBundle))
        val prfRead = new PRFReadBundle
        val flush = Input(new FlushBundle)
        val robHead = Input(UInt(ROB_WIDTH.W))

        val mem = new MemoryRequest
        val busy =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
        val stallCommit =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
        val lsqCount =
            if (common.Configurables.Profiling.Utilization)
                Some(Output(UInt(log2Ceil(17).W))) // 8 LQ + 8 SQ
            else None
    })

    // Instantiate Load and Store Queues
    val lq = Module(new LoadBuffer(numEntriesLQ = 8, numEntriesSQ = 8))
    val sq = Module(new StoreQueue(entries = 8))

    io.lsqCount.foreach(_ := lq.io.count.getOrElse(0.U) + sq.io.count.getOrElse(0.U))

    // Wire up LQ and SQ to each other and global signals
    lq.io.SQcontents := sq.io.contents
    lq.io.SQptrs := sq.io.ptrs

    lq.io.broadcastIn := io.broadcastIn
    sq.io.broadcastIn := io.broadcastIn

    lq.io.flush := io.flush
    sq.io.flush := io.flush

    sq.io.robHead := io.robHead

    // =========================================================================
    // 1. Input Stage: Dispatch into LQ/SQ
    // =========================================================================
    val isLoad = !io.issueIn.bits.info.isStore
    val isStore = io.issueIn.bits.info.isStore

    lq.io.in.valid := false.B
    sq.io.in.valid := false.B
    io.issueIn.ready := Mux(isLoad, lq.io.in.ready, sq.io.in.ready)

    io.prfRead.addr1 := io.issueIn.bits.src1
    io.prfRead.addr2 := io.issueIn.bits.src2

    val src1Ready = Wire(Bool())
    val src2Ready = Wire(Bool())
    val src1Data = Wire(UInt(32.W))
    val src2Data = Wire(UInt(32.W))

    src1Ready := io.issueIn.bits.src1Ready
    src2Ready := io.issueIn.bits.src2Ready
    src1Data := io.prfRead.data1
    src2Data := io.prfRead.data2

    when(io.broadcastIn.valid && io.broadcastIn.bits.writeEn) {
        val bcastTag = io.broadcastIn.bits.pdst
        when(io.issueIn.bits.src1 === bcastTag && !io.issueIn.bits.src1Ready) {
            src1Ready := true.B
            src1Data := io.broadcastIn.bits.data
        }
        when(io.issueIn.bits.src2 === bcastTag && !io.issueIn.bits.src2Ready) {
            src2Ready := true.B
            src2Data := io.broadcastIn.bits.data
        }
    }


    // Convert incoming instruction to a LoadBufferEntry
    val lq_entry = Wire(new LoadBufferEntry(3)) // log2Ceil(8)
    lq_entry := DontCare
    lq_entry.robTag := io.issueIn.bits.robTag
    lq_entry.pdst := io.issueIn.bits.pdst
    lq_entry.addrPreg := io.issueIn.bits.src1
    lq_entry.addrReady := src1Ready
    lq_entry.addrVal := src1Data
    lq_entry.imm := io.issueIn.bits.info.imm
    lq_entry.opWidth := io.issueIn.bits.info.opWidth
    lq_entry.isUnsigned := io.issueIn.bits.info.isUnsigned
    lq.io.in.bits := lq_entry

    // Convert incoming instruction to a StoreQueueEntry
    val sq_entry = Wire(new StoreQueueEntry)
    sq_entry := DontCare
    sq_entry.robTag := io.issueIn.bits.robTag
    sq_entry.addrPreg := io.issueIn.bits.src1
    sq_entry.addrReady := src1Ready
    sq_entry.addrVal := src1Data
    sq_entry.dataPreg := io.issueIn.bits.src2
    sq_entry.dataReady := src2Ready
    sq_entry.dataVal := src2Data
    sq_entry.imm := io.issueIn.bits.info.imm
    sq_entry.opWidth := io.issueIn.bits.info.opWidth
    sq_entry.isUnsigned := io.issueIn.bits.info.isUnsigned
    sq.io.in.bits := sq_entry

    when(io.issueIn.valid) {
        when(isLoad) {
            lq.io.in.valid := true.B
        }.otherwise {
            sq.io.in.valid := true.B
        }
    }

    // =========================================================================
    // 2. Memory & Writeback Arbitration
    // =========================================================================
    // Stage 3 is repurposed to track one in-flight memory request to the blocking cache
    val s3Ready = Wire(Bool())

    // Arbitrate between loads from LQ and stores from SQ for memory access
    val memArbiter = Module(new RRArbiter(new LoadStoreAction, 2))

    // Port 0: Loads from LQ that need to access cache
    memArbiter.io.in(0).valid := lq.io.out.valid
    lq.io.out.ready := memArbiter.io.in(0).ready
    
    val loadAction = Wire(new LoadStoreAction)
    loadAction := DontCare
    loadAction.addr := lq.io.out.bits.addrResolved
    loadAction.data := 0.U
    loadAction.isLoad := true.B
    loadAction.opWidth := lq.io.out.bits.opWidth
    loadAction.isUnsigned := lq.io.out.bits.isUnsigned
    loadAction.targetReg := lq.io.out.bits.pdst
    memArbiter.io.in(0).bits := loadAction

    // Port 1: Stores from SQ that are ready to commit to memory
    memArbiter.io.in(1).valid := sq.io.out.valid
    sq.io.out.ready := memArbiter.io.in(1).ready
    
    val storeAction = Wire(new LoadStoreAction)
    storeAction := DontCare
    storeAction.addr := sq.io.out.bits.addrResolved
    storeAction.data := sq.io.out.bits.dataVal
    storeAction.isLoad := false.B
    storeAction.opWidth := sq.io.out.bits.opWidth
    storeAction.isUnsigned := DontCare
    storeAction.targetReg := 0.U
    memArbiter.io.in(1).bits := storeAction

    // Connect arbiter to memory system, gated by S3 tracker readiness
    io.mem.req.valid := memArbiter.io.out.valid && s3Ready
    memArbiter.io.out.ready := io.mem.req.ready && s3Ready
    io.mem.req.bits := memArbiter.io.out.bits

    // Arbitrate between all sources that need to broadcast on the CDB
    val wbArbiter = Module(new RRArbiter(new BroadcastBundle, 3))
    io.broadcastOut <> wbArbiter.io.out

    // Port 0: Store-ready broadcast from SQ
    wbArbiter.io.in(0) <> sq.io.broadcastOut

    // Port 2: Forwarded load data from LQ
    wbArbiter.io.in(2) <> lq.io.forwardOut

    // =========================================================================
    // 3. Stage 3: Memory Flight Tracker (Preserved Logic)
    // =========================================================================
    val s3Valid = RegInit(false.B)
    val s3Bits = Reg(new LoadBufferEntry(3)) // Holds info about the in-flight load
    val s3Data = Reg(UInt(32.W))
    val s3AddrDebug = Reg(UInt(32.W))
    val s3WaitingResp = RegInit(false.B)
    val s3IsDead = RegInit(false.B)

    // A memory request fires, only loads will occupy the S3 tracker stage
    val memReqFire = io.mem.req.fire
    val isLoadMemReq = memReqFire && io.mem.req.bits.isLoad

    // 3.1: Detect Flush
    val s3FlushHit = io.flush.checkKilled(s3Bits.robTag)
    when(s3FlushHit && s3Valid) {
        s3IsDead := true.B
    }

    // 3.2: Handle Response from Memory
    when(io.mem.resp.valid) {
        s3Data := io.mem.resp.bits
        s3WaitingResp := false.B
    }
    io.mem.resp.ready := true.B

    // 3.3: Completion Logic
    val s3IsPending = s3WaitingResp
    val s3MemDone = s3Valid && !s3IsPending

    // 3.4: Writeback Arbiter (Port 1: Loads from memory)
    wbArbiter.io.in(1).valid := s3MemDone && !s3IsDead && !s3FlushHit
    wbArbiter.io.in(1).bits.pdst := s3Bits.pdst
    wbArbiter.io.in(1).bits.robTag := s3Bits.robTag
    wbArbiter.io.in(1).bits.data := s3Data
    wbArbiter.io.in(1).bits.writeEn := true.B

    // 3.5: Fire/Drain Logic
    // S3 can be emptied if the load finished writeback or was killed
    val s3Fire = !s3IsPending && (
      (s3MemDone && wbArbiter.io.in(1).ready) || // Normal Load finished
      (s3Valid && (s3IsDead || s3FlushHit))      // Killed Load is drained
    )
    s3Ready := !s3Valid || s3Fire

    when(s3Ready) {
        val incomingValid = isLoadMemReq
        s3Valid := incomingValid
        when(incomingValid) {
            s3Bits := lq.io.out.bits
            s3AddrDebug := lq.io.out.bits.addrResolved
        }
        s3WaitingResp := incomingValid
        s3IsDead := false.B // Reset for new instruction
    }

    // =========================================================================
    // 4. Profiling & Debug
    // =========================================================================
    io.busy.foreach(_ := lq.io.count.getOrElse(0.U) > 0.U || sq.io.count.getOrElse(0.U) > 0.U || s3Valid)

    // A store is stalled if it is ready and at the head of the ROB, but not yet committed
    val sqHead = sq.io.contents(sq.io.ptrs.head)
    val isStalledForCommit = sqHead.valid && sqHead.bits.broadcasted && !sqHead.bits.committed
    io.stallCommit.foreach(_ := isStalledForCommit)

    when(Elaboration.printOnMemAccess.B) {
        when(memReqFire && !io.mem.req.bits.isLoad) {
            printf(
              p"STORE_REQ: Addr=0x${Hexadecimal(io.mem.req.bits.addr)} Data=0x${Hexadecimal(io.mem.req.bits.data)}\n"
            )
        }
        when(wbArbiter.io.in(1).fire) {
            printf(
              p"LOAD_WB: Addr=0x${Hexadecimal(s3AddrDebug)} Data=0x${Hexadecimal(wbArbiter.io.in(1).bits.data)}\n"
            )
        }
        when(memReqFire && !io.mem.req.bits.isLoad && s3Ready) { // Store Ack
             printf(p"STORE_ACK: Addr=0x${Hexadecimal(io.mem.req.bits.addr)}\n")
        }
    }
}