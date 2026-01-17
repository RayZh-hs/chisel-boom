package components.backend

import chisel3._
import chisel3.util._
import common._
import components.structures.IssueBufferEntry

/**
 * A generic pipeline stage that:
 * 1. Accepts an IssueBufferEntry
 * 2. Reads operands from the PRF (combinational read based on input)
 * 3. Latches the entry and the read data
 * 4. Handle Flushes
 */
class OperandFetchStage[T <: Data](gen: T) extends Module {
    val io = IO(new Bundle {
        // Input from Issue Buffer
        val issueIn = Flipped(Decoupled(new IssueBufferEntry(gen.cloneType)))
        
        // Output to Execution (Decoupled to handle backpressure)
        val out = Decoupled(new Bundle {
            val info = new IssueBufferEntry(gen.cloneType)
            val op1  = UInt(32.W)
            val op2  = UInt(32.W)
        })

        // Interface to PRF
        val prfRead = new PRFReadBundle
        
        // Global signals
        val flush = Input(new FlushBundle)
        val busy  = Output(Bool())
    })

    // --- Combinational Read Setup ---
    // We immediately assert the addresses to the PRF so data is ready for the register latch
    io.prfRead.addr1 := io.issueIn.bits.src1
    io.prfRead.addr2 := io.issueIn.bits.src2

    // --- State Registers ---
    val validReg = RegInit(false.B)
    val infoReg  = Reg(new IssueBufferEntry(gen.cloneType))
    val op1Reg   = Reg(UInt(32.W))
    val op2Reg   = Reg(UInt(32.W))

    // --- Pipeline Control ---
    // We can accept new input if the output is ready to accept our current data, 
    // or if we don't have valid data currently.
    val ready = io.out.ready || !validReg
    io.issueIn.ready := ready

    when(ready) {
        // Latch logic
        val kill = io.flush.checkKilled(io.issueIn.bits.robTag)
        validReg := io.issueIn.fire && !kill
        
        when(io.issueIn.fire) {
            infoReg := io.issueIn.bits
            op1Reg  := io.prfRead.data1
            op2Reg  := io.prfRead.data2
        }
    }.otherwise {
        // If stalled, check if the current held instruction gets flushed
        when(io.flush.checkKilled(infoReg.robTag)) {
            validReg := false.B
        }
    }

    // --- Outputs ---
    io.out.valid     := validReg && !io.flush.checkKilled(infoReg.robTag)
    io.out.bits.info := infoReg
    io.out.bits.op1  := op1Reg
    io.out.bits.op2  := op2Reg
    
    io.busy := validReg
}