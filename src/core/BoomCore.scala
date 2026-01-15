package core

import chisel3._
import chisel3.util._
import utility.CycleAwareModule
import components.frontend._
import components.backend._
import components.structures._
import components.memory._
import common._
import common.Configurables._
import components.structures.{
    ALUInfo,
    BRUInfo,
    IssueBuffer,
    LoadStoreInfo,
    SequentialIssueBuffer,
    SequentialBufferEntry
}

class BoomCore(val hexFile: String) extends CycleAwareModule {
    val io = IO(new Bundle {
        val exit = Output(Valid(new LoadStoreAction))
        val put = Output(Valid(new LoadStoreAction))
    })

    // Component Instantiation
    val fetcher = Module(new InstFetcher)
    val ifQueue = Module(
      new Queue(new FetchToDecodeBundle, entries = 4, pipe = true, flow = true)
    )
    val decoder = Module(new InstDecoder)
    val dispatcher = Module(new InstDispatcher)
    val rat = Module(new RegisterAliasTable(3, 1, 1))
    val freeList = Module(new FreeList(Derived.PREG_COUNT, 32))
    val imem = Module(new InstructionMemory(hexFile))
    val btb = Module(new BranchTargetBuffer)

    val rob = Module(new ReOrderBuffer)
    val aluIB = Module(new IssueBuffer(new ALUInfo, 8, "ALU_IB"))
    val bruIB = Module(new IssueBuffer(new BRUInfo, 4, "BRU_IB"))
    val aluAdaptor = Module(new ALUAdaptor)
    val bruAdaptor = Module(new BRUAdaptor)

    // Memory Subsystem and MMIO Devices
    // val lsu = Module(new LoadStoreUnit) // Removed: Replaced by Cache+DRAM inside MemorySubsystem
    val printDevice = Module(new PrintDevice)
    val exitDevice = Module(new ExitDevice)
    val mmio = Module(new MMIORouter(Seq("h80000000".U, "hFFFFFFFF".U)))
    val memory = Module(new MemorySubsystem)
    val lsAdaptor = Module(new LoadStoreAdaptor)

    // --- Unified Memory System Integration ---
    val memConf = MemConfig(idWidth = 4, addrWidth = 32, dataWidth = 128)
    
    // Switch to DPI-based DRAM
    val dram = Module(new DPIDRAM(memConf, hexFile))
    dram.io.clock := clock
    dram.io.reset := reset.asBool

    // Arbiter for DRAM Requests (2 Masters: D-Cache (0), I-Cache (1))
    val dramArb = Module(new RRArbiter(new MemRequest(memConf), 2))
    
    // Connect D-Cache (memory) to Arbiter Port 0
    dramArb.io.in(0) <> memory.io.dram.req
    
    // Connect I-Cache (imem) to Arbiter Port 1
    dramArb.io.in(1) <> imem.io.dram.req
    
    // Connect Arbiter to DRAM
    dram.io.req <> dramArb.io.out
    
    // Broadcast DRAM Response to both
    // They will filter based on ID (D-Cache: 0,1; I-Cache: 2,3)
    memory.io.dram.resp.valid := dram.io.resp.valid
    memory.io.dram.resp.bits := dram.io.resp.bits
    imem.io.dram.resp.valid := dram.io.resp.valid
    imem.io.dram.resp.bits := dram.io.resp.bits
    
    // Backpressure: DRAM ready if both listeners are ready
    dram.io.resp.ready := memory.io.dram.resp.ready && imem.io.dram.resp.ready

    // Generic memory routing: Adaptor -> MemorySubsystem -> (LSU | MMIO)
    memory.io.upstream <> lsAdaptor.io.mem
    // lsu.io <> memory.io.lsu // Removed
    mmio.io.upstream <> memory.io.mmio

    printDevice.io <> mmio.io.devices(0)
    exitDevice.io <> mmio.io.devices(1)

    // Expose exit device to top level
    io.exit.valid := exitDevice.io.req.valid && !exitDevice.io.req.bits.isLoad
    io.exit.bits := exitDevice.io.req.bits

