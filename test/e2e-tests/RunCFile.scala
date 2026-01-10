package e2e

import chiseltest._
import core.BoomCore
import java.nio.file.{Path, Paths, Files}

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

    RawTester.test(new BoomCore(hex.toString), Seq(WriteVcdAnnotation)) { dut =>
        dut.clock.setTimeout(200000)

        var cycle = 0
        var result: BigInt = 0
        var done = false

        println("Simulation started.")
        while (!done && cycle < 200000) {
            if (cycle % 1000 == 0) println(s"Cycle: $cycle")
            val mmio = dut.lsAdaptor.memory.mmio.exitDevice.io.req
            if (
              mmio.valid.peek().litToBoolean && !mmio.bits.isLoad
                  .peek()
                  .litToBoolean
            ) {
                result = mmio.bits.data.peek().litValue
                done = true
            } else {
                dut.clock.step(1)
                cycle += 1
            }
        }

        if (done) {
            println(s"Simulation finished in $cycle cycles.")
            println(s"Result: $result")
        } else {
            println(s"Simulation timed out after $cycle cycles.")
        }
    }
}
