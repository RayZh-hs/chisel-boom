package common

import chisel3._
import chisel3.util._
import Configurables._
import Configurables.Derived._

/** Instruction Fetch output bundle definition.
  */
class FetchToDecodeBundle extends Bundle {
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val predict = Bool()
    val predictedTarget = UInt(32.W)
}

/** Micro-operation bundle definition.
  *
  * Full uop structure, used in Decode -> Dispatch stage. Broken up when pushed
  * to IQ and ROB.
  *
  * @note
  *   Memory access uops assume: `paddr` is `prs1`, `psrc` is `prs2`, `pdst` is
  *   `pdst`.
  */
class DecodedInstBundle extends Bundle {
    // - categorizing operation
    val fUnitType = FunUnitType() // functional unit type
    val aluOpType = ALUOpType()
    val multOpType = MultOpType()
    val bruOpType = BRUOpType()
    val cmpOpType = CmpOpType()
    val isLoad = Bool()
    val isStore = Bool()
    // - id to pc (needed if it is a branching instruction or AUIPC)
    val pc = UInt(32.W)
    val predict = Bool()
    val predictedTarget = UInt(32.W)
    // - register renaming info
    val lrs1, lrs2, ldst = UInt(5.W) // logical registers
    val prs1, prs2, pdst = UInt(PREG_WIDTH.W) // physical registers
    val stalePdst = UInt(PREG_WIDTH.W) // stale physical destination
    val useImm = Bool()
    val imm = UInt(32.W)
    // - memory access info
    val opWidth = MemOpWidth()
    val isUnsigned = Bool()
    // paddr is assumed to be prs1
    // psrc is assumed to be prs2
    // pdst is assumed to be pdst
}

class DecodedInstWithRAS extends Bundle {
    val inst = new DecodedInstBundle
    val rasSP = UInt(RAS_WIDTH.W)
}

class DispatchToROBBundle extends Bundle {
    val ldst = UInt(5.W)
    val pdst = UInt(PREG_WIDTH.W)
    val stalePdst = UInt(PREG_WIDTH.W)
    val isStore = Bool()

    // wire pc if elaboration option is set
    val pc = if (Configurables.Elaboration.pcInROB) Some(UInt(32.W)) else None
}

class DispatchToALQBundle extends Bundle {
    val aluOpType = ALUOpType()
    val prs1, prs2 = UInt(PREG_WIDTH.W)
    val (useImm, imm) = (Bool(), UInt(32.W))
    val pdst = UInt(PREG_WIDTH.W)
}

class DispatchToMultQBundle extends Bundle {
    val multOpType = MultOpType()
    val prs1, prs2 = UInt(PREG_WIDTH.W)
    val pdst = UInt(PREG_WIDTH.W)
}

class DispatchToBRQBundle extends Bundle {
    val bruOpType = BRUOpType()
    val cmpOpType = CmpOpType()
    val prs1, prs2 = UInt(PREG_WIDTH.W)
    val (useImm, imm) = (Bool(), UInt(32.W))
    val pc = UInt(32.W)
    val rasSP = UInt(RAS_WIDTH.W)
}

class DispatchToLSQBundle extends Bundle {
    val isLoad = Bool() // differentiate load/store
    val opWidth = MemOpWidth()
    val paddr = UInt(PREG_WIDTH.W)
    val (useImm, imm) = (Bool(), UInt(32.W))
    val psrc = UInt(PREG_WIDTH.W)
    val pdst = UInt(PREG_WIDTH.W)
}

class BroadcastBundle extends Bundle {
    val pdst = UInt(PREG_WIDTH.W)
    val robTag = UInt(ROB_WIDTH.W)
    val data = UInt(32.W)
    val writeEn = Bool()
}

class RollbackBundle extends Bundle {
    val ldst = UInt(5.W)
    val pdst = UInt(PREG_WIDTH.W)
    val stalePdst = UInt(PREG_WIDTH.W)
}

class BranchUpdateBundle extends Bundle {
    val valid = Bool()
    val mispredict = Bool()
    val taken = Bool()
    val target = UInt(32.W)
    val pc = UInt(32.W)
    val robTag = UInt(ROB_WIDTH.W)
    val predict = Bool()
    val predictedTarget = UInt(32.W)
    val rasSP = UInt(RAS_WIDTH.W)
}

class FlushBundle extends Bundle {
    val valid = Bool()
    val flushTag = UInt(ROB_WIDTH.W)
    val robHead = UInt(ROB_WIDTH.W)

    def isYounger(tag: UInt): Bool = {
        val headLeFlush = robHead <= flushTag
        val tagGeRobHead = tag >= robHead
        val tagLeFlush = tag <= flushTag
        val isOlderOrSame = Mux(
          headLeFlush,
          (tagGeRobHead && tagLeFlush),
          (tagGeRobHead || tagLeFlush)
        )
        !isOlderOrSame
    }

