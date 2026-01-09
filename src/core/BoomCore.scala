package core

import chisel3._
import chisel3.util._
import utility.CycleAwareModule
import components.frontend._
import components.backend._
import components.structures._
import common._
import common.Configurables._
import components.structures.{ALUInfo, BRUInfo, IssueBuffer}

class BoomCore(val hexFile: String) extends CycleAwareModule {
    // Component Instantiation
    val fetcher = Module(new InstFetcher)
    val decoder = Module(new InstDecoder)
    val dispatcher = Module(new InstDispatcher)
    val rat = Module(new RegisterAliasTable(3, 1, 1))
    val freeList = Module(new FreeList(Derived.PREG_COUNT, 32))
    val imem = Module(new InstructionMemory(hexFile))
    val btb = Module(new BranchTargetBuffer)

    val rob = Module(new ReOrderBuffer)
    val aluIB = Module(new IssueBuffer(new ALUInfo, 8))
    val bruIB = Module(new IssueBuffer(new BRUInfo, 4))
    val aluAdaptor = Module(new ALUAdaptor)
    val bruAdaptor = Module(new BRUAdaptor)
    val prf = Module(new PhysicalRegisterFile(Derived.PREG_COUNT, 4, 2, 32))
    val bc = Module(new BroadcastChannel)

    // --- Frontend Wiring ---

    // BTB integration
    btb.io.pc := fetcher.io.instAddr
    fetcher.io.targetPC := btb.io.target

    // Memory interface for fetcher
    imem.io.addr := fetcher.io.instAddr
    fetcher.io.instData := imem.io.inst

    // Decoder connections
    decoder.io.in <> fetcher.io.ifOut

    // Dispatcher connections
    dispatcher.io.instInput <> decoder.io.out

    // RAT and FreeList connections
    rat.io.readL(0) := dispatcher.io.rat.lrs1
    rat.io.readL(1) := dispatcher.io.rat.lrs2
    rat.io.readL(2) := dispatcher.io.rat.ldst
    dispatcher.io.rat.prs1 := rat.io.readP(0)
    dispatcher.io.rat.prs2 := rat.io.readP(1)
    dispatcher.io.rat.stalePdst := rat.io.readP(2)
    rat.io.update(0) <> dispatcher.io.rat.update

    dispatcher.io.freeList.allocate <> freeList.io.allocate

    // --- Backend Wiring ---

    // ROB connections
    rob.io.dispatch <> dispatcher.io.robOutput

    // Dispatch to Issue Buffers
    val instOutput = dispatcher.io.instOutput
    val isALU = instOutput.bits.fUnitType === FunUnitType.ALU
    val isBRU = instOutput.bits.fUnitType === FunUnitType.BRU

    // PRF Busy Table check for dispatch
    prf.io.readyAddrs(0) := instOutput.bits.prs1
    prf.io.readyAddrs(1) := instOutput.bits.prs2
    val src1Ready = prf.io.isReady(0)
    val src2Ready = prf.io.isReady(1)

    // ALU Issue Buffer Enqueue
    aluIB.io.in.valid := instOutput.valid && isALU && !rob.io.rollback.valid
    aluIB.io.in.bits.robTag := rob.io.robTag
    aluIB.io.in.bits.pdst := instOutput.bits.pdst
    aluIB.io.in.bits.src1 := instOutput.bits.prs1
    aluIB.io.in.bits.src2 := instOutput.bits.prs2
    aluIB.io.in.bits.src1Ready := src1Ready
    aluIB.io.in.bits.src2Ready := src2Ready
    aluIB.io.in.bits.imm := instOutput.bits.imm
    aluIB.io.in.bits.useImm := instOutput.bits.useImm
    aluIB.io.in.bits.info.aluOp := instOutput.bits.aluOpType

    // BRU Issue Buffer Enqueue
    bruIB.io.in.valid := instOutput.valid && isBRU && !rob.io.rollback.valid
    bruIB.io.in.bits.robTag := rob.io.robTag
    bruIB.io.in.bits.pdst := instOutput.bits.pdst
    bruIB.io.in.bits.src1 := instOutput.bits.prs1
    bruIB.io.in.bits.src2 := instOutput.bits.prs2
    bruIB.io.in.bits.src1Ready := src1Ready
    bruIB.io.in.bits.src2Ready := src2Ready
    bruIB.io.in.bits.imm := instOutput.bits.imm
    bruIB.io.in.bits.useImm := instOutput.bits.useImm
    bruIB.io.in.bits.info.bruOp := instOutput.bits.bruOpType
    bruIB.io.in.bits.info.cmpOp := instOutput.bits.cmpOpType
    bruIB.io.in.bits.info.pc := instOutput.bits.pc
    bruIB.io.in.bits.info.predict := instOutput.bits.predict
    bruIB.io.in.bits.info.predictedTarget := instOutput.bits.predictedTarget

