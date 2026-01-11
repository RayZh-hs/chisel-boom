package e2e

import chiseltest._
import core.BoomCore
import java.nio.file.{Path, Paths, Files}
import Configurables._

object RunCFile extends App {
    if (args.length < 1) {
        println("Usage: RunCFile <path_to_c_file>")
        sys.exit(1)
    }

    val cFileCandidate = Paths.get(args(0)).toAbsolutePath

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

    RawTester.test(
      new BoomCore(hex.toString),
      Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)
    ) { dut =>
        dut.clock.setTimeout(MAX_CYCLE_COUNT)

        var cycle = 0
        var result: BigInt = 0
        var outputBuffer = scala.collection.mutable.ArrayBuffer[BigInt]()
        var done = false

        println("Simulation started.")
        while (!done && cycle < MAX_CYCLE_COUNT) {
            // if (cycle % 100 == 0) println(s"Cycle: $cycle")
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
