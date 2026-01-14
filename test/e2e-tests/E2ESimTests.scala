package e2e

import org.scalatest.funsuite.AnyFunSuite
import chiseltest._
import core.BoomCore
import Configurables._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.sys.process.Process

class E2ESimTests extends AnyFunSuite with ChiselScalatestTester {
    import E2EUtils._

    if (sys.props.contains("verbose") || sys.props.contains("v")) {
        common.Configurables.verbose = true
    } else {
        common.Configurables.verbose = false
        common.Configurables.Elaboration.prune()
    }

    if (toolchain.nonEmpty) {
        val simFiles =
            if (Files.isDirectory(simtestsDir))
                Files
                    .list(simtestsDir)
                    .iterator()
                    .asScala
                    .filter(f => f.toString.endsWith(".c") && !shouldSkip(f))
                    .toList
            else Nil

        simFiles.foreach { cFile =>
            val name = cFile.getFileName.toString.stripSuffix(".c")
            test(s"Sim test: $name") {
                val expected = readExpected(cFile)
                val hex = buildHexFor(cFile)

                test(new BoomCore(hex.toString))
                    .withAnnotations(
                      Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)
                    ) { dut =>
                        // Sim tests might need more cycles
                        val maxCycles = MAX_CYCLE_COUNT * 10
                        dut.clock.setTimeout(maxCycles)

                        var cycle = 0
                        var result: BigInt = 0
                        var outputBuffer =
                            scala.collection.mutable.ArrayBuffer[BigInt]()
                        var done = false

                        while (!done && cycle < maxCycles) {
                            // Collect output
                            if (dut.io.put.valid.peek().litToBoolean) {
                                outputBuffer += dut.io.put.bits.data
                                    .peek()
                                    .litValue
                            }

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

                        if (outputBuffer.isEmpty && expected.length == 1) {
                            assert(
                              result == expected.head,
                              s"Expected return value ${expected.head}, got $result (No output captured)"
                            )
                        } else {
                            assert(
                              outputBuffer.toSeq == expected,
                              s"Expected $expected, got ${outputBuffer.toSeq}"
                            )
                        }
                    }
            }
        }
    } else {
        test("Sim tests (skipped)") {
            cancel(
              s"RISC-V toolchain not found. Tried prefixes: riscv32-unknown-elf-, riscv64-unknown-elf-, etc. Set RISCV_BIN (e.g. /opt/riscv/bin/) or put tools on PATH."
            )
        }
    }
}
