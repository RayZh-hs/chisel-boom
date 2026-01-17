package utility

import chisel3._
import chisel3.util._
import common.MemOpWidth

/** Wallace Layer
  *
  * Performs 3-to-2 reduction to input integers.
  *
  * @param width
  *   Bit width of the input numbers
  * @param height
  *   Number of input rows
  * @param depth
  *   Maximum number of reduction iterations to perform in this layer
  */
class WallaceLayer(width: Int, height: Int, depth: Int) extends Module {
    // Helper functions
    private def nextHeight(height: Int): Int = {
        if (height <= 2) height
        else {
            val numGroups = height / 3
            val remain = height % 3
            // Each group of 3 becomes 2 rows (sum + carry), remainders stay as 1
            (numGroups * 2) + remain
        }
    }

    private def nextNHeight(height: Int, n: Int = 1): Int = {
        var h = height
        var d = 0
        while (d < n && h > 2) {
            h = nextHeight(h)
            d += 1
        }
        h
    }

    // Calculate output dimensions
    val outHeight = nextNHeight(height, depth)
    val outWidth = width + depth

    val io = IO(new Bundle {
        val in = Input(Vec(height, UInt(width.W)))
        val out = Output(Vec(outHeight, UInt(outWidth.W)))
    })

    // Standard half adder
    def halfAdder(a: UInt, b: UInt): (UInt, UInt) = {
        val sum = a ^ b
        val carry = a & b
        (sum, carry)
    }

    // Full adder: Reduce three bits to two bits
    def fullAdder(a: UInt, b: UInt, c: UInt): (UInt, UInt) = {
        val sum = a ^ b ^ c
        val carry = (a & b) | (b & c) | (a & c)
        (sum, carry)
    }

    // Recursive reduction function with depth limit
    def reduce(inputs: Seq[UInt], currentDepth: Int): Seq[UInt] = {
        if (inputs.length <= 2 || currentDepth == 0) {
            inputs
        } else {
            // Gemini is kind ro provide this extremely elegant,
            // functional method to implement 3-to-2 reduction :)
            val (sums, carries) = inputs
                .grouped(3)
                .map {
                    case Seq(a, b, c) =>
                        val (sum, carry) = fullAdder(a, b, c)
                        (Some(sum), Some(carry << 1))
                    case Seq(a, b) =>
                        val (sum, carry) = halfAdder(a, b)
                        (Some(sum), Some(carry << 1))
                    case Seq(a) =>
                        (Some(a), None)
                }
                .toSeq
                .unzip

            // Flatten results and recurse with decremented depth
            reduce(sums.flatten ++ carries.flatten, currentDepth - 1)
        }
    }

    // Perform reduction
    val reducedSeq = reduce(io.in, depth)

    // Align output widths for the Vec (pad inputs to match the declared outWidth)
    // This is necessary because Chisel Vecs require uniform types.
    io.out := VecInit(reducedSeq.map(_.pad(outWidth)))
}

/** Wallace Tree
  *
  * Top-level module that generates a complete Wallace Tree structure by:
  *   - Concatenating WallaceLayers until rows <= 2;
  *   - Performing final addition with full adder.
  *
  * @param opAWidth
  *   Bit width of multiplicand A
  * @param opBWidth
  *   Bit width of multiplicand B
  * @param reductionDepth
  *   Maximum number of reduction iterations to perform in each WallaceLayer
  *
  * @note
  *   Every time the two inputs go through a Wallace Layer the result is
  *   buffered in a register to improve timing. Use the `reset` signal to reset
  *   them at flush.
  */
class WallaceTree(opAWidth: Int, opBWidth: Int, reductionDepth: Int)
    extends Module {
    val productWidth = opAWidth + opBWidth
    val io = IO(new Bundle {
        val opA = Input(UInt(opAWidth.W))
        val opB = Input(UInt(opBWidth.W))
        val product = Output(UInt(productWidth.W))
    })

    // Generate partial products
    val partialProducts = Wire(Vec(opBWidth, UInt(productWidth.W)))
    for (i <- 0 until opBWidth) {
        // If opB bit i is set, take opA, otherwise 0.
        val rowVal = Mux(io.opB(i), io.opA, 0.U)
        // Shift left by i and zero-extend to final width
        partialProducts(i) := (rowVal << i).asUInt.pad(productWidth)
    }

    // Stage 1: Wallace Layers
    var width = productWidth
    var height = opBWidth
    var activeLayerInput = partialProducts.map(_.pad(width)).toSeq
    var wallaceLayers = Seq.empty[WallaceLayer]
    while (height > 2) {
        val layer = Module(new WallaceLayer(width, height, reductionDepth))
        layer.io.in := VecInit(activeLayerInput)
        wallaceLayers = wallaceLayers :+ layer

        // Update for next iteration
        width = layer.outWidth
        height = layer.outHeight
        activeLayerInput =
            RegNext(layer.io.out, 0.U.asTypeOf(layer.io.out)).toSeq
    }

    // Stage 2: Addition
    val finalSum = Wire(UInt(productWidth.W))
    if (height == 2) {
        finalSum := (activeLayerInput(0) + activeLayerInput(1))
            .asUInt(productWidth - 1, 0)
    } else if (height == 1) {
        // This should only happen when the initial height == 1
        finalSum := activeLayerInput(0).pad(productWidth)
    } else {
        throw new Exception("Wallace Tree reduction failed: no output rows.")
    }
    io.product := finalSum

    // Export Cycle Count
    val cycleCount = wallaceLayers.length
}
