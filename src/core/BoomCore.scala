package core

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utility.CycleAwareModule
import components.frontend._
import components.backend._
import components.structures._
import components.memory._
import common._
import common.Configurables._

class BoomCore(val hexFile: String) extends CycleAwareModule {
    val io = IO(new Bundle {
        val exit = Output(Valid(UInt(32.W)))
        val put = Decoupled(UInt(32.W))
        val profiler = Output(new BoomCoreProfileBundle)
    })

    // Component Instantiation
    val fetcher = Module(new InstFetcher)
    val ifQueue = Module(
      new Queue(
        new FetchToDecodeBundle,
        entries = 2,
        pipe = false,
        flow = false
      )
    )
    val decoder = Module(new InstDecoder)
    val rasAdaptor = Module(new RASAdaptor)
    val decodeRASPlexer = Module(new DispatchRASPlexer)
    val dispatcher = Module(new InstDispatcher)
    val rat = Module(new RegisterAliasTable(3, 1, 1))
    val freeList = Module(new FreeList(Derived.PREG_COUNT, 32))
    val icache = Module(
        new ICache(CacheConfig(nSetsWidth = 6, nCacheLineWidth = 4, idOffset = 2))
    )
    val btb = Module(new BranchTargetBuffer)

    val rob = Module(new ReOrderBuffer)
    val aluIB = Module(new IssueBuffer(new ALUInfo, 16, "ALU_IB"))
    val bruIB = Module(new IssueBuffer(new BRUInfo, 16, "BRU_IB"))
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
    dramArb.io.in(1) <> icache.io.dram.req

    // Connect Arbiter to DRAM
    dram.io.req <> dramArb.io.out

    // Broadcast DRAM Response to both
    // They will filter based on ID (D-Cache: 0,1; I-Cache: 2,3)
    memory.io.dram.resp.valid := dram.io.resp.valid
    memory.io.dram.resp.bits := dram.io.resp.bits
    icache.io.dram.resp.valid := dram.io.resp.valid
    icache.io.dram.resp.bits := dram.io.resp.bits

    // Backpressure: DRAM ready if both listeners are ready
    dram.io.resp.ready := memory.io.dram.resp.ready && icache.io.dram.resp.ready

    // Generic memory routing: Adaptor -> MemorySubsystem -> (LSU | MMIO)
    memory.io.upstream <> lsAdaptor.io.mem
    // lsu.io <> memory.io.lsu // Removed
    mmio.io.upstream <> memory.io.mmio

    printDevice.io <> mmio.io.devices(0)
    exitDevice.io <> mmio.io.devices(1)

    // Expose exit device to top level
    io.exit <> exitDevice.exitOut
    io.put <> printDevice.debugOut

    val prf = Module(new PhysicalRegisterFile(Derived.PREG_COUNT, 6, 1, 32))
    val bc = Module(new BroadcastChannel)

    // --- Frontend Wiring ---

    // BTB integration
    btb.io.pc := fetcher.io.instAddr
    fetcher.io.btbResult := btb.io.target

    // Memory interface for fetcher
    icache.io.req <> fetcher.io.icache.req
    fetcher.io.icache.resp <> icache.io.resp

    // Frontend queue (in-stage buffer between fetcher and decoder)
    ifQueue.io.enq <> fetcher.io.ifOut

    val rasPredictedValid = RegNext(rasAdaptor.io.out.fire, init = false.B)
    val rasPredictedSignal =
        RegEnable(rasAdaptor.io.out.bits, rasAdaptor.io.out.fire)
    
    val rasFlush = rasPredictedValid && rasPredictedSignal.flush
    val rasFlushTargetPC = rasPredictedSignal.flushNextPC


    // Decoder and connections
    // Wire up Decoder & RASAdaptor to output of ifQueue
    // -- decoder.io.in <> ifQueue.io.deq
    // -- rasAdaptor.io.in <> ifQueue.io.deq
    ifQueue.io.deq.ready := decoder.io.in.ready && rasAdaptor.io.in.ready
    decoder.io.in.valid := ifQueue.io.deq.valid && !rasFlush
    decoder.io.in.bits := ifQueue.io.deq.bits
    rasAdaptor.io.in.valid := ifQueue.io.deq.valid && !rasFlush
    rasAdaptor.io.in.bits := ifQueue.io.deq.bits

