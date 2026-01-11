package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._
import common._
import utility.CycleAwareModule

class ALUInfo extends Bundle {
    val aluOp = ALUOpType()
}

class BRUInfo extends Bundle {
    val bruOp = BRUOpType()
    val cmpOp = CmpOpType()
    val pc = UInt(32.W)
    val predict = Bool()
    val predictedTarget = UInt(32.W)
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
}

class IssueBuffer[T <: Data](gen: T, numEntries: Int) extends CycleAwareModule {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new IssueBufferEntry(gen)))
        val broadcast = Input(Valid(new BroadcastBundle()))
        val out = Decoupled(new IssueBufferEntry(gen))

        val flush = Input(new FlushBundle)
    })

    val buffer = Reg(Vec(numEntries, new IssueBufferEntry(gen)))
    val valid = RegInit(VecInit(Seq.fill(numEntries)(false.B)))

    when(io.flush.valid) {
        for (i <- 0 until numEntries) {
            when(valid(i) && io.flush.checkKilled(buffer(i).robTag)) {
                valid(i) := false.B
            }
        }
    }

    // 3. Enqueue Logic
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
    }

    // 4. Update Readiness (Snoop/Broadcast)
    // Only update valid entries when broadcast is active
    when(io.broadcast.valid) {
        val resPdst = io.broadcast.bits.pdst
        for (i <- 0 until numEntries) {
            when(valid(i)) {
                when(buffer(i).src1 === resPdst) {
                    buffer(i).src1Ready := true.B
                }
                when(buffer(i).src2 === resPdst) {
                    buffer(i).src2Ready := true.B
                }
            }
        }
    }

    // 5. Issue Logic (Out-of-Order selection)
    val readyEntries = Wire(Vec(numEntries, Bool()))
    for (i <- 0 until numEntries) {
        readyEntries(i) := valid(i) && buffer(i).src1Ready && buffer(
          i
        ).src2Ready
    }

    val issueIndex = PriorityEncoder(readyEntries)
    val canIssue = readyEntries.asUInt.orR

    io.out.valid := canIssue && !io.flush.valid
    io.out.bits := buffer(issueIndex)

    when(io.out.fire) {
        valid(issueIndex) := false.B
    }

    when(io.in.fire) {
        printf(
          p"ISSUE_BUF: Enq robTag=${io.in.bits.robTag} pdst=${io.in.bits.pdst}\n"
        )
    }
    when(io.out.fire) {
        printf(
          p"ISSUE_BUF: Issue robTag=${io.out.bits.robTag} pdst=${io.out.bits.pdst}\n"
        )
    }
}
