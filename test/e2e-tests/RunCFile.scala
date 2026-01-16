package e2e

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import core.BoomCore
import java.nio.file.{Path, Paths, Files}
import common.Configurables._
import e2e.Configurables._

object RunCFile extends App {
    val argList = args.toList
    val verbose = argList.contains("-v") || argList.contains("--verbose")
    val positionalArgs = argList.filterNot(arg => arg.startsWith("-"))

    if (positionalArgs.isEmpty) {
        println("Usage: RunCFile [options] <path_to_c_file>")
        println("Options:")
        println("  -v, --verbose    Enable verbose debug output")
        sys.exit(1)
    }

    // Set the global verbose flag
    common.Configurables.verbose = verbose

    val cFileCandidate = Paths.get(positionalArgs.head).toAbsolutePath

    if (!Files.exists(cFileCandidate)) {
        println(s"File not found: $cFileCandidate")
        sys.exit(1)
    }

    import E2EUtils._

    if (toolchain.isEmpty) {
        println(
          "RISC-V toolchain not found. Please set RISCV_BIN environment variable."
        )
        sys.exit(1)
    }

    println(s"Compiling ${cFileCandidate}...")
    val hex =
        try {
            buildHexFor(cFileCandidate)
        } catch {
            case e: Exception =>
                println(s"Compilation failed: ${e.getMessage}")
                sys.exit(1)
        }

    println(s"Running simulation using hex file: $hex")
    // In RunCFile.scala
    val fixedHexPath = Paths.get("current_run.hex").toAbsolutePath
    Files.copy(hex, fixedHexPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    
    // Now the Verilog generated for this module is identical across all tests
    simulate(new BoomCore(fixedHexPath.toString)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        var cycle = 0
        var result: BigInt = 0
        var outputBuffer = scala.collection.mutable.ArrayBuffer[BigInt]()
        var done = false

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
