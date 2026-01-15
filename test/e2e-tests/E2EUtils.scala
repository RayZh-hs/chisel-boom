package e2e

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import core.BoomCore

object E2EUtils {
    // Flag to detect if running in CI environment
    val isCI: Boolean =
        sys.env.get("CI").contains("true") || sys.env.contains("GITHUB_ACTIONS")

    def findRepoRoot(start: Path): Path = {
        var cur = start
        while (cur != null && !Files.exists(cur.resolve("build.mill"))) {
            cur = cur.getParent
        }
        if (cur == null) start else cur
    }

    val repoRoot: Path = findRepoRoot(
      Paths.get(System.getProperty("user.dir")).toAbsolutePath
    )
    val cDir: Path = repoRoot.resolve("test/e2e-tests/resources/c")
    val expectedDir: Path =
        repoRoot.resolve("test/e2e-tests/resources/expected")
    val linkageDir: Path = repoRoot.resolve("test/e2e-tests/resources/linkage")
    val genDir: Path = repoRoot.resolve("test/e2e-tests/generated")
    val simtestsDir: Path = cDir.resolve("simtests")
    val skipJson: Path = repoRoot.resolve("test/e2e-tests/resources/skip.jsonc")

    lazy val skipList: Set[String] = {
        if (Files.exists(skipJson)) {
            val content = Files.readString(skipJson, StandardCharsets.UTF_8)
            // Remove block comments and line comments, then extract all quoted strings.
            val withoutBlock = content.replaceAll("(?s)/\\*.*?\\*/", "")
            val withoutLine = withoutBlock.replaceAll("(?m)//.*$", "")
            val strRegex = "\"([^\"]*)\"".r
            strRegex.findAllMatchIn(withoutLine).map(_.group(1)).toSet
        } else {
            Set.empty
        }
    }

    def shouldSkip(cFile: Path): Boolean = {
        val rel = cDir.relativize(cFile).toString
        val noExt = rel.stripSuffix(".c")
        skipList.contains(noExt) || skipList.contains(
          rel
        ) // Check both with and without extension
    }

    def cmdExists(cmd: String): Boolean = {
        Process(Seq("sh", "-c", s"command -v $cmd >/dev/null 2>&1")).! == 0
    }

    lazy val toolchain: Option[(String, String)] = {
        val binDir = sys.env.get("RISCV_BIN").map(_ + "/").getOrElse("")
        val prefixes = Seq(
          "riscv-none-elf-",
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

    def gcc: String = toolchain.map(_._1).getOrElse("riscv32-unknown-elf-gcc")
    def objcopy: String =
        toolchain.map(_._2).getOrElse("riscv32-unknown-elf-objcopy")

    def readExpected(cFile: Path): Seq[BigInt] = {
        val name = cFile.getFileName.toString.stripSuffix(".c")
        val possiblePaths = Seq(
          expectedDir.resolve(s"$name.expected"),
          expectedDir.resolve("simtests").resolve(s"$name.expected"),
          cFile.resolveSibling(s"$name.expected")
        )

        val finalP = possiblePaths
            .find(p => Files.exists(p))
            .getOrElse(possiblePaths.head)

        if (!Files.exists(finalP)) return Seq()

        val content =
            new String(Files.readAllBytes(finalP), StandardCharsets.UTF_8).trim
        if (content.isEmpty) Seq()
        else content.split("\\s+").map(BigInt(_)).toSeq
    }

    def writeHexFromBinary(binPath: Path, hexPath: Path): Unit = {
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

    def buildHexFor(cFile: Path): Path = {
        Files.createDirectories(genDir)
        val name = cFile.getFileName.toString.stripSuffix(".c")

        val elf = genDir.resolve(s"$name.elf")
        val bin = genDir.resolve(s"$name.bin")
        val hex = genDir.resolve(s"$name.hex")

        val linkLd = linkageDir.resolve("link.ld")
        val crt0 = linkageDir.resolve("crt0.S")
        val mathC = linkageDir.resolve("math.c")

        // Check dependencies (source, linker script, startup code, headers)
        val includeDir = cDir.resolve("include")
        val headerFiles = if (Files.isDirectory(includeDir)) {
            Files.list(includeDir).iterator().asScala.toList
        } else {
            Nil
        }
        val dependencies = Seq(cFile, linkLd, crt0) ++ headerFiles

        // If hex exists and is newer than all dependencies, skip rebuild
        if (Files.exists(hex)) {
            val hexTime = Files.getLastModifiedTime(hex).toMillis
            val upToDate = !dependencies.exists(d => 
                Files.exists(d) && Files.getLastModifiedTime(d).toMillis > hexTime
            )
            if (upToDate) return hex
        }

        val compileCmd = Seq(
          gcc,
          "-march=rv32i",
          "-mabi=ilp32",
          "-O0",
          "-ffreestanding",
          "-mstrict-align",
          "-fno-builtin",
          "-I",
          cDir.toString,
          "-T",
          linkLd.toString,
          "-nostdlib",
          "-static",
          "-Wl,--no-warn-rwx-segments",
          crt0.toString,
          mathC.toString,
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
          Seq(
            objcopy,
            "-O",
            "binary",
            "-R",
            ".note.gnu.build-id",
            "-R",
            ".comment",
            "-R",
            ".riscv.attributes",
            elf.toString,
            bin.toString
          ),
          repoRoot.toFile
        ).!
        require(
          objRc == 0,
          s"objcopy failed for ${cFile.getFileName} (rc=$objRc)"
        )

        writeHexFromBinary(bin, hex)
        hex
    }

    case class SimulationResult(
        result: BigInt,
        output: Seq[BigInt],
        cycles: Int,
        timedOut: Boolean
    )

    def runSimulation(
        dut: BoomCore,
        maxCycles: Int = Configurables.MAX_CYCLE_COUNT,
        debugCallback: (Int) => Unit = _ => ()
    ): SimulationResult = {

        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        
        var cycle = 0
        var result: BigInt = 0
        var outputBuffer = scala.collection.mutable.ArrayBuffer[BigInt]()
        var done = false

        while (!done && cycle < maxCycles) {
            debugCallback(cycle)

            if (dut.io.put.valid.peek().litToBoolean) {
                outputBuffer += dut.io.put.bits.data
                    .peek().litValue
            }

            if (dut.io.exit.valid.peek().litToBoolean) {
                result = dut.io.exit.bits.data.peek().litValue
                done = true
            } else {
                dut.clock.step(1)
                cycle += 1
            }
        }

        SimulationResult(result, outputBuffer.toSeq, cycle, !done)
    }
}
