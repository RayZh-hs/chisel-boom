package core

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utility.CycleAwareModule
import components.frontend._
import components.backend._
import components.structures._
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
    val rat = Module(new RegisterAliasTable(3, 1, 2))
    val freeList = Module(new FreeList(Derived.PREG_COUNT, 32))
    val imem = Module(new InstructionMemory(hexFile))
    val btb = Module(new BranchTargetBuffer)

    val rob = Module(new ReOrderBuffer)
    val aluIB = Module(new IssueBuffer(new ALUInfo, 16, "ALU_IB"))
    val multIB = Module(new IssueBuffer(new MultInfo, 8, "MULT_IB"))
    val bruIB = Module(new IssueBuffer(new BRUInfo, 16, "BRU_IB"))
    val aluAdaptor = Module(new ALUAdaptor)
    val multAdaptor = Module(new MultAdaptor)
    val bruAdaptor = Module(new BRUAdaptor)

    // Memory Subsystem and MMIO Devices
    val lsu = Module(new LoadStoreUnit)
    val printDevice = Module(new PrintDevice)
    val exitDevice = Module(new ExitDevice)
    val mmio = Module(new MMIORouter(Seq("h80000000".U, "hFFFFFFFF".U)))
    val memory = Module(new MemorySubsystem)
    val lsAdaptor = Module(new LoadStoreAdaptor)

    // Generic memory routing: Adaptor -> MemorySubsystem -> (LSU | MMIO)
    memory.io.upstream <> lsAdaptor.io.mem
    lsu.io <> memory.io.lsu
    mmio.io.upstream <> memory.io.mmio

    printDevice.io <> mmio.io.devices(0)
    exitDevice.io <> mmio.io.devices(1)

    // Expose exit device to top level
    io.exit.valid := exitDevice.io.req.valid && !exitDevice.io.req.bits.isLoad
    io.exit.bits := exitDevice.io.req.bits

    io.put.valid := printDevice.io.req.valid && !printDevice.io.req.bits.isLoad
    io.put.bits := printDevice.io.req.bits

    val prf = Module(new PhysicalRegisterFile(Derived.PREG_COUNT, 8, 1, 32))
    val bc = Module(new BroadcastChannel)

    // --- Frontend Wiring ---

    // BTB integration
    btb.io.pc := fetcher.io.instAddr
    fetcher.io.btbResult := btb.io.target

    // Memory interface for fetcher
    imem.io.addr := fetcher.io.instAddr
    fetcher.io.instData := imem.io.inst

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
    
    // DispatchRouting Module
    val dispatchRouter = Module(new DispatchRouter)

    dispatcherInstQueue.reset := reset.asBool || backendMispredict

    // Router Inputs
    dispatchRouter.io.instInput.valid := dispatcherInstQueue.io.deq.valid
    dispatchRouter.io.instInput.bits.inst := dispatcherInstQueue.io.deq.bits.inst
    dispatchRouter.io.instInput.bits.rasSP := dispatcherInstQueue.io.deq.bits.rasSP
    dispatcherInstQueue.io.deq.ready := dispatchRouter.io.instInput.ready

    dispatchRouter.io.robTagIn := dispatcherInstQueue.io.deq.bits.robTag
    dispatchRouter.io.robDispatchReady := rob.io.dispatch.ready
    dispatchRouter.io.rollbackValid := rob.io.rollback(0).valid
    
    // PRF Ready for Dispatch Routing
    prf.io.readyAddrs(0) := dispatcherInstQueue.io.deq.bits.inst.prs1
    prf.io.readyAddrs(1) := dispatcherInstQueue.io.deq.bits.inst.prs2
    dispatchRouter.io.prfReady(0) := prf.io.isReady(0)
    dispatchRouter.io.prfReady(1) := prf.io.isReady(1)

    // Router Outputs -> IBs
    aluIB.io.in <> dispatchRouter.io.aluIB
    multIB.io.in <> dispatchRouter.io.multIB
    bruIB.io.in <> dispatchRouter.io.bruIB
    lsAdaptor.io.issueIn <> dispatchRouter.io.lsuIB

    // PRF Busy Table Update (Set Busy on Dispatch)
    prf.io.setBusy.valid := dispatchRouter.io.setBusy.valid
    prf.io.setBusy.bits := dispatchRouter.io.setBusy.bits

    // Issue Buffer to Adaptor connections
    aluAdaptor.io.issueIn <> aluIB.io.out
    multAdaptor.io.issueIn <> multIB.io.out
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
    

    // Mult uses ports 6, 7
    prf.io.read(6).addr := multAdaptor.io.prfRead.addr1
    multAdaptor.io.prfRead.data1 := prf.io.read(6).data
    prf.io.read(7).addr := multAdaptor.io.prfRead.addr2
    multAdaptor.io.prfRead.data2 := prf.io.read(7).data

    // Adaptor to PRF Write connections (Unified via Broadcast Channel)
    prf.io.write(0).addr := bc.io.broadcastOut.bits.pdst
    prf.io.write(0).data := bc.io.broadcastOut.bits.data
    prf.io
        .write(0)
        .en := bc.io.broadcastOut.valid && bc.io.broadcastOut.bits.writeEn

    // Broadcast Channel connections
    bc.io.aluResult <> aluAdaptor.io.broadcastOut
    bc.io.multResult <> multAdaptor.io.broadcastOut
    bc.io.bruResult <> bruAdaptor.io.broadcastOut
    bc.io.memResult <> lsAdaptor.io.broadcastOut

    // Broadcast to everything
    val broadcast = bc.io.broadcastOut
    aluIB.io.broadcast := broadcast
    multIB.io.broadcast := broadcast
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
    multIB.io.flush := flushCtrl

    aluAdaptor.io.flush := flushCtrl
    multAdaptor.io.flush := flushCtrl
    bruAdaptor.io.flush := flushCtrl
    lsAdaptor.io.flush := flushCtrl

    rob.io.flush := false.B

    // Unused PRF readyAddrs
    prf.io.readyAddrs(2) := 0.U
    prf.io.readyAddrs(3) := 0.U
    prf.io.readyAddrs(4) := 0.U
    prf.io.readyAddrs(5) := 0.U
    prf.io.readyAddrs(6) := 0.U
    prf.io.readyAddrs(7) := 0.U

    // --- Commit and Rollback logic for renaming ---

    val commit = rob.io.commit
    val rollback = rob.io.rollback

    freeList.io.free.valid := rob.io.commit.valid && (rob.io.commit.bits.ldst =/= 0.U)
    freeList.io.free.bits := rob.io.commit.bits.stalePdst
    commit.ready := freeList.io.free.ready

    for (i <- 0 until 2) {
        rat.rollback(i).valid := rollback(i).valid
        rat.rollback(i).bits.ldst := rollback(i).bits.ldst
        rat.rollback(i).bits.stalePdst := rollback(i).bits.stalePdst

        freeList.io.rollbackFree(i).valid := rollback(i).valid && (rollback(i).bits.ldst =/= 0.U)
        freeList.io.rollbackFree(i).bits := rollback(i).bits.pdst

        prf.io.clrBusy(i).valid := rollback(i).valid && (rollback(i).bits.pdst =/= 0.U)
        prf.io.clrBusy(i).bits := rollback(i).bits.pdst
    }

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
        val issueMultBusy = multIB.io.out.valid
        val aluBusy = aluAdaptor.io.busy.get
        val bruBusy = bruAdaptor.io.busy.get
        val multBusy = multAdaptor.io.busy.get
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
        val issueMultStallOperands = multIB.io.stallOperands.get
        val issueMultStallPort = multIB.io.stallPort.get
        val lsuStallCommit = lsAdaptor.io.stallCommit.get

        val fetchQueueDepth = ifQueue.io.count
        val issueALUDepth = aluIB.io.count.get
        val issueBRUDepth = bruIB.io.count.get
        val issueMultDepth = multIB.io.count.get
        val lsuQueueDepth = lsAdaptor.io.lsqCount.get
        val robDepth = rob.io.count.get

        val fetcherBusyCount = RegInit(0.U(32.W))
        val decoderBusyCount = RegInit(0.U(32.W))
        val dispatcherBusyCount = RegInit(0.U(32.W))
        val issueALUBusyCount = RegInit(0.U(32.W))
        val issueBRUBusyCount = RegInit(0.U(32.W))
        val issueMultBusyCount = RegInit(0.U(32.W))
        val aluBusyCount = RegInit(0.U(32.W))
        val bruBusyCount = RegInit(0.U(32.W))
        val multBusyCount = RegInit(0.U(32.W))
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
        val issueMultStallOperandsCount = RegInit(0.U(32.W))
        val issueMultStallPortCount = RegInit(0.U(32.W))
        val lsuStallCommitCount = RegInit(0.U(32.W))

        val fetchQueueDepthSum = RegInit(0.U(64.W))
        val issueALUDepthSum = RegInit(0.U(64.W))
        val issueBRUDepthSum = RegInit(0.U(64.W))
        val issueMultDepthSum = RegInit(0.U(64.W))
        val lsuQueueDepthSum = RegInit(0.U(64.W))
        val robDepthSum = RegInit(0.U(64.W))

        // Throughput & Waits
        val countFetcherSum = RegInit(0.U(64.W))
        val countDecoderSum = RegInit(0.U(64.W))
        val countDispatcherSum = RegInit(0.U(64.W))
        val countIssueALUSum = RegInit(0.U(64.W))
        val countIssueBRUSum = RegInit(0.U(64.W))
        val countIssueMultSum = RegInit(0.U(64.W))
        val countLSUSum = RegInit(0.U(64.W))
        val countWritebackSum = RegInit(0.U(64.W))
        
        val waitDepALUSum = RegInit(0.U(64.W))
        val waitDepBRUSum = RegInit(0.U(64.W))
        val waitDepMultSum = RegInit(0.U(64.W))
        
        // Counter Updates
        when(fetcher.io.ifOut.fire) { countFetcherSum := countFetcherSum + 1.U }
        when(decoder.io.out.fire) { countDecoderSum := countDecoderSum + 1.U }
        when(dispatcher.io.instOutput.fire) { countDispatcherSum := countDispatcherSum + 1.U }
        when(aluIB.io.out.fire) { countIssueALUSum := countIssueALUSum + 1.U }
        when(bruIB.io.out.fire) { countIssueBRUSum := countIssueBRUSum + 1.U }
        when(multIB.io.out.fire) { countIssueMultSum := countIssueMultSum + 1.U }
        // LSU entry: dispatch fires to LSQ
        when(lsAdaptor.io.issueIn.fire) { countLSUSum := countLSUSum + 1.U }
        when(bc.io.broadcastOut.valid) { countWritebackSum := countWritebackSum + 1.U }

        waitDepALUSum := waitDepALUSum + aluIB.io.waitDepCount.get
        waitDepBRUSum := waitDepBRUSum + bruIB.io.waitDepCount.get
        waitDepMultSum := waitDepMultSum + multIB.io.waitDepCount.get

        when(fetcherBusy) { fetcherBusyCount := fetcherBusyCount + 1.U }
        when(decoderBusy) { decoderBusyCount := decoderBusyCount + 1.U }
        when(dispatcherBusy) { dispatcherBusyCount := dispatcherBusyCount + 1.U }
        when(issueALUBusy) { issueALUBusyCount := issueALUBusyCount + 1.U }
        when(issueBRUBusy) { issueBRUBusyCount := issueBRUBusyCount + 1.U }
        when(issueMultBusy) { issueMultBusyCount := issueMultBusyCount + 1.U }
        when(aluBusy) { aluBusyCount := aluBusyCount + 1.U }
        when(bruBusy) { bruBusyCount := bruBusyCount + 1.U }
        when(multBusy) { multBusyCount := multBusyCount + 1.U }
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
        when(issueMultStallOperands) { issueMultStallOperandsCount := issueMultStallOperandsCount + 1.U }
        when(issueMultStallPort) { issueMultStallPortCount := issueMultStallPortCount + 1.U }
        when(lsuStallCommit) { lsuStallCommitCount := lsuStallCommitCount + 1.U }

        fetchQueueDepthSum := fetchQueueDepthSum + fetchQueueDepth
        issueALUDepthSum := issueALUDepthSum + issueALUDepth
        issueBRUDepthSum := issueBRUDepthSum + issueBRUDepth
        issueMultDepthSum := issueMultDepthSum + issueMultDepth
        lsuQueueDepthSum := lsuQueueDepthSum + lsuQueueDepth
        robDepthSum := robDepthSum + robDepth

        io.profiler.busyFetcher.get := fetcherBusyCount
        io.profiler.busyDecoder.get := decoderBusyCount
        io.profiler.busyDispatcher.get := dispatcherBusyCount
        io.profiler.busyIssueALU.get := issueALUBusyCount
        io.profiler.busyIssueBRU.get := issueBRUBusyCount
        io.profiler.busyIssueMult.get := issueMultBusyCount
        io.profiler.busyALU.get := aluBusyCount
        io.profiler.busyBRU.get := bruBusyCount
        io.profiler.busyMult.get := multBusyCount
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
        io.profiler.issueMultStallOperands.get := issueMultStallOperandsCount
        io.profiler.issueMultStallPort.get := issueMultStallPortCount
        io.profiler.lsuStallCommit.get := lsuStallCommitCount
        
        io.profiler.fetchQueueDepth.get := fetchQueueDepthSum
        io.profiler.issueALUDepth.get := issueALUDepthSum
        io.profiler.issueBRUDepth.get := issueBRUDepthSum
        io.profiler.issueMultDepth.get := issueMultDepthSum
        io.profiler.lsuQueueDepth.get := lsuQueueDepthSum
        io.profiler.robDepth.get := robDepthSum

        io.profiler.countFetcher.get := countFetcherSum
        io.profiler.countDecoder.get := countDecoderSum
        io.profiler.countDispatcher.get := countDispatcherSum
        io.profiler.countIssueALU.get := countIssueALUSum
        io.profiler.countIssueBRU.get := countIssueBRUSum
        io.profiler.countIssueMult.get := countIssueMultSum
        io.profiler.countLSU.get := countLSUSum
        io.profiler.countWriteback.get := countWritebackSum
        
        io.profiler.waitDepALU.get := waitDepALUSum
        io.profiler.waitDepBRU.get := waitDepBRUSum
        io.profiler.waitDepMult.get := waitDepMultSum
        
        dontTouch(fetcherBusyCount)
        dontTouch(decoderBusyCount)
        dontTouch(dispatcherBusyCount)
        dontTouch(issueALUBusyCount)
        dontTouch(issueBRUBusyCount)
        dontTouch(issueMultBusyCount)
        dontTouch(aluBusyCount)
        dontTouch(bruBusyCount)
        dontTouch(multBusyCount)
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
