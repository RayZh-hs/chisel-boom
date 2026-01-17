package e2e

import java.nio.file.{Path, Paths, Files}
import scala.jdk.CollectionConverters._
import common.Configurables._
import e2e.Configurables._
import E2EUtils._

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

    val normalizedPath = if (try { isByteAligned(hexFile) } catch { case _: Throwable => false }) {
        println(s"Hex file $hexFile is byte-aligned. Converting to word-aligned format.")
        val p = convertByteToWordAligned(hexFile)
        println(s"Converted hex file saved to: $p")
        p
    } else {
        hexFile
    }

    println(s"Running simulation using hex file: $normalizedPath")
    println("Simulation started.")

    val simRes = E2EUtils.runTestWithHex(normalizedPath)

    Thread.sleep(500) // Wait for final prints to flush
    if (!simRes.timedOut) {
        println(s"Simulation finished in ${simRes.cycles} cycles.")
        println(s"Return Code: ${simRes.result}")
        println(s"Output: ${simRes.output.mkString(" ")}")
    } else {
        println(s"Simulation timed out after ${simRes.cycles} cycles.")
        println(s"Output so far: ${simRes.output.mkString(" ")}")
    }

    def isByteAligned(path: Path): Boolean = {
        val lines = Files.readAllLines(path)
        // Grab first non-empty line starting with hex digits
        val hexDigits = "[0-9a-fA-F]+".r
        val firstHexLine = lines.asScala.collectFirst { case line: String if hexDigits.findFirstIn(line).isDefined => line }
        firstHexLine match {
            case Some(line: String) =>
                val firstToken = line.trim.split("\\s+")(0)
                firstToken.length == 2
            case None => throw new Exception("No hex data found in file.")
        }
    }

    def convertByteToWordAligned(inputPath: Path): Path = {
        val lines = Files.readAllLines(inputPath).asScala
        val outputPath = inputPath.getParent.resolve(inputPath.getFileName.toString.replace(".hex", "_word_aligned.hex"))
        val writer = Files.newBufferedWriter(outputPath)
        for (line <- lines) {
            if (line.trim.nonEmpty && !line.startsWith("#") && !line.startsWith("@")) {
                val tokens = line.trim.split("\\s+")
                if (tokens.exists(token => token.length != 2 && !token.matches("[0-9a-fA-F]+"))) {
                    throw new Exception(s"Invalid hex data format in line: $line")
                }
                // squeeze 4 bytes into one word
                val wordTokens = tokens.grouped(4).map { byteGroup =>
                    val paddedGroup = byteGroup.padTo(4, "00") // pad with zeros if less than 4 bytes
                    paddedGroup.reverse.mkString // reverse for little-endian
                }.toSeq
                // Write wordTokens to new file
                writer.write(wordTokens.mkString(" ") + "\n")
            } else {
                writer.write(line + "\n")
            }
        }
        writer.close()
        outputPath
    }
}
