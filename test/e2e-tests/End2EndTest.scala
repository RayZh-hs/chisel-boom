import org.scalatest.funsuite.AnyFunSuite
import chiseltest._
import core.BoomCore

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.sys.process.Process

class CProgramSpec extends AnyFunSuite with ChiselScalatestTester {
    private def findRepoRoot(start: Path): Path = {
        var cur = start
        while (cur != null && !Files.exists(cur.resolve("build.mill"))) {
            cur = cur.getParent
        }
        if (cur == null) start else cur
    }

    private val repoRoot: Path = findRepoRoot(
      Paths.get(System.getProperty("user.dir")).toAbsolutePath
    )
    private val cDir: Path = repoRoot.resolve("test/e2e-tests/resources/c")
    private val expectedDir: Path =
        repoRoot.resolve("test/e2e-tests/resources/expected")
    private val linkageDir: Path =
        repoRoot.resolve("test/e2e-tests/resources/linkage")
    private val genDir: Path = repoRoot.resolve("test/e2e-tests/generated")

    private def cmdExists(cmd: String): Boolean = {
        Process(Seq("sh", "-c", s"command -v $cmd >/dev/null 2>&1")).! == 0
    }

    private lazy val toolchain: Option[(String, String)] = {
        val binDir = sys.env.get("RISCV_BIN").map(_ + "/").getOrElse("")
        val prefixes = Seq(
          "riscv32-unknown-elf-",
          "riscv64-unknown-elf-",
          "riscv64-linux-gnu-",
          "riscv32-linux-gnu-"
        )

        prefixes.map(p => (binDir + p + "gcc", binDir + p + "objcopy")).find {
            case (gcc, objcopy) =>
                cmdExists(gcc) && cmdExists(objcopy)
        }
    }

    private def gcc: String =
        toolchain.map(_._1).getOrElse("riscv32-unknown-elf-gcc")
    private def objcopy: String =
        toolchain.map(_._2).getOrElse("riscv32-unknown-elf-objcopy")

    private def readExpected(name: String): BigInt = {
        val p = expectedDir.resolve(s"$name.expected")
        BigInt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim)
    }

    private def writeHexFromBinary(binPath: Path, hexPath: Path): Unit = {
        val bytes = Files.readAllBytes(binPath)
        val paddedLen = ((bytes.length + 3) / 4) * 4
        val padded =
            if (paddedLen == bytes.length) bytes
            else bytes ++ Array.fill[Byte](paddedLen - bytes.length)(0)

        val lines = new StringBuilder
        var i = 0
        while (i < padded.length) {
            val b0 = padded(i) & 0xff
            val b1 = padded(i + 1) & 0xff
            val b2 = padded(i + 2) & 0xff
            val b3 = padded(i + 3) & 0xff
            val word =
                (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)).toLong & 0xffffffffL
            lines.append(f"$word%08x").append('\n')
            i += 4
        }

        Files.write(hexPath, lines.toString.getBytes(StandardCharsets.UTF_8))
    }

    private def buildHexFor(cFile: Path): Path = {
        Files.createDirectories(genDir)
        val name = cFile.getFileName.toString.stripSuffix(".c")

        val elf = genDir.resolve(s"$name.elf")
        val bin = genDir.resolve(s"$name.bin")
        val hex = genDir.resolve(s"$name.hex")

        val linkLd = linkageDir.resolve("link.ld")
        val crt0 = linkageDir.resolve("crt0.S")

        val compileCmd = Seq(
          gcc,
          "-march=rv32i",
          "-mabi=ilp32",
          "-O2",
          "-ffreestanding",
          "-fno-builtin",
          "-T",
          linkLd.toString,
          "-nostdlib",
          "-static",
          crt0.toString,
          cFile.toString,
          "-o",
          elf.toString
        )

        val ccRc = Process(compileCmd, repoRoot.toFile).!
        require(
          ccRc == 0,
          s"Compile failed for ${cFile.getFileName} (rc=$ccRc)"
        )

        val objRc = Process(
          Seq(objcopy, "-O", "binary", elf.toString, bin.toString),
          repoRoot.toFile
        ).!
        require(
          objRc == 0,
          s"objcopy failed for ${cFile.getFileName} (rc=$objRc)"
        )

        writeHexFromBinary(bin, hex)
        hex
    }

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
                    .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
                        dut.clock.setTimeout(200000)

                        var cycle = 0
                        var result: BigInt = 0
                        var done = false

                        while (!done && cycle < 200000) {
                            val mmio =
                                dut.lsAdaptor.memory.mmio.exitDevice.io.req
                            if (
                              mmio.valid
                                  .peek()
                                  .litToBoolean && !mmio.bits.isLoad
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
