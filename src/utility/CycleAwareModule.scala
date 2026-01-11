package utility

import chisel3._
import chisel3.util._

class CycleAwareModule extends Module {
    val cycleCount = RegInit(0.U(64.W))
    cycleCount := cycleCount + 1.U

    /** Custom printf that prepends the current cycle count.
      *
      * @param fmt
      *   The C-style format string (e.g., "Value: %d\n")
      * @param args
      *   The hardware signals to print (must be of type Bits/UInt/SInt/etc.)
      */
    def printf(fmt: String, args: Any*): Unit = {
        val fmtWithCycle = s"[Cycle=%d] $fmt"
        val argsWithCycle = Seq(cycleCount) ++ args.map(_.asInstanceOf[Bits])
        chisel3.printf(fmtWithCycle, argsWithCycle: _*)
    }

    // Overload to support the p"..." interpolator style (Idiomatic Chisel)
    def printf(p: Printable): Unit = {
        val pWithCycle = p"[cycle=${Decimal(cycleCount)}] " + p
        chisel3.printf(pWithCycle)
    }
}
