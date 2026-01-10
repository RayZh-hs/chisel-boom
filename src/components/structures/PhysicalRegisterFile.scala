package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._

import common.RegBusyStatus

class PhysicalRegisterFile(numRegs: Int, numReadPorts: Int, numWritePorts: Int, dataWidth: Int) extends Module {
    val io = IO(new Bundle {
        // Read Ports
        val read = Vec(numReadPorts, new Bundle {
            val addr = Input(UInt(log2Ceil(numRegs).W))
            val data = Output(UInt(dataWidth.W))
        })
        // Write Ports
        val write = Vec(numWritePorts, new Bundle {
            val addr = Input(UInt(log2Ceil(numRegs).W))
            val data = Input(UInt(dataWidth.W))
            val en = Input(Bool())
        })
        // Busy Table Interface
        val setBusy = Flipped(Valid(UInt(log2Ceil(numRegs).W)))
        val setReady = Flipped(Valid(UInt(log2Ceil(numRegs).W)))
        val clrBusy = Flipped(Valid(UInt(log2Ceil(numRegs).W)))
        val isReady = Vec(numReadPorts, Output(Bool()))
        val readyAddrs = Vec(numReadPorts, Input(UInt(log2Ceil(numRegs).W)))
    })

    // Register array for data
    val regFile = RegInit(VecInit(Seq.fill(numRegs)(0.U(dataWidth.W))))

    // Busy table (true means the value is being computed, false means it's ready)
    // At reset, all registers are ready (not busy)
    val busyTable = RegInit(VecInit(Seq.fill(numRegs)(false.B)))

    // Read Logic
    for (i <- 0 until numReadPorts) {
        io.read(i).data := regFile(io.read(i).addr)
        io.isReady(i) := !busyTable(io.readyAddrs(i))
    }

    // Write Logic
    for (i <- 0 until numWritePorts) {
        when(io.write(i).en) {
            regFile(io.write(i).addr) := io.write(i).data
        }
    }

    // Busy Table Updates
    // Assertion first - setBusy and setReady should never target same register in same cycle
    when(io.setBusy.valid && io.setReady.valid && (io.setBusy.bits === io.setReady.bits)) {
        chisel3.assert(false.B, "Attempting to set the same register busy and ready in the same cycle! should not happen since free list have delay.")
    }
    
    // setReady first, then setBusy takes precedence (more common to set busy on new dispatch)
    when(io.setReady.valid) {
        busyTable(io.setReady.bits) := false.B
    }
    when(io.clrBusy.valid) {
        busyTable(io.clrBusy.bits) := false.B
    }
    when(io.setBusy.valid) {
        busyTable(io.setBusy.bits) := true.B
    }
    
    // Register 0 is always ready and always 0
    busyTable(0) := false.B
    regFile(0) := 0.U
}
