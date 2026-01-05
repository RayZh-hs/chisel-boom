package components.structures

import chisel3._
import chisel3.util._
import common.Configurables._

import common.RegBusyStatus

class PhysicalRegisterFile(numRegs: Int) extends Module {
    val io = IO(new Bundle {
        // Read Ports
        val read = Vec(4, new Bundle {
            val addr = Input(UInt(log2Ceil(numRegs).W))
            val data = Output(UInt(32.W))
        })
        // Write Ports
        val write = Vec(2, new Bundle {
            val addr = Input(UInt(log2Ceil(numRegs).W))
            val data = Input(UInt(32.W))
            val en = Input(Bool())
        })
        // Busy Table Interface
        val setBusy = Flipped(Valid(UInt(log2Ceil(numRegs).W)))
        val setReady = Flipped(Valid(UInt(log2Ceil(numRegs).W)))
        val isReady = Vec(4, Output(Bool()))
        val readyAddrs = Vec(4, Input(UInt(log2Ceil(numRegs).W)))
    })

    // Register array for data
    val regFile = RegInit(VecInit(Seq.fill(numRegs)(0.U(32.W))))

    // Busy table (true means the value is being computed, false means it's ready)
    // At reset, all registers are ready (not busy)
    val busyTable = RegInit(VecInit(Seq.fill(numRegs)(false.B)))

    // Read Logic
    for (i <- 0 until 4) {
        io.read(i).data := regFile(io.read(i).addr)
        io.isReady(i) := !busyTable(io.readyAddrs(i))
    }

    // Write Logic
    for (i <- 0 until 2) {
        when(io.write(i).en) {
            regFile(io.write(i).addr) := io.write(i).data
        }
    }

    // Busy Table Updates
    // setBusy has priority over setReady if they happen to the same register (unlikely in this design)
    when(io.setBusy.valid) {
        busyTable(io.setBusy.bits) := true.B
    }
    when(io.setReady.valid) {
        busyTable(io.setReady.bits) := false.B
    }
    
    // Register 0 is always ready and always 0
    busyTable(0) := false.B
    regFile(0) := 0.U
}
