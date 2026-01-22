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
  * Integrates the Load/Store Queues and manages the interface to the memory
  * system. Handles out-of-order load/store execution, dependency checking, and
  * forwarding.
  *
  * Includes an Unordered Writeback Buffer to decouple memory responses from the
  * CDB.
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

    // Instantiate the Unordered Writeback Buffer (Depth 4)
    val wbBuffer = Module(new LoadWritebackBuffer(entries = 4))
    wbBuffer.io.flush := io.flush

    io.lsqCount.foreach(
      _ := lq.io.count.getOrElse(0.U) + sq.io.count.getOrElse(0.U)
    )

    // Wire up LQ and SQ
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

    val lq_entry = Wire(new LoadBufferEntry(3))
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
    val s3Ready = Wire(Bool())

    // Arbitrate between loads from LQ and stores from SQ for memory access
    val memArbiter = Module(new RRArbiter(new LoadStoreAction, 2))

    // Port 0: Loads from LQ
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

    // Port 1: Stores from SQ
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

    io.mem.req.valid := memArbiter.io.out.valid && s3Ready
    memArbiter.io.out.ready := io.mem.req.ready && s3Ready
    io.mem.req.bits := memArbiter.io.out.bits

    // Arbitrate CDB access
    val wbArbiter = Module(new RRArbiter(new BroadcastBundle, 3))
    io.broadcastOut <> wbArbiter.io.out

    // Port 0: Stores
    wbArbiter.io.in(0) <> sq.io.broadcastOut
    // Port 1: Loads (from WB Buffer)
    wbArbiter.io.in(1) <> wbBuffer.io.deq
    // Port 2: Forwarded Loads
    wbArbiter.io.in(2) <> lq.io.forwardOut

    // =========================================================================
    // 3. Stage 3: Memory Flight Tracker
    // =========================================================================
    val s3Valid = RegInit(false.B)         // Request in flight (Load OR Store)
    val s3IsLoad = RegInit(false.B)        // Type of request
    val s3Bits = Reg(new LoadBufferEntry(3))
    val s3IsDead = RegInit(false.B)

    val memReqFire = io.mem.req.fire

    // 3.1: Detect Flush (Only relevant for Loads, as Stores in SQ handle their own flush)
    val s3FlushHit = io.flush.checkKilled(s3Bits.robTag)
    when(s3FlushHit && s3Valid && s3IsLoad) {
        s3IsDead := true.B
    }

    // 3.2: Connect Memory Response -> Writeback Buffer
    // We only push to WB Buffer if it is a LOAD and it's valid/alive.
    val respValid = io.mem.resp.valid
    val isLoadResponse = s3Valid && s3IsLoad
    val isStoreResponse = s3Valid && !s3IsLoad
    val s3IsAlive = !s3IsDead && !s3FlushHit

    wbBuffer.io.enq.valid := isLoadResponse && s3IsAlive && respValid
    wbBuffer.io.enq.bits.pdst := s3Bits.pdst
    wbBuffer.io.enq.bits.robTag := s3Bits.robTag
    wbBuffer.io.enq.bits.data := io.mem.resp.bits
    wbBuffer.io.enq.bits.writeEn := true.B

    // 3.3: Backpressure Logic
    // - If it's a Load: Depend on WB Buffer ready (unless dead).
    // - If it's a Store: Always Ready (Sink Ack).
    // - If Idle/Spurious: Always Ready.
    io.mem.resp.ready := Mux(isLoadResponse && s3IsAlive, wbBuffer.io.enq.ready, true.B)

    // 3.4: Completion Logic
    // We are done if we received a handshake on the response channel.
    val s3Handshake = respValid && io.mem.resp.ready
    
    // Ready for NEW request if:
    // 1. Empty
    // 2. OR finishing current request this cycle
    s3Ready := !s3Valid || s3Handshake

    // 3.5: State Update
    when(s3Ready) {
        s3Valid := memReqFire // Latch true if we fired a request
        when(memReqFire) {
            s3IsLoad := io.mem.req.bits.isLoad
            s3Bits := lq.io.out.bits // Latch metadata (ignored for stores)
            s3IsDead := false.B
        }
    }

    // =========================================================================
    // 4. Profiling & Debug
    // =========================================================================
    io.busy.foreach(
      _ := lq.io.count.getOrElse(0.U) > 0.U ||
          sq.io.count.getOrElse(0.U) > 0.U ||
          s3Valid ||
          wbBuffer.io.deq.valid
    )

    val sqHead = sq.io.contents(sq.io.ptrs.head)
    val isStalledForCommit =
        sqHead.valid && sqHead.bits.broadcasted && !sqHead.bits.committed
    io.stallCommit.foreach(_ := isStalledForCommit)

    when(Elaboration.printOnMemAccess.B) {
        when(memReqFire && !io.mem.req.bits.isLoad) {
            printf(
              p"STORE_REQ: Addr=0x${Hexadecimal(io.mem.req.bits.addr)} Data=0x${Hexadecimal(io.mem.req.bits.data)}\n"
            )
        }
        when(wbBuffer.io.deq.fire) {
            printf(
              p"LOAD_WB: Data=0x${Hexadecimal(wbBuffer.io.deq.bits.data)} Tag=${wbBuffer.io.deq.bits.robTag}\n"
            )
        }
    }
}