    // Wire output of Decoder & RASAdaptor to Plexer
    decodeRASPlexer.io.instFromDecoder <> decoder.io.out
    decodeRASPlexer.io.rasBundleFromAdaptor <> rasAdaptor.io.out

    val decoderDispatcherQueue = Module(
      new Queue(new DecodedInstWithRAS, entries = 2, pipe = false, flow = false)
    )

    // Dispatcher connections
    // Combine Inst and RasSP into the queue
    // Wire the Plexer output to Dispatcher
    decoderDispatcherQueue.io.enq <> decodeRASPlexer.io.plexToDispatcher
    dispatcher.io.instInput <> decoderDispatcherQueue.io.deq

    // On RAS predict overwrite, set pc_overwrite for IF and reset ifQueue
    
    // RAS Recovery
    rasAdaptor.io.recover := bruAdaptor.io.brUpdate.valid && bruAdaptor.io.brUpdate.mispredict
    rasAdaptor.io.recoverSP := bruAdaptor.io.brUpdate.rasSP

    val backendMispredict =
        bruAdaptor.io.brUpdate.valid && bruAdaptor.io.brUpdate.mispredict
    decoderDispatcherQueue.reset := reset.asBool || backendMispredict
    ifQueue.reset := reset.asBool || fetcher.io.pcOverwrite.valid
    when(ifQueue.reset.asBool) {
      printf(p"IF Queue Reset\n")
    }

    // RAT and FreeList connections
    rat.io.readL(0) := dispatcher.io.ratAccess.lrs1
    rat.io.readL(1) := dispatcher.io.ratAccess.lrs2
    rat.io.readL(2) := dispatcher.io.ratAccess.ldst
    dispatcher.io.ratAccess.prs1 := rat.io.readP(0)
    dispatcher.io.ratAccess.prs2 := rat.io.readP(1)
    dispatcher.io.ratAccess.stalePdst := rat.io.readP(2)
    rat.io.update(0) <> dispatcher.io.ratAccess.update

    if (Configurables.Elaboration.printRegFileOnCommit) {
        rat.io.debugBroadcastValid.get := bc.io.broadcastOut.valid
    }

    dispatcher.io.freeListAccess.allocate <> freeList.io.allocate

    // --- Backend Wiring ---

    // ROB connections
    rob.io.dispatch <> dispatcher.io.robOutput

    // Dispatch to Issue Buffers

    class DispatcherQueueEntry extends Bundle {
        val inst = new DecodedInstBundle
        val rasSP = UInt(RAS_WIDTH.W)
        val robTag = UInt(ROB_WIDTH.W)
    }

    val dispatcherInstQueue = Module(
      new Queue(
        new DispatcherQueueEntry,
        entries = 2,
        pipe = false,
        flow = false
      )
    )
    dispatcherInstQueue.io.enq.valid := dispatcher.io.instOutput.valid
    dispatcherInstQueue.io.enq.bits.inst := dispatcher.io.instOutput.bits.inst
    dispatcherInstQueue.io.enq.bits.rasSP := dispatcher.io.instOutput.bits.rasSP
    dispatcherInstQueue.io.enq.bits.robTag := rob.io.robTag
    dispatcher.io.instOutput.ready := dispatcherInstQueue.io.enq.ready

    dispatcherInstQueue.reset := reset.asBool || backendMispredict

    val instOutput = dispatcherInstQueue.io.deq
    val isALU = instOutput.bits.inst.fUnitType === FunUnitType.ALU
    val isBRU = instOutput.bits.inst.fUnitType === FunUnitType.BRU
    val isLSU = instOutput.bits.inst.fUnitType === FunUnitType.MEM

    // PRF Busy Table check for dispatch
    prf.io.readyAddrs(0) := instOutput.bits.inst.prs1
    prf.io.readyAddrs(1) := instOutput.bits.inst.prs2
    val src1Ready = prf.io.isReady(0)
    val src2Ready = prf.io.isReady(1)

