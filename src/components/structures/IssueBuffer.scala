package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._
import common._
import utility._

class ALUInfo extends Bundle {
    val aluOp = ALUOpType()
}

class MultInfo extends Bundle {
    val multOp = MultOpType()
}

class BRUInfo extends Bundle {
    val bruOp = BRUOpType()
    val cmpOp = CmpOpType()
    val pc = UInt(32.W)
    val predict = Bool()
    val predictedTarget = UInt(32.W)
    val rasSP = UInt(RAS_WIDTH.W)
}

class IssueBufferEntry[T <: Data](gen: T) extends Bundle {
    val robTag = UInt(ROB_WIDTH.W)
    val pdst = UInt(PREG_WIDTH.W)
    val src1Ready = Bool()
    val src2Ready = Bool()
    val src1 = UInt(PREG_WIDTH.W)
    val src2 = UInt(PREG_WIDTH.W)
    val imm = UInt(32.W)
    val useImm = Bool()
    val info = gen.cloneType

    val pc =
        if (Configurables.Elaboration.pcInIssueBuffer) Some(UInt(32.W))
        else None
}

/** Issue Buffer
  *
  * Fully parametized Issue Buffer supporting any type of info bundle.
  *
  * @param gen
  *   The generator of the info bundle
  * @param numEntries
  *   Number of entries in the Issue Buffer
  * @param name
  *   Name of the Issue Buffer (for debugging)
  */
class IssueBuffer[T <: Data](gen: T, entries: Int, name: String)
    extends CycleAwareModule with NonOrderedLogic {
    def numEntries = entries
    
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new IssueBufferEntry(gen)))
        val broadcast = Input(Valid(new BroadcastBundle()))
        val out = Decoupled(new IssueBufferEntry(gen))
        val flush = Input(new FlushBundle)
        
        // Profiling (Optional)
        val stallOperands = if (Configurables.Profiling.Utilization) Some(Output(Bool())) else None
        val stallPort     = if (Configurables.Profiling.Utilization) Some(Output(Bool())) else None
        val count         = if (Configurables.Profiling.Utilization) Some(Output(UInt(log2Ceil(numEntries + 1).W))) else None
        val waitDepCount  = if (Configurables.Profiling.Utilization) Some(Output(UInt(log2Ceil(numEntries + 1).W))) else None
    })

    // 1. Data Storage (Control state is now in Trait)
    val buffer = Reg(Vec(numEntries, new IssueBufferEntry(gen)))

    // 2. Profiling Connection
    io.count.foreach(_ := getCount)
    io.waitDepCount.foreach { c =>
        c := PopCount(valids.zip(buffer).map { case (v, b) =>
            v && (!b.src1Ready || !b.src2Ready)
        })
    }

    // 3. Flush Logic
    val killMask = Wire(Vec(numEntries, Bool()))
    for (i <- 0 until numEntries) {
        killMask(i) := valids(i) && io.flush.checkKilled(buffer(i).robTag)
    }
    when(io.flush.valid) {
        onFlush(killMask)
    }

    // 4. Wakeup Logic (Broadcast)
    when(io.broadcast.valid) {
        val resPdst = io.broadcast.bits.pdst
        for (i <- 0 until numEntries) {
            when(valids(i)) {
                when(buffer(i).src1 === resPdst) { buffer(i).src1Ready := true.B }
                when(buffer(i).src2 === resPdst) { buffer(i).src2Ready := true.B }
            }
        }
    }

    // 5. Enqueue Logic
    io.in.ready := !isFull && !io.flush.valid // 'isFull' from Trait

    when(io.in.fire) {
        val idx = enqueuePtr // 'enqueuePtr' from Trait (logic to find free slot)
        
        // --- Data Setup ---
        val entry = io.in.bits
        val updatedEntry = Wire(new IssueBufferEntry(gen))
        updatedEntry := entry
        
        // Forwarding check
        val broadcastMatch1 = io.broadcast.valid && (entry.src1 === io.broadcast.bits.pdst)
        val broadcastMatch2 = io.broadcast.valid && (entry.src2 === io.broadcast.bits.pdst)
        when(broadcastMatch1) { updatedEntry.src1Ready := true.B }
        when(broadcastMatch2) { updatedEntry.src2Ready := true.B }

        // --- Write ---
        buffer(idx) := updatedEntry
        onEnqueue() // Mark as valid in Trait

        printf(p"${name}: Enq robTag=${updatedEntry.robTag} idx=$idx\n")
    }

    // 6. Issue Logic
    val readyCandidates = Wire(Vec(numEntries, Bool()))
    for (i <- 0 until numEntries) {
        readyCandidates(i) := valids(i) && buffer(i).src1Ready && buffer(i).src2Ready
    }

    // Use Trait helper to calculate Round Robin selection
    val (canIssue, issueIdx) = getIssuePtr(readyCandidates)

    io.out.valid := canIssue && !io.flush.valid
    io.out.bits  := buffer(issueIdx)

    when(io.out.fire) {
        onIssue(issueIdx) // Updates RR pointer and clears valid bit in Trait
        printf(p"${name}: Issue robTag=${io.out.bits.robTag}\n")
    }

    // 7. Stall signals
    io.stallOperands.foreach(_ := !isEmpty && !canIssue && !io.flush.valid)
    io.stallPort.foreach(_ := io.out.valid && !io.out.ready)
}