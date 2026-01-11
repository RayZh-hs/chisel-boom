package components.structures

import chisel3._
import chisel3.util._
import common._
import common.Configurables._

/** Register Alias Table (RAT)
  *
  * Manages the mapping between logical registers and physical registers.
  */
class RegisterAliasTable(
    val nReadPorts: Int,
    val nUpdatePorts: Int,
    val nRollbackPorts: Int
) extends Module {
    val io = IO(new Bundle {
        // Read ports for logical to physical mapping
        val readL = Input(Vec(nReadPorts, UInt(5.W)))
        val readP = Output(Vec(nReadPorts, UInt(PREG_WIDTH.W)))

        val update = Vec(nUpdatePorts, Flipped(Valid(new Bundle {
            val ldst = UInt(5.W)
            val pdst = UInt(PREG_WIDTH.W)
        })))
    })

    val rollback = IO(Vec(nRollbackPorts, Flipped(Valid(new Bundle {
        val ldst = UInt(5.W)
        val stalePdst = UInt(PREG_WIDTH.W)
    }))))

    // Map Table: Logical to Physical Register Mapping
    // x0 is always mapped to p0
    val mapTable = RegInit(VecInit(Seq.tabulate(32)(i => i.U(PREG_WIDTH.W))))

    // Read logic
    for (i <- 0 until nReadPorts) {
        io.readP(i) := mapTable(io.readL(i))
    }

    // Update logic
    for (i <- 0 until nUpdatePorts) {
        when(io.update(i).valid && io.update(i).bits.ldst =/= 0.U) {
            mapTable(io.update(i).bits.ldst) := io.update(i).bits.pdst
        }
    }

    // Rollback logic (takes precedence over updates in the same cycle)
    for (i <- 0 until nRollbackPorts) {
        when(rollback(i).valid && rollback(i).bits.ldst =/= 0.U) {
            mapTable(rollback(i).bits.ldst) := rollback(i).bits.stalePdst
        }
    }
}