    io.put.valid := printDevice.io.req.valid && !printDevice.io.req.bits.isLoad
    io.put.bits := printDevice.io.req.bits

    val prf = Module(new PhysicalRegisterFile(Derived.PREG_COUNT, 6, 1, 32))
    val bc = Module(new BroadcastChannel)

    // --- Frontend Wiring ---

    // BTB integration
    btb.io.pc := fetcher.io.instAddr
    fetcher.io.btbResult := btb.io.target

    // Memory interface for fetcher
    imem.io.addr := fetcher.io.instAddr
    fetcher.io.instData := imem.io.inst
    fetcher.io.instValid := imem.io.respValid
    fetcher.io.instReady := imem.io.ready

    // Frontend queue (in-stage buffer between fetcher and decoder)
    ifQueue.io.enq <> fetcher.io.ifOut
    ifQueue.reset := reset.asBool || fetcher.io.pcOverwrite.valid

    // Decoder connections
    decoder.io.in <> ifQueue.io.deq

    // Dispatcher connections
    dispatcher.io.instInput <> decoder.io.out

    // RAT and FreeList connections
    rat.io.readL(0) := dispatcher.io.ratAccess.lrs1
    rat.io.readL(1) := dispatcher.io.ratAccess.lrs2
    rat.io.readL(2) := dispatcher.io.ratAccess.ldst
    dispatcher.io.ratAccess.prs1 := rat.io.readP(0)
    dispatcher.io.ratAccess.prs2 := rat.io.readP(1)
    dispatcher.io.ratAccess.stalePdst := rat.io.readP(2)
    rat.io.update(0) <> dispatcher.io.ratAccess.update

    if (Configurables.Elaboration.printOnBroadcast) {
        rat.io.debugBroadcastValid.get := bc.io.broadcastOut.valid
    }

    dispatcher.io.freeListAccess.allocate <> freeList.io.allocate

    // --- Backend Wiring ---

    // ROB connections
    rob.io.dispatch <> dispatcher.io.robOutput

    // Dispatch to Issue Buffers
    val instOutput = dispatcher.io.instOutput
    val isALU = instOutput.bits.fUnitType === FunUnitType.ALU
    val isBRU = instOutput.bits.fUnitType === FunUnitType.BRU
    val isLSU = instOutput.bits.fUnitType === FunUnitType.MEM

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
    if (Configurables.Elaboration.pcInIssueBuffer) {
        aluIB.io.in.bits.pc.get := instOutput.bits.pc
    }

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
    if (Configurables.Elaboration.pcInIssueBuffer) {
        bruIB.io.in.bits.pc.get := instOutput.bits.pc
    }

    // LSU Issue Buffer Enqueue
    lsAdaptor.io.issueIn.valid := instOutput.valid && isLSU && !rob.io.rollback.valid
    lsAdaptor.io.issueIn.bits.robTag := rob.io.robTag
    lsAdaptor.io.issueIn.bits.pdst := instOutput.bits.pdst
    lsAdaptor.io.issueIn.bits.src1 := instOutput.bits.prs1
    lsAdaptor.io.issueIn.bits.src2 := instOutput.bits.prs2
    lsAdaptor.io.issueIn.bits.src1Ready := src1Ready
    lsAdaptor.io.issueIn.bits.src2Ready := Mux(
      instOutput.bits.isStore,
      src2Ready,
      true.B
    ) // Not used for loads
    lsAdaptor.io.issueIn.bits.info.opWidth := instOutput.bits.opWidth
    lsAdaptor.io.issueIn.bits.info.isStore := instOutput.bits.isStore
    lsAdaptor.io.issueIn.bits.info.isUnsigned := instOutput.bits.isUnsigned
    lsAdaptor.io.issueIn.bits.info.imm := instOutput.bits.imm
    if (Configurables.Elaboration.pcInIssueBuffer) {
        lsAdaptor.io.issueIn.bits.pc.get := instOutput.bits.pc
    }

    instOutput.ready := Mux(
      isALU,
      aluIB.io.in.ready,
      Mux(
        isBRU,
        bruIB.io.in.ready,
        Mux(isLSU, lsAdaptor.io.issueIn.ready, false.B)
      )
    ) && rob.io.dispatch.ready

