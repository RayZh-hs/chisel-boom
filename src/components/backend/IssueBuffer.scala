package components.backend

import chisel3._
import chisel3.util._
import common.Configurables._
import common._

class ALUInfo extends Bundle {
    val aluOp = ALUOpType()
}

class BRUInfo extends Bundle {
    val bruOp = BRUOpType()
    val cmpOp = CmpOpType()
    val pc = UInt(32.W)
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

class IssueBuffer[T <: Data](gen: T, numEntries: Int) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new IssueBufferEntry(gen)))
        val broadcast = Input(Valid(new BroadcastBundle()))
        val out = Decoupled(new IssueBufferEntry(gen))
    })
    val buffer = RegInit(VecInit(Seq.fill(numEntries)(0.U.asTypeOf(new IssueBufferEntry(gen)))))
    val valid = RegInit(0.U(numEntries.W))
    
    // Enqueue logic
    when(io.in.valid && io.in.ready) {
        val emptyIndex = PriorityEncoder(~valid)
        buffer(emptyIndex) := io.in.bits
        valid := valid | (1.U << emptyIndex)
    }

    // Update source readiness based on broadcasts
    for (i <- 0 until numEntries) {
        when(valid(i)) {
            when(buffer(i).src1 === io.broadcast.bits.pdst && io.broadcast.valid) {
                buffer(i).src1Ready := true.B
            }
            when(buffer(i).src2 === io.broadcast.bits.pdst && io.broadcast.valid) {
                buffer(i).src2Ready := true.B
            }
        }
    }

    // Issue logic
    val readyEntries = Wire(Vec(numEntries, Bool()))
    for (i <- 0 until numEntries) {
        readyEntries(i) := valid(i) && buffer(i).src1Ready && buffer(i).src2Ready
    }
    val issueIndex = PriorityEncoder(readyEntries.asUInt)
    val canIssue = readyEntries.asUInt.orR
    io.out.valid := canIssue
    io.out.bits := buffer(issueIndex)

    when(io.out.valid && io.out.ready) {
        valid := valid & ~(1.U << issueIndex)
    }
}