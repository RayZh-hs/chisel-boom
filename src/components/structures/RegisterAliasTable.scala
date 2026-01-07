package components.structures

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

/** Register Alias Table (RAT)
  *
  * Manages the mapping between logical registers and physical registers.
  */
class RegisterAliasTable extends Module {
    val io = IO(new Bundle {
        // Read/Update port for Dispatcher
        val lrs1 = Input(UInt(5.W))
        val lrs2 = Input(UInt(5.W))
        val ldst = Input(UInt(5.W))
        
        val prs1 = Output(UInt(PREG_WIDTH.W))
        val prs2 = Output(UInt(PREG_WIDTH.W))
        val stalePdst = Output(UInt(PREG_WIDTH.W))

        val update = Flipped(Valid(new Bundle {
            val ldst = UInt(5.W)
            val pdst = UInt(PREG_WIDTH.W)
        }))
    })

    val rollback = IO(Flipped(Valid(new Bundle {
        val ldst = UInt(5.W)
        val stalePdst = UInt(PREG_WIDTH.W)
    })))

    // Map Table: Logical to Physical Register Mapping
    // x0 is always mapped to p0
    val mapTable = RegInit(VecInit(Seq.tabulate(32)(i => i.U(PREG_WIDTH.W))))

    // Read logic
    io.prs1 := mapTable(io.lrs1)
    io.prs2 := mapTable(io.lrs2)
    io.stalePdst := mapTable(io.ldst)

    // Update logic
    when(rollback.valid) {
        // Rollback takes precedence
        mapTable(rollback.bits.ldst) := rollback.bits.stalePdst
    }.elsewhen(io.update.valid && io.update.bits.ldst =/= 0.U) {
        mapTable(io.update.bits.ldst) := io.update.bits.pdst
    }
}
