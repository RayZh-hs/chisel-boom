package e2e

import org.scalatest.funsuite.AnyFunSuite
import Configurables._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import firrtl.options.TargetDirAnnotation

class E2ETests extends AnyFunSuite {
    import E2EUtils._

    if (sys.props.contains("verbose") || sys.props.contains("v")) {
        common.Configurables.verbose = true
    }

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

                val simRes = runTestWithHex(sourceHex)

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
    } else {
        test("C tests (skipped)") {
            cancel(
              s"RISC-V toolchain not found. Tried prefixes: riscv32-unknown-elf-, riscv64-unknown-elf-, etc. Set RISCV_BIN (e.g. /opt/riscv/bin/) or put tools on PATH."
            )
        }
    }
}
