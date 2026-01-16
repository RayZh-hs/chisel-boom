package e2e

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
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
    val sharedHexPath: Path = genDir.resolve("shared_program.hex")
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
                Files.exists(d) && Files
                    .getLastModifiedTime(d)
                    .toMillis > hexTime
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

    def setupSimulation() {
        val requireReport = System.getProperty("report") == "true"
        if (!requireReport) {
            common.Configurables.Profiling.prune()
        }
    }

    def runTestWithHex(
        hexPath: Path,
        maxCycles: Int = Configurables.MAX_CYCLE_COUNT
    ): SimulationResult = {
        setupSimulation()
        // Shared path for the hex file to avoid recompilation of BoomCore
        // We place it outside the specific test run directory so it persists/is accessible
        Files.copy(hexPath, sharedHexPath, StandardCopyOption.REPLACE_EXISTING)

        var res: SimulationResult = null
        simulate(new BoomCore(sharedHexPath.toAbsolutePath.toString)) { dut =>
            res = runSimulation(dut, maxCycles)
        }
        res
    }

    def runSimulation(
        dut: BoomCore,
        maxCycles: Int = Configurables.MAX_CYCLE_COUNT,
        debugCallback: (Int) => Unit = _ => (),
        report: Boolean = true
    ): SimulationResult = {

        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        var cycle = 0
        var result: BigInt = 0
        var outputBuffer = scala.collection.mutable.ArrayBuffer[BigInt]()
        var done = false

        val batchSize = 4096

        while (!done && cycle < maxCycles) {
            debugCallback(cycle)

            // Run in batches
            try {
                // Step for a batch (or remainder)
                val steps = Math.min(batchSize, maxCycles - cycle)
                dut.clock.step(steps)
                cycle += steps
            } catch {
                case e: Exception
                    if e.getMessage != null && (e.getMessage.contains(
                      "stop"
                    ) || e.getMessage.contains("Stop") || e.getMessage.contains(
                      "simulation has already finished"
                    )) =>
                    done = true
                case e: Throwable =>
                    val str = e.toString
                    val msg = if (e.getMessage != null) e.getMessage else ""
                    if (
                      str.contains("StopException") || str.contains(
                        "stop"
                      ) || msg.contains("simulation has already finished")
                    ) {
                        done = true
                    } else {
                        throw e
                    }
            }

            // Check for exit condition (latched)
            // Even if simulation stopped, peekable state should remain
            if (dut.io.exit.valid.peek().litToBoolean) {
                result = dut.io.exit.bits.peek().litValue
                done = true
            }

            // Drain debug output queue
            // We use a small loop to drain up to 'batchSize' elements or until empty
            var drained = 0
            while (drained < 64 && dut.io.put.valid.peek().litToBoolean) {
                val outVal = dut.io.put.bits.peek().litValue
                // Convert unsigned 32-bit to signed 32-bit BigInt
                val outSigned =
                    if (outVal >= BigInt("80000000", 16))
                        outVal - BigInt("100000000", 16)
                    else outVal
                outputBuffer += outSigned

                // Handshake
                dut.io.put.ready.poke(true.B)
                dut.clock.step(1)
                dut.io.put.ready.poke(false.B)
                drained += 1
                cycle += 1
            }
        }

        if (done && report && common.Configurables.Profiling.isAnyEnabled) {
            Thread.sleep(100) // Wait for all prints to finish
            println("=========================================================")
            println("                    PROFILING REPORT                     ")
            println("=========================================================")

            val p = dut.io.profiler 
            if (common.Configurables.Profiling.branchMispredictionRate) {
                val total = p.totalBranches.get.peek().litValue
                val mispred = p.totalMispredicts.get.peek().litValue
                val rate =
                    if (total > 0)
                        (mispred.toDouble / total.toDouble) * 100.0
                    else 0.0

                println(f"Branch Misprediction Rate:")
                println(f"  Total Branches:       $total")
                println(f"  Total Mispredictions: $mispred")
                println(f"  Misprediction Rate:   $rate%.2f%%")
            }

            if (common.Configurables.Profiling.IPC) {
                val insts = p.totalInstructions.get.peek().litValue
                val cycles = p.totalCycles.get.peek().litValue
                // Post cold start cycles (free list initialization)
                val pcsCycles = cycles - 32 + 4 // 32 init cycles, among which 4 frontend working cycles
                val ipc = if (pcsCycles > 0) insts.toDouble / pcsCycles.toDouble else 0.0
                
                println(f"IPC Performance:")
                println(f"  Total Instructions:   $insts")
                println(f"  Total PCS Cycles:     $pcsCycles")
                println(f"  IPC:                  $ipc%.4f")
            }

            if (common.Configurables.Profiling.RollbackTime) {
                val events = p.totalRollbackEvents.get.peek().litValue
                val cycles = p.totalRollbackCycles.get.peek().litValue
                val avg = if (events > 0) cycles.toDouble / events.toDouble else 0.0
                
                println(f"Rollback Performance:")
                println(f"  Total Rollback Events: $events")
                println(f"  Total Rollback Cycles: $cycles")
                println(f"  Average Rollback Time: $avg%.2f cycles")
            }

            if (common.Configurables.Profiling.Utilization) {
                println(f"Stage Utilization:")
                val fetcher = p.busyFetcher.get.peek().litValue
                val decoder = p.busyDecoder.get.peek().litValue
                val dispatcher = p.busyDispatcher.get.peek().litValue
                val alu = p.busyALU.get.peek().litValue
                val bru = p.busyBRU.get.peek().litValue
                val lsu = p.busyLSU.get.peek().litValue
                val rob = p.busyROB.get.peek().litValue

                def formatUtil(name: String, busy: BigInt): Unit = {
                    val rate = if (cycle > 0) (busy.toDouble / cycle.toDouble) * 100.0 else 0.0
                    println(f"  $name%-12s: $busy%8d / $cycle%8d ($rate%.2f%%)")
                }

                formatUtil("Fetcher", fetcher)
                formatUtil("Decoder", decoder)
                formatUtil("Dispatcher", dispatcher)
                formatUtil("ALU", alu)
                formatUtil("BRU", bru)
                formatUtil("LSU", lsu)
                formatUtil("ROB-Commit", rob)
            }

            println("=========================================================")
        }

        SimulationResult(
          result,
          outputBuffer.toSeq,
          cycle,
          !done && cycle >= maxCycles
        )
    }
}
