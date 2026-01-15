package e2e

import org.scalatest.funsuite.AnyFunSuite
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import core.BoomCore
import Configurables._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import firrtl.options.TargetDirAnnotation

class E2ETests extends AnyFunSuite {
    import E2EUtils._

    if (sys.props.contains("verbose") || sys.props.contains("v")) {
        common.Configurables.verbose = true
    }

    // Shared path for the hex file to avoid recompilation of BoomCore
    // We place it outside the specific test run directory so it persists/is accessible
    private val sharedHexPath = genDir.resolve("shared_program.hex")

    if (toolchain.nonEmpty) {
        val cFiles =
            if (Files.isDirectory(cDir))
                Files
                    .list(cDir)
                    .iterator()
                    .asScala
                    .filter(f => f.toString.endsWith(".c") && !shouldSkip(f))
                    .toList
            else Nil

        cFiles.foreach { cFile =>
            val name = cFile.getFileName.toString.stripSuffix(".c")
            test(s"C test: $name") {
                val expected = readExpected(cFile)
                val sourceHex = buildHexFor(cFile)
                
                // Copy the specific test's hex to the shared location
                // This allows the pre-compiled BoomCore to read "shared_program.hex"
                // which is effectively swapped out for each test case
                Files.copy(sourceHex, sharedHexPath, StandardCopyOption.REPLACE_EXISTING)
                
                // Use the shared path for the reused core instance
                simulate(new BoomCore(sharedHexPath.toAbsolutePath.toString)) { dut =>
                        dut.reset.poke(true.B)
                        dut.clock.step()
                        dut.reset.poke(false.B)

                        val simRes =
                            E2EUtils.runSimulation(dut, MAX_CYCLE_COUNT)

                        assert(
                          !simRes.timedOut,
                          s"Simulation timed out after ${simRes.cycles} cycles"
                        )

                        if (simRes.output.isEmpty && expected.length == 1) {
                            assert(
                              simRes.result == expected.head,
                              s"Expected return value ${expected.head}, got ${simRes.result} (No output captured)"
                            )
                        } else {
                            assert(
                              simRes.output == expected,
                              s"Expected $expected, got ${simRes.output}"
                            )
                        }
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
