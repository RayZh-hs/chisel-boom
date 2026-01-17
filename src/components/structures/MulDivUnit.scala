package components.structures

import chisel3._
import chisel3.util._
import common._
import utility.WallaceTree
import common.Configurables.MMIOAddress._

object MduFunc {
    // Multiplication
    val MUL = 0.U(3.W)
    val MULH = 1.U(3.W)
    val MULHSU = 2.U(3.W)
    val MULHU = 3.U(3.W)
    // Division / Remainder
    val DIV = 4.U(3.W)
    val DIVU = 5.U(3.W)
    val REM = 6.U(3.W)
    val REMU = 7.U(3.W)
}

/** Multiply Divide Unit
  *
  * Performs 32-bit integer multiplication and division.
  *
  * @note
  *   - Utilizes WallaceTree for Multiplication (Multicycle latency)
  *   - Iterative Division (34 Cycles)
  */
class MulDivUnit extends Module {
    val io = IO(new Bundle {
        val req = Flipped(Decoupled(new Bundle {
            val fn = UInt(3.W)
            val a = UInt(32.W)
            val b = UInt(32.W)
        }))
        val resp = Decoupled(UInt(32.W))
    })

    // State Enum
    object State extends ChiselEnum {
        val sIdle, sMul, sDiv, sDone = Value
    }
    import State._

    val state = RegInit(sIdle)

    // Internal Registers
    val rA = Reg(UInt(32.W))
    val rB = Reg(UInt(32.W))
    val rFn = Reg(UInt(3.W))
    val rRes = Reg(UInt(32.W)) // Final result buffer

    // Division Specific Registers
    val rRem = Reg(UInt(33.W))
    val rQuo = Reg(UInt(32.W))
    val rDivSign = Reg(Bool())

    // Shared Counter used for both DIV iterations and MUL pipeline latency
    val cnt = Reg(UInt(6.W))

    // Wallace Tree
    val wallace = Module(new WallaceTree(32, 32, Configurables.WALLACE_RDEPTH))

    // Determine correctness of signed operations for Multiplication
    val mul_a_is_signed =
        (rFn === MduFunc.MULH) || (rFn === MduFunc.MULHSU) || (rFn === MduFunc.MUL)
    val mul_b_is_signed = (rFn === MduFunc.MULH) || (rFn === MduFunc.MUL)

    // Use absolute values for Wallace Tree to handle signed logic uniformly
    val abs_a = Mux(mul_a_is_signed && rA(31), -rA, rA)
    val abs_b = Mux(mul_b_is_signed && rB(31), -rB, rB)

    wallace.io.opA := abs_a
    wallace.io.opB := abs_b

    // Calculate Sign of the product
    val mul_res_sign = (mul_a_is_signed && rA(31)) ^ (mul_b_is_signed && rB(31))

    // Sign correction for Wallace Output
    val wallace_raw = wallace.io.product
    val wallace_corrected = Mux(mul_res_sign, -wallace_raw, wallace_raw)

    // Default IO
    io.req.ready := false.B
    io.resp.valid := false.B
    io.resp.bits := rRes

    // Division Input Helper Logic (Combinational)
    val div_is_signed =
        (io.req.bits.fn === MduFunc.DIV) || (io.req.bits.fn === MduFunc.REM)
    val div_a_sign = io.req.bits.a(31) && div_is_signed
    val div_b_sign = io.req.bits.b(31) && div_is_signed
    val div_abs_a = Mux(div_a_sign, -io.req.bits.a, io.req.bits.a)
    val div_abs_b = Mux(div_b_sign, -io.req.bits.b, io.req.bits.b)

    // Helper Functions
    def startOp(a: UInt, b: UInt, fn: UInt): Unit = {
        rA := a
        rB := b
        rFn := fn

        val isDiv = (fn === MduFunc.DIV) || (fn === MduFunc.DIVU)
        val isRem = (fn === MduFunc.REM) || (fn === MduFunc.REMU)

        when(isDiv || isRem) {
            // TODO remove hardcoded MMIO values
            // Setup Division
            val divBy0 = (b === 0.U)
            val signedOverflow =
                (div_is_signed) && (a === PUT_ADDR.U) && (b === EXIT_ADDR.U)

            when(divBy0) {
                rRes := Mux(isDiv, EXIT_ADDR.U, a)
                state := sDone
            }.elsewhen(signedOverflow) {
                rRes := Mux(isDiv, PUT_ADDR.U, 0.U)
                state := sDone
            }.otherwise {
                // Initialize iterative division
                cnt := 32.U
                rQuo := div_abs_a
                rRem := 0.U
                rDivSign := Mux(isDiv, div_a_sign ^ div_b_sign, div_a_sign)
                rB := div_abs_b // Use rB to store divisor magnitude
                state := sDiv
            }
        }.otherwise {
            // Setup Multiplication
            // Wait N cycles for Wallace Tree
            cnt := wallace.cycleCount.U
            state := sMul
        }
    }

    def handleMul(): Unit = {
        // Wait for Wallace Tree pipeline
        when(cnt === 0.U) {
            // Select Lower or Upper 32 bits
            rRes := Mux(
              rFn === MduFunc.MUL,
              wallace_corrected(31, 0),
              wallace_corrected(63, 32)
            )
            state := sDone
        }.otherwise {
            cnt := cnt - 1.U
        }
    }

    def handleDiv(): Unit = {
        // Iterative Division Logic
        val shiftRem = Cat(rRem(31, 0), rQuo(31))
        val diff = shiftRem - rB // rB holds abs(b)
        val less = diff(32)

        rRem := Mux(less, shiftRem, diff)
        rQuo := Cat(rQuo(30, 0), !less)

        cnt := cnt - 1.U

        when(cnt === 1.U) {
            val finalQuo = rQuo
            val finalRem = Mux(less, shiftRem, diff)(31, 0)

            val isDiv = (rFn === MduFunc.DIV) || (rFn === MduFunc.DIVU)
            val rawResult = Mux(isDiv, Cat(finalQuo(30, 0), !less), finalRem)
            rRes := Mux(rDivSign, -rawResult, rawResult)
            state := sDone
        }
    }

    switch(state) {
        is(sIdle) {
            io.req.ready := true.B
            when(io.req.fire) {
                startOp(io.req.bits.a, io.req.bits.b, io.req.bits.fn)
            }
        }
        is(sMul) { handleMul() }
        is(sDiv) { handleDiv() }
        is(sDone) {
            io.resp.valid := true.B
            when(io.resp.fire) { state := sIdle }
        }
    }
}
