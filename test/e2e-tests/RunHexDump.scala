package e2e

import chiseltest._
import core.BoomCore
import java.nio.file.{Path, Paths, Files}
import common.Configurables._
import e2e.Configurables._
import E2EUtils._

object RunHexDump extends App {
    val argList = args.toList
    val verbose = argList.contains("-v") || argList.contains("--verbose")
    val positionalArgs = argList.filterNot(arg => arg.startsWith("-"))

    if (positionalArgs.isEmpty) {
        println("Usage: RunHexDump [options] <path_to_hex_file>")
        println("Options:")
        println("  -v, --verbose    Enable verbose debug output")
        sys.exit(1)
    }

    // Set the global verbose flag
    common.Configurables.verbose = verbose

    val hexFile = Paths.get(positionalArgs.head).toAbsolutePath

    if (!Files.exists(hexFile)) {
        println(s"File not found: $hexFile")
        sys.exit(1)
    }

    println(s"Running simulation using hex file: $hexFile")

    setupSimulation()
    RawTester.test(
      new BoomCore(hexFile.toString),
      Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)
    ) { dut =>
        dut.clock.setTimeout(MAX_CYCLE_COUNT)
        println("Simulation started.")

        val simRes = E2EUtils.runSimulation(dut, MAX_CYCLE_COUNT)

        Thread.sleep(500) // Wait for final prints to flush
        if (!simRes.timedOut) {
            println(s"Simulation finished in ${simRes.cycles} cycles.")
            println(s"Return Code: ${simRes.result}")
            println(s"Output: ${simRes.output.mkString(" ")}")
        } else {
            println(s"Simulation timed out after ${simRes.cycles} cycles.")
            println(s"Output so far: ${simRes.output.mkString(" ")}")
        }
    }
}
