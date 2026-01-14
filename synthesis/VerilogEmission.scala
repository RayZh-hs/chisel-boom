import circt.stage.ChiselStage
import common.Configurables._
import core.BoomCore
import java.io.File

object VerilogEmission {
    def main(args: Array[String]): Unit = {
        // Expecting one argument: path to hex file for memory initialization
        if (args.length > 1) {
            println("Usage: VerilogEmission (hex-file)")
            sys.exit(1)
        }

        // If none is given, create an empty hex file in /tmp
        if (args.length == 0) {
            val tempHexFile = "/tmp/empty.hex"
            new File(tempHexFile).createNewFile()
            println(
              s"No hex file provided. Using empty hex file at '$tempHexFile'."
            )
        }

        val buildDir = "synthesis/generated"
        val hexFile = if (args.length == 1) args(0) else "/tmp/empty.hex"

        // Create output directory
        new File(buildDir).mkdirs()

        println("Generating SystemVerilog for BoomCore...")
        if (!new File(hexFile).exists()) {
            println(s"Error: Hex file '$hexFile' does not exist.")
            sys.exit(1)
        }

        // Generate the SystemVerilog files
        // using --split-verilog to generate separate files per module is good practice
        val genArgs = Array(
          "--target-dir",
          buildDir,
          "--split-verilog"
        )

        // Prune elaboration options for synthesis
        Elaboration.prune()
        ChiselStage.emitSystemVerilogFile(
          new BoomCore(hexFile),
          args = genArgs,
          firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
        )

        println(s"Design elaborated to $buildDir")
    }
}