class LoadWritebackBuffer(entries: Int) extends Module {
    val io = IO(new Bundle {
        val enq = Flipped(Decoupled(new BroadcastBundle))
        val deq = Decoupled(new BroadcastBundle)
        val flush = Input(new FlushBundle)
    })

    // Storage
    val regs = Reg(Vec(entries, new BroadcastBundle))
    val valids = RegInit(VecInit(Seq.fill(entries)(false.B)))

    // ---------------------------------------------------------------------------
    // Output (Dequeue) Logic -- Added FLOW
    // ---------------------------------------------------------------------------
    // Find valid entries that are NOT being flushed this cycle
    val validMask = VecInit(valids.zip(regs).map { case (v, r) =>
        v && !io.flush.checkKilled(r.robTag)
    })

    val hasValid = validMask.asUInt.orR
    val empty = !hasValid
    val deqSel = PriorityEncoder(validMask)

    // FLOW: If the buffer is empty, valid input data bypasses registers 
    // and goes directly to the output.
    io.deq.valid := Mux(empty, io.enq.valid, hasValid) && !io.flush.valid
    io.deq.bits  := Mux(empty, io.enq.bits,  regs(deqSel))

    // ---------------------------------------------------------------------------
    // Input (Enqueue) Logic -- Added PIPE
    // ---------------------------------------------------------------------------
    val freeMask = ~valids.asUInt
    val hasFree = freeMask.orR

    // PIPE: We can accept data if:
    // 1. We have a free slot in registers (hasFree)
    // 2. The buffer is full, but we are about to dequeue a stored entry (io.deq.ready && !empty)
    val willFreeSlot = io.deq.ready && !empty
    
    // Note: We maintain the original constraint that we don't enqueue during a flush
    io.enq.ready := hasFree || willFreeSlot

    // Select Write Destination:
    // If we have free slots, pick the lowest one.
    // If we are full (but Pipe is active), we write into the slot being dequeued (deqSel).
    val enqSel = Mux(hasFree, PriorityEncoder(freeMask), deqSel)

    // ---------------------------------------------------------------------------
    // State Updates
    // ---------------------------------------------------------------------------
    // Detect Flow condition: Data passed through, so we do not write to registers.
    val doFlow = empty && io.deq.ready

    for (i <- 0 until entries) {
        // We are dequeuing from registers if we fired and it wasn't a bypass flow
        val isDeq = io.deq.fire && !empty && (deqSel === i.U)
        
        // We are enquing to registers if we fired and it wasn't a bypass flow
        val isEnq = io.enq.fire && !doFlow && (enqSel === i.U)
        
        val isKilled = valids(i) && io.flush.checkKilled(regs(i).robTag)

        when(isKilled) {
            // Flush takes highest priority - invalidate immediately
            valids(i) := false.B
        }.elsewhen(isEnq) {
            // Write new data
            // In the "Pipe" case (Full + Deq + Enq), both isEnq and isDeq are true 
            // for the same index. This clause takes priority, effectively overwriting 
            // the old data with new data and keeping valid=true.
            regs(i) := io.enq.bits
            valids(i) := true.B
        }.elsewhen(isDeq) {
            // Clear valid bit after successful dequeue
            valids(i) := false.B
        }
    }
}