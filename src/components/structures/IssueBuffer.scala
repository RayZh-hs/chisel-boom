package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._
import common._
import utility.CycleAwareModule

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
    val info = gen

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
class IssueBuffer[T <: Data](gen: T, numEntries: Int, name: String)
    extends CycleAwareModule {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new IssueBufferEntry(gen)))
        val broadcast = Input(Valid(new BroadcastBundle()))
        val out = Decoupled(new IssueBufferEntry(gen))

        val flush = Input(new FlushBundle)

        // Profiling Outputs
        val stallOperands =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
        val stallPort =
            if (common.Configurables.Profiling.Utilization) Some(Output(Bool()))
            else None
        val count =
            if (common.Configurables.Profiling.Utilization)
                Some(Output(UInt(log2Ceil(numEntries + 1).W)))
            else None
        val waitDepCount =
            if (common.Configurables.Profiling.Utilization)
                Some(Output(UInt(log2Ceil(numEntries + 1).W)))
            else None
    })

    val buffer = Reg(Vec(numEntries, new IssueBufferEntry(gen)))
    val valid = RegInit(VecInit(Seq.fill(numEntries)(false.B)))

    // Fix: Round Robin State
    // Tracks the index of the last instruction issued to ensure fairness
    /*
     * @note
     *   We use a Round-Robin issue policy to prevent Starvation
     *   (buffer filled by noops that do not get issued).
     *   
     *   Starvation is prevented by the Issue Logic (Dequeue), not the Enqueue Logic.
     */
    val lastIssuedIndex = RegInit((numEntries - 1).U(log2Ceil(numEntries).W))
    io.count.foreach(_ := PopCount(valid))
    io.waitDepCount.foreach { c =>
        c := PopCount(valid.zip(buffer).map { case (v, b) =>
            v && (!b.src1Ready || !b.src2Ready)
        })
    }

    when(io.flush.valid) {
        for (i <- 0 until numEntries) {
            when(valid(i) && io.flush.checkKilled(buffer(i).robTag)) {
                valid(i) := false.B
            }
        }
    }

    // Update readiness on Broadcast
    when(io.broadcast.valid) {
        val resPdst = io.broadcast.bits.pdst
        for (i <- 0 until numEntries) {
            when(valid(i)) {
                when(buffer(i).src1 === resPdst) {
                    buffer(i).src1Ready := true.B
                    printf(
                      p"${name}: Broadcast wake up robTag=${buffer(i).robTag} src1=${buffer(i).src1}\n"
                    )
                }
                when(buffer(i).src2 === resPdst) {
                    buffer(i).src2Ready := true.B
                    printf(
                      p"${name}: Broadcast wake up robTag=${buffer(i).robTag} src2=${buffer(i).src2}\n"
                    )
                }
            }
        }
    }

    // Enqueue Logic
    val canEnqueue = !valid.asUInt.andR
    io.in.ready := canEnqueue && !io.flush.valid

    val emptyIndex = PriorityEncoder(valid.map(!_))
    when(io.in.fire) {
        val entry = io.in.bits
        val broadcastMatch1 =
            io.broadcast.valid && (entry.src1 === io.broadcast.bits.pdst)
        val broadcastMatch2 =
            io.broadcast.valid && (entry.src2 === io.broadcast.bits.pdst)

        val updatedEntry = Wire(new IssueBufferEntry(gen))
        updatedEntry := entry
        when(broadcastMatch1) { updatedEntry.src1Ready := true.B }
        when(broadcastMatch2) { updatedEntry.src2Ready := true.B }

        buffer(emptyIndex) := updatedEntry
        valid(emptyIndex) := true.B

        if (Configurables.Elaboration.pcInIssueBuffer) {
            printf(
              p"${name}: Enq robTag=${updatedEntry.robTag} pdst=${updatedEntry.pdst} src1=${updatedEntry.src1} src1Ready=${updatedEntry.src1Ready} src2=${updatedEntry.src2} src2Ready=${updatedEntry.src2Ready} pc=0x${Hexadecimal(updatedEntry.pc.get)}\n"
            )
        } else {
            printf(
              p"${name}: Enq robTag=${updatedEntry.robTag} pdst=${updatedEntry.pdst} src1=${updatedEntry.src1} src1Ready=${updatedEntry.src1Ready} src2=${updatedEntry.src2} src2Ready=${updatedEntry.src2Ready}\n"
            )
        }
    }

    // Issue Logic (Fix: Use Round-Robin Selection)
    val readyEntries = Wire(Vec(numEntries, Bool()))
    for (i <- 0 until numEntries) {
        readyEntries(i) := valid(i) && buffer(i).src1Ready && buffer(
          i
        ).src2Ready
    }

    // Create a masked version of ready signals.
    //Only look at entries with an index greater than the last one issued.
    val maskedReadyEntries = Wire(Vec(numEntries, Bool()))
    for (i <- 0 until numEntries) {
        maskedReadyEntries(i) := readyEntries(i) && (i.U > lastIssuedIndex)
    }

    // Check if there are any ready entries in the masked region (wrap-around)
    val hasReadyInMask = maskedReadyEntries.asUInt.orR

    // If we have a ready entry after the pointer, pick it.
    // Otherwise, wrap around and pick the first available from the start.
    val nextIndex = PriorityEncoder(maskedReadyEntries)
    val wrapIndex = PriorityEncoder(readyEntries)

    val issueIndex = Mux(hasReadyInMask, nextIndex, wrapIndex)
    val canIssue = readyEntries.asUInt.orR

    io.out.valid := canIssue && !io.flush.valid
    io.out.bits := buffer(issueIndex)

    io.stallOperands.foreach(
      _ := valid.asUInt.orR && !canIssue && !io.flush.valid
    )
    io.stallPort.foreach(_ := io.out.valid && !io.out.ready)

    when(io.out.fire) {
        valid(issueIndex) := false.B
        // Update the round-robin pointer
        lastIssuedIndex := issueIndex
    }

    when(io.out.fire) {
        if (Configurables.Elaboration.pcInIssueBuffer) {
            printf(
              p"${name}: Issue robTag=${io.out.bits.robTag} pdst=${io.out.bits.pdst} pc=0x${Hexadecimal(io.out.bits.pc.get)}\n"
            )
        } else {
            printf(
              p"${name}: Issue robTag=${io.out.bits.robTag} pdst=${io.out.bits.pdst}\n"
            )
        }
    }
}