    instOutput.ready := Mux(
      isALU,
      aluIB.io.in.ready,
      Mux(isBRU, bruIB.io.in.ready, false.B)
    ) && rob.io.dispatch.ready

    // PRF Busy Table Update (Set Busy on Dispatch)
    prf.io.setBusy.valid := instOutput.valid && instOutput.ready && instOutput.bits.pdst =/= 0.U
    prf.io.setBusy.bits := instOutput.bits.pdst

    // Issue Buffer to Adaptor connections
    aluAdaptor.io.issueIn <> aluIB.io.out
    bruAdaptor.io.issueIn <> bruIB.io.out

    // Adaptor to PRF Read connections
    // ALU uses ports 0, 1
    prf.io.read(0).addr := aluAdaptor.io.prfRead.addr1
    aluAdaptor.io.prfRead.data1 := prf.io.read(0).data
    prf.io.read(1).addr := aluAdaptor.io.prfRead.addr2
    aluAdaptor.io.prfRead.data2 := prf.io.read(1).data

    // BRU uses ports 2, 3
    prf.io.read(2).addr := bruAdaptor.io.prfRead.addr1
    bruAdaptor.io.prfRead.data1 := prf.io.read(2).data
    prf.io.read(3).addr := bruAdaptor.io.prfRead.addr2
    bruAdaptor.io.prfRead.data2 := prf.io.read(3).data

    // Adaptor to PRF Write connections
    prf.io.write(0) <> aluAdaptor.io.prfWrite
    prf.io.write(1) <> bruAdaptor.io.prfWrite

    // Broadcast Channel connections
    bc.io.aluResult <> aluAdaptor.io.broadcastOut
    bc.io.bruResult <> bruAdaptor.io.broadcastOut
    bc.io.memResult.valid := false.B
    bc.io.memResult.bits := 0.U.asTypeOf(new BroadcastBundle)

    // Broadcast to everything
    val broadcast = bc.io.broadcastOut
    aluIB.io.broadcast := broadcast
    bruIB.io.broadcast := broadcast
    rob.io.broadcastInput.valid := broadcast.valid
    rob.io.broadcastInput.bits := broadcast.bits

    // PRF Busy Table Update (Set Ready on Broadcast)
    prf.io.setReady.valid := broadcast.valid
    prf.io.setReady.bits := broadcast.bits.pdst

    // Misprediction handling
    val brUpdate = bruAdaptor.io.brUpdate
    val mispredict = brUpdate.valid && (
      (brUpdate.taken =/= brUpdate.predict) ||
          (brUpdate.taken && brUpdate.target =/= brUpdate.predictedTarget)
    )
    rob.io.brUpdate.valid := mispredict
    rob.io.brUpdate.bits.robTag := brUpdate.robTag
    rob.io.brUpdate.bits.mispredict := mispredict

    // Fetcher PC Overwrite (Misprediction)
    fetcher.io.pcOverwrite.valid := mispredict
    fetcher.io.pcOverwrite.bits := Mux(
      brUpdate.taken,
      brUpdate.target,
      brUpdate.pc + 4.U
    )

    // Flush logic
    aluIB.io.flush := mispredict
    bruIB.io.flush := mispredict
    rob.io.flush := false.B

    // Unused PRF readyAddrs
    prf.io.readyAddrs(2) := 0.U
    prf.io.readyAddrs(3) := 0.U

    // --- Commit and Rollback logic for renaming ---

    val commit = rob.io.commit
    val rollback = rob.io.rollback

    freeList.io.free.valid := commit.valid
    freeList.io.free.bits := commit.bits.stalePdst
    commit.ready := freeList.io.free.ready

    rat.rollback(0).valid := rollback.valid
    rat.rollback(0).bits.ldst := rollback.bits.ldst
    rat.rollback(0).bits.stalePdst := rollback.bits.stalePdst

    freeList.io.rollbackFree.valid := rollback.valid
    freeList.io.rollbackFree.bits := rollback.bits.pdst
}
