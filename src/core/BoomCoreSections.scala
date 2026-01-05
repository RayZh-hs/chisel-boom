package core

import chisel3._
import chisel3.util._
import utility.CycleAwareModule
import components.frontend._
import components.backend._
import components.structures._
import common._
import common.Configurables._

class Frontend(val hexFile: String) extends CycleAwareModule {
    val io = IO(new Bundle {
        val instOutput = Decoupled(new DecodeToDispatchBundle)
        val robOutput = Decoupled(new DispatchToROBBundle)
        val commit = Flipped(Valid(new ROBEntry))
        val rollback = Flipped(Valid(new Bundle {
            val ldst = UInt(5.W)
            val pdst = UInt(PREG_WIDTH.W)
            val stalePdst = UInt(PREG_WIDTH.W)
        }))
        val pcOverwrite = Flipped(Valid(UInt(32.W)))
    })

    val fetcher = Module(new InstFetcher)
    val decoder = Module(new InstDecoder)
    val dispatcher = Module(new InstDispatcher)
    val imem = Module(new InstructionMemory(hexFile))
    val btb = Module(new BranchTargetBuffer)

    // Fetcher connections
    fetcher.io.pcOverwrite := io.pcOverwrite
    
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
    io.instOutput <> dispatcher.io.instOutput
    io.robOutput <> dispatcher.io.robOutput
    dispatcher.io.commit := io.commit
    dispatcher.io.rollback := io.rollback
}

class Backend extends CycleAwareModule {
    val io = IO(new Bundle {
        val instInput = Flipped(Decoupled(new DecodeToDispatchBundle))
        val robInput = Flipped(Decoupled(new DispatchToROBBundle))
        val commit = Decoupled(new ROBEntry)
        val rollback = Output(Valid(new Bundle {
            val ldst = UInt(5.W)
            val pdst = UInt(PREG_WIDTH.W)
            val stalePdst = UInt(PREG_WIDTH.W)
        }))
        val pcOverwrite = Output(Valid(UInt(32.W)))
    })

    val rob = Module(new ReOrderBuffer)
    val aluIB = Module(new IssueBuffer(new ALUInfo, 8))
    val bruIB = Module(new IssueBuffer(new BRUInfo, 4))
    val aluAdaptor = Module(new ALUAdaptor)
    val bruAdaptor = Module(new BRUAdaptor)
    val prf = Module(new PhysicalRegisterFile(Derived.PREG_COUNT))
    val bc = Module(new BroadcastChannel)

    // ROB connections
    rob.io.dispatch <> io.robInput
    io.commit <> rob.io.commit
    io.rollback := rob.io.rollback

    // Dispatch to Issue Buffers
    val isALU = io.instInput.bits.fUnitType === FunUnitType.ALU
    val isBRU = io.instInput.bits.fUnitType === FunUnitType.BRU

    // PRF Busy Table check for dispatch
    prf.io.readyAddrs(0) := io.instInput.bits.prs1
    prf.io.readyAddrs(1) := io.instInput.bits.prs2
    val src1Ready = prf.io.isReady(0)
    val src2Ready = prf.io.isReady(1)

    // ALU Issue Buffer Enqueue
    aluIB.io.in.valid := io.instInput.valid && isALU && !rob.io.rollback.valid
    aluIB.io.in.bits.robTag := rob.io.robTag
    aluIB.io.in.bits.pdst := io.instInput.bits.pdst
    aluIB.io.in.bits.src1 := io.instInput.bits.prs1
    aluIB.io.in.bits.src2 := io.instInput.bits.prs2
    aluIB.io.in.bits.src1Ready := src1Ready
    aluIB.io.in.bits.src2Ready := src2Ready
    aluIB.io.in.bits.imm := io.instInput.bits.imm
    aluIB.io.in.bits.useImm := io.instInput.bits.useImm
    aluIB.io.in.bits.info.aluOp := io.instInput.bits.aluOpType

    // BRU Issue Buffer Enqueue
    bruIB.io.in.valid := io.instInput.valid && isBRU && !rob.io.rollback.valid
    bruIB.io.in.bits.robTag := rob.io.robTag
    bruIB.io.in.bits.pdst := io.instInput.bits.pdst
    bruIB.io.in.bits.src1 := io.instInput.bits.prs1
    bruIB.io.in.bits.src2 := io.instInput.bits.prs2
    bruIB.io.in.bits.src1Ready := src1Ready
    bruIB.io.in.bits.src2Ready := src2Ready
    bruIB.io.in.bits.imm := io.instInput.bits.imm
    bruIB.io.in.bits.useImm := io.instInput.bits.useImm
    bruIB.io.in.bits.info.bruOp := io.instInput.bits.bruOpType
    bruIB.io.in.bits.info.cmpOp := io.instInput.bits.cmpOpType
    bruIB.io.in.bits.info.pc := io.instInput.bits.pc
    bruIB.io.in.bits.info.predict := io.instInput.bits.predict
    bruIB.io.in.bits.info.predictedTarget := io.instInput.bits.predictedTarget

    io.instInput.ready := Mux(isALU, aluIB.io.in.ready, Mux(isBRU, bruIB.io.in.ready, false.B)) && rob.io.dispatch.ready

    // PRF Busy Table Update (Set Busy on Dispatch)
    prf.io.setBusy.valid := io.instInput.valid && io.instInput.ready && io.instInput.bits.pdst =/= 0.U
    prf.io.setBusy.bits := io.instInput.bits.pdst

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

    io.pcOverwrite.valid := mispredict
    io.pcOverwrite.bits := Mux(brUpdate.taken, brUpdate.target, brUpdate.pc + 4.U)

    // Flush logic
    aluIB.io.flush := mispredict
    bruIB.io.flush := mispredict
    rob.io.flush := false.B 

    // Unused PRF readyAddrs
    prf.io.readyAddrs(2) := 0.U
    prf.io.readyAddrs(3) := 0.U
}

object BoomCoreSections {
    def connectFrontendBackend(frontend: Frontend, backend: Backend): Unit = {
        backend.io.instInput <> frontend.io.instOutput
        backend.io.robInput <> frontend.io.robOutput
        frontend.io.commit := backend.io.commit
        frontend.io.rollback := backend.io.rollback
        frontend.io.pcOverwrite := backend.io.pcOverwrite
    }
}
