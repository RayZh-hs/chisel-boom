package e2e

import org.scalatest.funsuite.AnyFunSuite
import chiseltest._
import core.BoomCore
import Configurables._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.sys.process.Process

class E2ETests extends AnyFunSuite with ChiselScalatestTester {
    import E2EUtils._

    if (toolchain.nonEmpty) {
        val cFiles =
            if (Files.isDirectory(cDir))
                Files
                    .list(cDir)
                    .iterator()
                    .asScala
                    .filter(_.toString.endsWith(".c"))
                    .toList
            else Nil

        cFiles.foreach { cFile =>
            val name = cFile.getFileName.toString.stripSuffix(".c")
            test(s"C test: $name") {
                val expected = readExpected(name)
                val hex = buildHexFor(cFile)

                test(new BoomCore(hex.toString))
                    .withAnnotations(
                      Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)
                    ) { dut =>
                        dut.clock.setTimeout(MAX_CYCLE_COUNT)

                        var cycle = 0
                        var result: BigInt = 0
                        var done = false

                        while (!done && cycle < MAX_CYCLE_COUNT) {
                            // if (cycle % 100 == 0) println(s"Cycle: $cycle")
                            if (dut.io.exit.valid.peek().litToBoolean) {
                                result = dut.io.exit.bits.data.peek().litValue
                                done = true
                            } else {
                                dut.clock.step(1)
                                cycle += 1
                            }
                        }

                        assert(
                          done,
                          s"Simulation timed out after $cycle cycles"
                        )
                        assert(
                          result == expected,
                          s"Expected $expected, got $result"
                        )
                    }
            }
        }
    } else {
        test("C tests (skipped)") {
            cancel(
              s"RISC-V toolchain not found. Tried prefixes: riscv32-unknown-elf-, riscv64-unknown-elf-, etc. Set RISCV_BIN (e.g. /opt/riscv/bin/) or put tools on PATH."
            )
        }
    }
}