    // PRF Busy Table Update (Set Busy on Dispatch)
    prf.io.setBusy.valid := instOutput.valid && instOutput.ready && instOutput.bits.pdst =/= 0.U
    prf.io.setBusy.bits := instOutput.bits.pdst

    // Issue Buffer to Adaptor connections
    aluAdaptor.io.issueIn <> aluIB.io.out
    bruAdaptor.io.issueIn <> bruIB.io.out

    lsAdaptor.io.robHead := rob.io.head

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

    // LSU uses ports 4, 5
    prf.io.read(4).addr := lsAdaptor.io.prfRead.addr1
    lsAdaptor.io.prfRead.data1 := prf.io.read(4).data
    prf.io.read(5).addr := lsAdaptor.io.prfRead.addr2
    lsAdaptor.io.prfRead.data2 := prf.io.read(5).data

    // Adaptor to PRF Write connections (Unified via Broadcast Channel)
    prf.io.write(0).addr := bc.io.broadcastOut.bits.pdst
    prf.io.write(0).data := bc.io.broadcastOut.bits.data
    prf.io
        .write(0)
        .en := bc.io.broadcastOut.valid && bc.io.broadcastOut.bits.writeEn

    // Broadcast Channel connections
    bc.io.aluResult <> aluAdaptor.io.broadcastOut
    bc.io.bruResult <> bruAdaptor.io.broadcastOut
    bc.io.memResult <> lsAdaptor.io.broadcastOut

    // Broadcast to everything
    val broadcast = bc.io.broadcastOut
    aluIB.io.broadcast := broadcast
    bruIB.io.broadcast := broadcast
    lsAdaptor.io.broadcastIn := broadcast
    rob.io.broadcastInput.valid := broadcast.valid
    rob.io.broadcastInput.bits := broadcast.bits

    // PRF Busy Table Update (Set Ready on Broadcast)
    prf.io.setReady.valid := broadcast.valid
    prf.io.setReady.bits := broadcast.bits.pdst

    // Misprediction handling
    val brUpdate = bruAdaptor.io.brUpdate
    val mispredict = brUpdate.valid && brUpdate.mispredict

    // BTB Update
    btb.io.update.valid := brUpdate.valid
    btb.io.update.bits.pc := brUpdate.pc
    btb.io.update.bits.target := brUpdate.target
    btb.io.update.bits.taken := brUpdate.taken
    btb.io.update.bits.mispredict := brUpdate.mispredict

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
    val flushCtrl = Wire(new FlushBundle)
    flushCtrl.valid := mispredict
    flushCtrl.flushTag := brUpdate.robTag
    flushCtrl.robHead := rob.io.head

    aluIB.io.flush := flushCtrl
    bruIB.io.flush := flushCtrl

    aluAdaptor.io.flush := flushCtrl
    bruAdaptor.io.flush := flushCtrl
    lsAdaptor.io.flush := flushCtrl


    // Unused PRF readyAddrs
    prf.io.readyAddrs(2) := 0.U
    prf.io.readyAddrs(3) := 0.U
    prf.io.readyAddrs(4) := 0.U
    prf.io.readyAddrs(5) := 0.U

    // --- Commit and Rollback logic for renaming ---

    val commit = rob.io.commit
    val rollback = rob.io.rollback

    freeList.io.free.valid := rob.io.commit.valid && (rob.io.commit.bits.ldst =/= 0.U)
    freeList.io.free.bits := rob.io.commit.bits.stalePdst
    commit.ready := freeList.io.free.ready

    rat.rollback(0).valid := rollback.valid
    rat.rollback(0).bits.ldst := rollback.bits.ldst
    rat.rollback(0).bits.stalePdst := rollback.bits.stalePdst

    freeList.io.rollbackFree.valid := rollback.valid && (rollback.bits.ldst =/= 0.U)
    freeList.io.rollbackFree.bits := rollback.bits.pdst

    prf.io.clrBusy.valid := rollback.valid && (rollback.bits.pdst =/= 0.U)
    prf.io.clrBusy.bits := rollback.bits.pdst
}
