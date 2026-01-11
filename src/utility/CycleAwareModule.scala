package utility

import chisel3._
import chisel3.util._
import common.Configurables

// self-import shorthand
import utility.CycleAwareModule.Configurables.{
    MAX_CYCLE_COUNT => MAX_CYCLE_COUNT
}

object CycleAwareModule {
    object Configurables {
        // Maximum cycle count supported by CycleAwareModule in debugging
        val MAX_CYCLE_COUNT = 40000
    }
}

class CycleAwareModule extends Module {
    val cycleCount = RegInit(0.U(log2Ceil(MAX_CYCLE_COUNT).W))
    cycleCount := cycleCount + 1.U

    /** Custom printf that prepends the current cycle count.
      *
      * @param fmt
      *   The C-style format string (e.g., "Value: %d\n")
      * @param args
      *   The hardware signals to print (must be of type Bits/UInt/SInt/etc.)
      */
    def printf(fmt: String, args: Any*): Unit = {
        if (Configurables.verbose) {
            val fmtWithCycle = s"[cycle: %d] $fmt"
            val argsWithCycle =
                Seq(cycleCount) ++ args.map(_.asInstanceOf[Bits])
            chisel3.printf(fmtWithCycle, argsWithCycle: _*)
        }
    }

    // Overload to support the p"..." interpolator style (Idiomatic Chisel)
    def printf(p: Printable): Unit = {
        if (Configurables.verbose) {
            val pWithCycle = p"[cycle: ${Decimal(cycleCount)}] " + p
            chisel3.printf(pWithCycle)
        }
    }
}
