package utility

import chisel3._
import chisel3.util._
import common.MDUFunc._

/** Simple Divider Adaptor
  *
  * Adapts the simple divider to support signed and unsigned division, as well
  * as handle corner cases and exceptions.
  *
  * @param width
  *   Bit-width of the divider
  * @param fanOut
  *   Length of sequential stages per iteration before caching in a register
  */
class SimpleDividerAdaptor(val width: Int, val fanOut: Int) extends Module {
    val io = IO(new Bundle {
        val req = Flipped(Decoupled(new Bundle {
            val opA = UInt(width.W)
            val opB = UInt(width.W)
            val fn = UInt(3.W) // MDUFunc
        }))
        val resp = Valid(new Bundle {
            val divByZero = Bool()
            val overflow = Bool()
            val result = UInt(width.W)
        })
    })

    // Instantiate Simple Divider
    val divider = Module(new SimpleDivider(width, fanOut))

    // Internal States
    object State extends ChiselEnum {
        val sIdle, sBusy, sDone = Value
    }
    import State._
    val state = RegInit(sIdle)

    // Metadata Registers
    val rIsRem = Reg(Bool())
    val rSignRes = Reg(Bool()) // Expected sign of the result
    val rResult = Reg(UInt(width.W))
    val rDivByZero = Reg(Bool())
    val rOverflow = Reg(Bool())

    // Decode Function
    // DIV=4, DIVU=5, REM=6, REMU=7
    val isSigned = (io.req.bits.fn === DIV) || (io.req.bits.fn === REM)
    val isRem = (io.req.bits.fn === REM) || (io.req.bits.fn === REMU)

    // Sign Extraction
    val signA = io.req.bits.opA(width - 1) && isSigned
    val signB = io.req.bits.opB(width - 1) && isSigned

    // Absolute Values
    val absA = Mux(signA, -io.req.bits.opA, io.req.bits.opA)
    val absB = Mux(signB, -io.req.bits.opB, io.req.bits.opB)

    // Corner Cases Check
    val cDivByZero = io.req.bits.opB === 0.U
    val cOverflow =
        isSigned && (io.req.bits.opA === (1.U << (width - 1))) && (io.req.bits.opB.andR) // INT_MIN / -1

    // Divider Interface Defaults
    divider.io.req.valid := false.B
    divider.io.req.bits.dividend := absA
    divider.io.req.bits.divisor := absB
    divider.io.resp.ready := false.B

    // State Updating Logic
    io.req.ready := false.B
    io.resp.valid := false.B
    io.resp.bits.divByZero := rDivByZero
    io.resp.bits.overflow := rOverflow
    io.resp.bits.result := rResult

    switch(state) {
        is(sIdle) {
            // Logic to accept request
            // - Corner Case: Accept immediately, bypass divider
            // - Normal: Forward to divider, wait for ready
            when(io.req.valid) {
                when(cDivByZero || cOverflow) {
                    io.req.ready := true.B
                    rDivByZero := cDivByZero
                    rOverflow := cOverflow

                    // RISC-V Exception Results
                    // DivBy0: DIV=-1, REM=opA
                    // Overflow: DIV=INT_MIN, REM=0
                    val resDiv =
                        Mux(cDivByZero, Fill(width, 1.U), (1.U << (width - 1)))
                    val resRem = Mux(cDivByZero, io.req.bits.opA, 0.U)
                    rResult := Mux(isRem, resRem, resDiv)

                    state := sDone
                }.otherwise {
                    // Normal Operation
                    divider.io.req.valid := true.B
                    when(divider.io.req.ready) {
                        io.req.ready := true.B

                        rIsRem := isRem
                        rDivByZero := false.B
                        rOverflow := false.B

                        // Determine Result Sign:
                        // Quotient: A^B, Remainder: A
                        rSignRes := Mux(isRem, signA, signA ^ signB)

                        state := sBusy
                    }
                }
            }
        }

        is(sBusy) {
            divider.io.resp.ready := true.B
            when(divider.io.resp.valid) {
                val rawQuo = divider.io.resp.bits.quotient
                val rawRem = divider.io.resp.bits.remainder
                val rawRes = Mux(rIsRem, rawRem, rawQuo)

                // Apply Sign Correction
                rResult := Mux(rSignRes, -rawRes, rawRes)
                state := sDone
            }
        }

        is(sDone) {
            io.resp.valid := true.B
            state := sIdle
        }
    }
}

/** Simple Divider
  *
  * @param width
  *   Bit-width of the divider
  * @param fanOut
  *   Length of sequential stages per iteration before caching in a register
  */
class SimpleDivider(val width: Int, val fanOut: Int) extends Module {
    require(
      width % fanOut == 0,
      s"Width ($width) must be divisible by fanOut ($fanOut)"
    )

    val io = IO(new Bundle {
        val req = Flipped(Decoupled(new Bundle {
            val dividend = UInt(width.W)
            val divisor = UInt(width.W)
        }))
        val resp = Decoupled(new Bundle {
            val quotient = UInt(width.W)
            val remainder = UInt(width.W)
        })
    })

    object State extends ChiselEnum {
        val sIdle, sCalc, sDone = Value
    }
    import State._

    val state = RegInit(sIdle)
    val cnt = Reg(UInt(log2Ceil(width / fanOut + 1).W))
    val rRem = Reg(UInt(width.W))
    val rQuo = Reg(UInt(width.W))
    val rDivisor = Reg(UInt(width.W))

    val nextDividendBits = rQuo(width - 1, width - fanOut)
    val remShifted = Cat(rRem(width - 1 - fanOut, 0), nextDividendBits)

    // Generate multiples of the divisor
    val numMultiples = 1 << fanOut
    val multiples = Wire(Vec(numMultiples, UInt((width + fanOut).W)))
    multiples(0) := 0.U
    for (i <- 1 until numMultiples) {
        multiples(i) := multiples(i - 1) + rDivisor
    }

    // Search for the largest 'i' st. remShifted >= multiples(i)
    /*
     * I should have placed a binary search here, but since we are going for fanOut=1, it doesn't matter so much.
     */
    val quoChunk = Wire(UInt(fanOut.W))
    quoChunk := 0.U
    for (i <- 1 until numMultiples) {
        when(remShifted >= multiples(i)) {
            quoChunk := i.U
        }
    }

    // Update Next Remainder and Next Quotient State
    val subVal = multiples(quoChunk)
    val nextRem = remShifted - subVal
    val nextQuo = Cat(rQuo(width - fanOut - 1, 0), quoChunk)

    // State Updating Logic
    io.req.ready := (state === sIdle)
    io.resp.valid := (state === sDone)
    io.resp.bits.quotient := rQuo
    io.resp.bits.remainder := rRem

    switch(state) {
        is(sIdle) {
            when(io.req.fire) {
                rRem := 0.U
                rQuo := io.req.bits.dividend
                rDivisor := io.req.bits.divisor
                cnt := (width / fanOut).U
                state := sCalc
            }
        }
        is(sCalc) {
            // Apply the Radix calculation
            rRem := nextRem
            rQuo := nextQuo

            cnt := cnt - 1.U
            when(cnt === 1.U) {
                state := sDone
            }
        }
        is(sDone) {
            when(io.resp.fire) {
                state := sIdle
            }
        }
    }
}