    // ALU Issue Buffer Enqueue
    aluIB.io.in.valid := instOutput.valid && isALU && !rob.io.rollback.valid
    aluIB.io.in.bits.robTag := instOutput.bits.robTag
    aluIB.io.in.bits.pdst := instOutput.bits.inst.pdst
    aluIB.io.in.bits.src1 := instOutput.bits.inst.prs1
    aluIB.io.in.bits.src2 := instOutput.bits.inst.prs2
    aluIB.io.in.bits.src1Ready := src1Ready
    aluIB.io.in.bits.src2Ready := Mux(instOutput.bits.inst.useImm, true.B, src2Ready)
    aluIB.io.in.bits.imm := instOutput.bits.inst.imm
    aluIB.io.in.bits.useImm := instOutput.bits.inst.useImm
    aluIB.io.in.bits.info.aluOp := instOutput.bits.inst.aluOpType
    if (Configurables.Elaboration.pcInIssueBuffer) {
        aluIB.io.in.bits.pc.get := instOutput.bits.inst.pc
    }

    // BRU Issue Buffer Enqueue
    bruIB.io.in.valid := instOutput.valid && isBRU && !rob.io.rollback.valid
    bruIB.io.in.bits.robTag := instOutput.bits.robTag
    bruIB.io.in.bits.pdst := instOutput.bits.inst.pdst
    bruIB.io.in.bits.src1 := instOutput.bits.inst.prs1
    bruIB.io.in.bits.src2 := instOutput.bits.inst.prs2
    bruIB.io.in.bits.src1Ready := src1Ready
    bruIB.io.in.bits.src2Ready := src2Ready
    bruIB.io.in.bits.imm := instOutput.bits.inst.imm
    bruIB.io.in.bits.useImm := instOutput.bits.inst.useImm
    bruIB.io.in.bits.info.bruOp := instOutput.bits.inst.bruOpType
    bruIB.io.in.bits.info.cmpOp := instOutput.bits.inst.cmpOpType
    bruIB.io.in.bits.info.pc := instOutput.bits.inst.pc
    bruIB.io.in.bits.info.predict := instOutput.bits.inst.predict
    bruIB.io.in.bits.info.predictedTarget := instOutput.bits.inst.predictedTarget
    bruIB.io.in.bits.info.rasSP := instOutput.bits.rasSP
    if (Configurables.Elaboration.pcInIssueBuffer) {
        bruIB.io.in.bits.pc.get := instOutput.bits.inst.pc
    }

