package e2e

import chiseltest._
import core.BoomCore
import java.nio.file.{Path, Paths, Files}
import common.Configurables._
import e2e.Configurables._

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

    RawTester.test(
      new BoomCore(hexFile.toString),
      Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)
    ) { dut =>
        dut.clock.setTimeout(MAX_CYCLE_COUNT)

        var cycle = 0
        var result: BigInt = 0
        var outputBuffer = scala.collection.mutable.ArrayBuffer[BigInt]()
        var done = false

        println("Simulation started.")
        while (!done && cycle < MAX_CYCLE_COUNT) {
            if (dut.io.put.valid.peek().litToBoolean) {
                outputBuffer += dut.io.put.bits.data.peek().litValue
            }
            if (dut.io.exit.valid.peek().litToBoolean) {
                result = dut.io.exit.bits.data.peek().litValue
                done = true
            } else {
                dut.clock.step(1)
                cycle += 1
            }
        }

        Thread.sleep(500) // Wait for final prints to flush
        if (done) {
            println(s"Simulation finished in $cycle cycles.")
            println(s"Return Code: $result")
            println(s"Output: ${outputBuffer.mkString(" ")}")
        } else {
            println(s"Simulation timed out after $cycle cycles.")
            println(s"Output so far: ${outputBuffer.mkString(" ")}")
        }
    }
}