    def checkKilled(tag: UInt): Bool = {
        valid && isYounger(tag)
    }
}

/** The Load Store Action is what actually drives the memory system
  */
class LoadStoreAction extends Bundle {
    val isLoad = Bool()
    val opWidth = MemOpWidth()
    val isUnsigned = Bool()
    val addr = UInt(32.W)
    val data = UInt(32.W)
    val targetReg = UInt(PREG_WIDTH.W)
}

class RASAdaptorBundle extends Bundle {
    val flush = Bool()
    val flushNextPC = UInt(32.W)
    val currentSP = UInt(RAS_WIDTH.W)
}

class MemoryInterface extends Bundle {
    val req = Flipped(Valid(new LoadStoreAction))
    val resp = Valid(UInt(32.W))
}

class BoomCoreProfileBundle extends Bundle {
    import common.Configurables.Profiling._
    def optfield[T <: Data](cond: Boolean, gen: => T): Option[T] = {
        if (cond) Some(gen) else None
    }

    val totalBranches = optfield(branchMispredictionRate, UInt(32.W))
    val totalMispredicts = optfield(branchMispredictionRate, UInt(32.W))

    // IPC
    val totalInstructions = optfield(IPC, UInt(64.W))
    val totalCycles = optfield(IPC, UInt(64.W))

    // Utilization
    val busyFetcher = optfield(Utilization, UInt(32.W))
    val fetcherStallBuffer = optfield(Utilization, UInt(32.W))

    val busyDecoder = optfield(Utilization, UInt(32.W))
    val decoderStallDispatch = optfield(Utilization, UInt(32.W))

    val busyDispatcher = optfield(Utilization, UInt(32.W))
    val dispatcherStallFreeList = optfield(Utilization, UInt(32.W))
    val dispatcherStallROB = optfield(Utilization, UInt(32.W))
    val dispatcherStallIssue = optfield(Utilization, UInt(32.W))

    val busyIssueALU = optfield(Utilization, UInt(32.W))
    val issueALUStallOperands = optfield(Utilization, UInt(32.W))
    val issueALUStallPort = optfield(Utilization, UInt(32.W))

    val busyIssueBRU = optfield(Utilization, UInt(32.W))
    val issueBRUStallOperands = optfield(Utilization, UInt(32.W))
    val issueBRUStallPort = optfield(Utilization, UInt(32.W))

    val busyIssueMult = optfield(Utilization, UInt(32.W))
    val issueMultStallOperands = optfield(Utilization, UInt(32.W))
    val issueMultStallPort = optfield(Utilization, UInt(32.W))

    val busyALU = optfield(Utilization, UInt(32.W))
    val busyBRU = optfield(Utilization, UInt(32.W))
    val busyMult = optfield(Utilization, UInt(32.W))
    
    val busyLSU = optfield(Utilization, UInt(32.W))
    val lsuStallCommit = optfield(Utilization, UInt(32.W))

    val busyWriteback = optfield(Utilization, UInt(32.W))
    val busyROB = optfield(Utilization, UInt(32.W))

    // Queue Depths (Accumulated)
    val fetchQueueDepth = optfield(Utilization, UInt(64.W))
    val issueALUDepth = optfield(Utilization, UInt(64.W))
    val issueBRUDepth = optfield(Utilization, UInt(64.W))
    val issueMultDepth = optfield(Utilization, UInt(64.W))
    val lsuQueueDepth = optfield(Utilization, UInt(64.W))
    val robDepth = optfield(Utilization, UInt(64.W))

    // Throughput Counts (Fired Events)
    val countFetcher = optfield(Utilization, UInt(64.W))
    val countDecoder = optfield(Utilization, UInt(64.W))
    val countDispatcher = optfield(Utilization, UInt(64.W))
    val countIssueALU = optfield(Utilization, UInt(64.W))
    val countIssueBRU = optfield(Utilization, UInt(64.W))
    val countIssueMult = optfield(Utilization, UInt(64.W))
    val countLSU = optfield(Utilization, UInt(64.W))
    val countWriteback = optfield(Utilization, UInt(64.W))

    // Dependency Resolution (Accumulated Wait Cycles)
    val waitDepALU = optfield(Utilization, UInt(64.W))
    val waitDepBRU = optfield(Utilization, UInt(64.W))
    val waitDepMult = optfield(Utilization, UInt(64.W))

    // Rollback
    val totalRollbackEvents = optfield(RollbackTime, UInt(32.W))
    val totalRollbackCycles = optfield(RollbackTime, UInt(32.W))
}