    // LSU Issue Buffer Enqueue
    lsAdaptor.io.issueIn.valid := instOutput.valid && isLSU && !rob.io.rollback.valid
    lsAdaptor.io.issueIn.bits.robTag := instOutput.bits.robTag
    lsAdaptor.io.issueIn.bits.pdst := instOutput.bits.inst.pdst
    lsAdaptor.io.issueIn.bits.src1 := instOutput.bits.inst.prs1
    lsAdaptor.io.issueIn.bits.src2 := instOutput.bits.inst.prs2
    lsAdaptor.io.issueIn.bits.src1Ready := src1Ready
    lsAdaptor.io.issueIn.bits.src2Ready := Mux(
      instOutput.bits.inst.isStore,
      src2Ready,
      true.B
    ) // Not used for loads
    lsAdaptor.io.issueIn.bits.info.opWidth := instOutput.bits.inst.opWidth
    lsAdaptor.io.issueIn.bits.info.isStore := instOutput.bits.inst.isStore
    lsAdaptor.io.issueIn.bits.info.isUnsigned := instOutput.bits.inst.isUnsigned
    lsAdaptor.io.issueIn.bits.info.imm := instOutput.bits.inst.imm
    if (Configurables.Elaboration.pcInIssueBuffer) {
        lsAdaptor.io.issueIn.bits.pc.get := instOutput.bits.inst.pc
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
    prf.io.setBusy.valid := instOutput.valid && instOutput.ready && instOutput.bits.inst.pdst =/= 0.U
    prf.io.setBusy.bits := instOutput.bits.inst.pdst

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

    // Fetcher PC Overwrite (Misprediction or RAS Re-predict)
    when(mispredict) {
        fetcher.io.pcOverwrite.valid := true.B
        fetcher.io.pcOverwrite.bits := Mux(
          brUpdate.taken,
          brUpdate.target,
          brUpdate.pc + 4.U
        )
        rasPredictedValid := false.B // Suppress RAS to prevent double flush
    }.elsewhen(rasPredictedValid && rasPredictedSignal.flush) {
        fetcher.io.pcOverwrite.valid := true.B
        fetcher.io.pcOverwrite.bits := rasPredictedSignal.flushNextPC
    }.otherwise {
        fetcher.io.pcOverwrite.valid := false.B
        fetcher.io.pcOverwrite.bits := 0.U
    }

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

    // --- Profiling ---
    if (Configurables.Profiling.branchMispredictionRate) {
        val totalBranches = WireInit(0.U(32.W))
        val totalMispredicts = WireInit(0.U(32.W))

        BoringUtils.addSink(totalBranches, "total_branches")
        BoringUtils.addSink(totalMispredicts, "branch_mispredictions")
        io.profiler.totalBranches.get := totalBranches
        io.profiler.totalMispredicts.get := totalMispredicts

        dontTouch(totalBranches)
        dontTouch(totalMispredicts)
    }

    if (Configurables.Profiling.IPC) {
        val instructionCount = RegInit(0.U(64.W))
        val cycleCount = RegInit(0.U(64.W))

        when(rob.io.commit.valid && rob.io.commit.ready) {
            instructionCount := instructionCount + 1.U
        }
        cycleCount := cycleCount + 1.U

        io.profiler.totalInstructions.get := instructionCount
        io.profiler.totalCycles.get := cycleCount

        dontTouch(instructionCount)
        dontTouch(cycleCount)
    }

    if (Configurables.Profiling.Utilization) {
        val fetcherBusy = fetcher.io.busy.get
        val decoderBusy = decoder.io.out.valid
        val dispatcherBusy = dispatcher.io.instOutput.valid
        val issueALUBusy = aluIB.io.out.valid
        val issueBRUBusy = bruIB.io.out.valid
        val aluBusy = aluAdaptor.io.busy.get
        val bruBusy = bruAdaptor.io.busy.get
        val lsuBusy = lsAdaptor.io.busy.get
        val writebackBusy = bc.io.broadcastOut.valid
        val robBusy = rob.io.commit.valid

        val fetcherStallBuffer = fetcher.io.stallBuffer.get
        val decoderStallDispatch = decoder.io.out.valid && !dispatcher.io.instInput.ready
        val dispatcherStallFreeList = dispatcher.io.stallFreeList.get
        val dispatcherStallROB = dispatcher.io.stallROB.get
        val dispatcherStallIssue = dispatcher.io.stallIssue.get
        val issueALUStallOperands = aluIB.io.stallOperands.get
        val issueALUStallPort = aluIB.io.stallPort.get
        val issueBRUStallOperands = bruIB.io.stallOperands.get
        val issueBRUStallPort = bruIB.io.stallPort.get
        val lsuStallCommit = lsAdaptor.io.stallCommit.get

        val fetchQueueDepth = ifQueue.io.count
        val issueALUDepth = aluIB.io.count.get
        val issueBRUDepth = bruIB.io.count.get
        val lsuQueueDepth = lsAdaptor.io.lsqCount.get
        val robDepth = rob.io.count.get

        val fetcherBusyCount = RegInit(0.U(32.W))
        val decoderBusyCount = RegInit(0.U(32.W))
        val dispatcherBusyCount = RegInit(0.U(32.W))
        val issueALUBusyCount = RegInit(0.U(32.W))
        val issueBRUBusyCount = RegInit(0.U(32.W))
        val aluBusyCount = RegInit(0.U(32.W))
        val bruBusyCount = RegInit(0.U(32.W))
        val lsuBusyCount = RegInit(0.U(32.W))
        val writebackBusyCount = RegInit(0.U(32.W))
        val robBusyCount = RegInit(0.U(32.W))

        val fetcherStallBufferCount = RegInit(0.U(32.W))
        val decoderStallDispatchCount = RegInit(0.U(32.W))
        val dispatcherStallFreeListCount = RegInit(0.U(32.W))
        val dispatcherStallROBCount = RegInit(0.U(32.W))
        val dispatcherStallIssueCount = RegInit(0.U(32.W))
        val issueALUStallOperandsCount = RegInit(0.U(32.W))
        val issueALUStallPortCount = RegInit(0.U(32.W))
        val issueBRUStallOperandsCount = RegInit(0.U(32.W))
        val issueBRUStallPortCount = RegInit(0.U(32.W))
        val lsuStallCommitCount = RegInit(0.U(32.W))

        val fetchQueueDepthSum = RegInit(0.U(64.W))
        val issueALUDepthSum = RegInit(0.U(64.W))
        val issueBRUDepthSum = RegInit(0.U(64.W))
        val lsuQueueDepthSum = RegInit(0.U(64.W))
        val robDepthSum = RegInit(0.U(64.W))

        // Throughput & Waits
        val countFetcherSum = RegInit(0.U(64.W))
        val countDecoderSum = RegInit(0.U(64.W))
        val countDispatcherSum = RegInit(0.U(64.W))
        val countIssueALUSum = RegInit(0.U(64.W))
        val countIssueBRUSum = RegInit(0.U(64.W))
        val countLSUSum = RegInit(0.U(64.W))
        val countWritebackSum = RegInit(0.U(64.W))
        
        val waitDepALUSum = RegInit(0.U(64.W))
        val waitDepBRUSum = RegInit(0.U(64.W))
        
        // Counter Updates
        when(fetcher.io.ifOut.fire) { countFetcherSum := countFetcherSum + 1.U }
        when(decoder.io.out.fire) { countDecoderSum := countDecoderSum + 1.U }
        when(dispatcher.io.instOutput.fire) { countDispatcherSum := countDispatcherSum + 1.U }
        when(aluIB.io.out.fire) { countIssueALUSum := countIssueALUSum + 1.U }
        when(bruIB.io.out.fire) { countIssueBRUSum := countIssueBRUSum + 1.U }
        // LSU entry: dispatch fires to LSQ
        when(lsAdaptor.io.issueIn.fire) { countLSUSum := countLSUSum + 1.U }
        when(bc.io.broadcastOut.valid) { countWritebackSum := countWritebackSum + 1.U }

        waitDepALUSum := waitDepALUSum + aluIB.io.waitDepCount.get
        waitDepBRUSum := waitDepBRUSum + bruIB.io.waitDepCount.get

        when(fetcherBusy) { fetcherBusyCount := fetcherBusyCount + 1.U }
        when(decoderBusy) { decoderBusyCount := decoderBusyCount + 1.U }
        when(dispatcherBusy) { dispatcherBusyCount := dispatcherBusyCount + 1.U }
        when(issueALUBusy) { issueALUBusyCount := issueALUBusyCount + 1.U }
        when(issueBRUBusy) { issueBRUBusyCount := issueBRUBusyCount + 1.U }
        when(aluBusy) { aluBusyCount := aluBusyCount + 1.U }
        when(bruBusy) { bruBusyCount := bruBusyCount + 1.U }
        when(lsuBusy) { lsuBusyCount := lsuBusyCount + 1.U }
        when(writebackBusy) { writebackBusyCount := writebackBusyCount + 1.U }
        when(robBusy) { robBusyCount := robBusyCount + 1.U }

        when(fetcherStallBuffer) { fetcherStallBufferCount := fetcherStallBufferCount + 1.U }
        when(decoderStallDispatch) { decoderStallDispatchCount := decoderStallDispatchCount + 1.U }
        when(dispatcherStallFreeList) { dispatcherStallFreeListCount := dispatcherStallFreeListCount + 1.U }
        when(dispatcherStallROB) { dispatcherStallROBCount := dispatcherStallROBCount + 1.U }
        when(dispatcherStallIssue) { dispatcherStallIssueCount := dispatcherStallIssueCount + 1.U }
        when(issueALUStallOperands) { issueALUStallOperandsCount := issueALUStallOperandsCount + 1.U }
        when(issueALUStallPort) { issueALUStallPortCount := issueALUStallPortCount + 1.U }
        when(issueBRUStallOperands) { issueBRUStallOperandsCount := issueBRUStallOperandsCount + 1.U }
        when(issueBRUStallPort) { issueBRUStallPortCount := issueBRUStallPortCount + 1.U }
        when(lsuStallCommit) { lsuStallCommitCount := lsuStallCommitCount + 1.U }

        fetchQueueDepthSum := fetchQueueDepthSum + fetchQueueDepth
        issueALUDepthSum := issueALUDepthSum + issueALUDepth
        issueBRUDepthSum := issueBRUDepthSum + issueBRUDepth
        lsuQueueDepthSum := lsuQueueDepthSum + lsuQueueDepth
        robDepthSum := robDepthSum + robDepth

        io.profiler.busyFetcher.get := fetcherBusyCount
        io.profiler.busyDecoder.get := decoderBusyCount
        io.profiler.busyDispatcher.get := dispatcherBusyCount
        io.profiler.busyIssueALU.get := issueALUBusyCount
        io.profiler.busyIssueBRU.get := issueBRUBusyCount
        io.profiler.busyALU.get := aluBusyCount
        io.profiler.busyBRU.get := bruBusyCount
        io.profiler.busyLSU.get := lsuBusyCount
        io.profiler.busyWriteback.get := writebackBusyCount
        io.profiler.busyROB.get := robBusyCount

        io.profiler.fetcherStallBuffer.get := fetcherStallBufferCount
        io.profiler.decoderStallDispatch.get := decoderStallDispatchCount
        io.profiler.dispatcherStallFreeList.get := dispatcherStallFreeListCount
        io.profiler.dispatcherStallROB.get := dispatcherStallROBCount
        io.profiler.dispatcherStallIssue.get := dispatcherStallIssueCount
        io.profiler.issueALUStallOperands.get := issueALUStallOperandsCount
        io.profiler.issueALUStallPort.get := issueALUStallPortCount
        io.profiler.issueBRUStallOperands.get := issueBRUStallOperandsCount
        io.profiler.issueBRUStallPort.get := issueBRUStallPortCount
        io.profiler.lsuStallCommit.get := lsuStallCommitCount
        
        io.profiler.fetchQueueDepth.get := fetchQueueDepthSum
        io.profiler.issueALUDepth.get := issueALUDepthSum
        io.profiler.issueBRUDepth.get := issueBRUDepthSum
        io.profiler.lsuQueueDepth.get := lsuQueueDepthSum
        io.profiler.robDepth.get := robDepthSum

        io.profiler.countFetcher.get := countFetcherSum
        io.profiler.countDecoder.get := countDecoderSum
        io.profiler.countDispatcher.get := countDispatcherSum
        io.profiler.countIssueALU.get := countIssueALUSum
        io.profiler.countIssueBRU.get := countIssueBRUSum
        io.profiler.countLSU.get := countLSUSum
        io.profiler.countWriteback.get := countWritebackSum
        
        io.profiler.waitDepALU.get := waitDepALUSum
        io.profiler.waitDepBRU.get := waitDepBRUSum
        
        dontTouch(fetcherBusyCount)
        dontTouch(decoderBusyCount)
        dontTouch(dispatcherBusyCount)
        dontTouch(issueALUBusyCount)
        dontTouch(issueBRUBusyCount)
        dontTouch(aluBusyCount)
        dontTouch(bruBusyCount)
        dontTouch(lsuBusyCount)
        dontTouch(writebackBusyCount)
        dontTouch(robBusyCount)
    }

    if (Configurables.Profiling.RollbackTime) {
        val rollbackEvents = RegInit(0.U(32.W))
        val rollbackCycles = RegInit(0.U(32.W))

        when(mispredict) { rollbackEvents := rollbackEvents + 1.U }
        when(rob.io.isRollingBack.get) {
            rollbackCycles := rollbackCycles + 1.U
        }

        io.profiler.totalRollbackEvents.get := rollbackEvents
        io.profiler.totalRollbackCycles.get := rollbackCycles

        dontTouch(rollbackEvents)
        dontTouch(rollbackCycles)
    }
}
