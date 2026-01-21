package components.structures

import chisel3._
import chisel3.util._
import common._
import utility.{WallaceTree, SimpleDividerAdaptor}
import common.Configurables.MMIOAddress._

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

    // Internal Registers for Multiplication
    val rA = Reg(UInt(32.W))
    val rB = Reg(UInt(32.W))
    val rFn = Reg(UInt(3.W))
    val rRes = Reg(UInt(32.W)) // Final result buffer
    val cnt = Reg(UInt(6.W)) // Latency counter for Mul

    // # Multiplication
    val wallace = Module(new WallaceTree(32, 32, Configurables.WALLACE_RDEPTH))
    // Determine correctness of signed operations for Multiplication
    val mul_a_is_signed =
        (rFn === MDUFunc.MULH) || (rFn === MDUFunc.MULHSU) || (rFn === MDUFunc.MUL)
    val mul_b_is_signed = (rFn === MDUFunc.MULH) || (rFn === MDUFunc.MUL)

    val abs_a = Mux(mul_a_is_signed && rA(31), -rA, rA)
    val abs_b = Mux(mul_b_is_signed && rB(31), -rB, rB)

    wallace.io.opA := abs_a
    wallace.io.opB := abs_b

    // Calculate Sign of the product
    val mul_res_sign = (mul_a_is_signed && rA(31)) ^ (mul_b_is_signed && rB(31))
    val wallace_raw = wallace.io.product
    val wallace_corrected = Mux(mul_res_sign, -wallace_raw, wallace_raw)

    // # Division
    val dividerAdaptor = Module(
      new SimpleDividerAdaptor(32, 1)
    )

    // Default wiring for Divider
    dividerAdaptor.io.req.valid := false.B
    dividerAdaptor.io.req.bits.opA := io.req.bits.a
    dividerAdaptor.io.req.bits.opB := io.req.bits.b
    dividerAdaptor.io.req.bits.fn := io.req.bits.fn

    // Default IO
    val is_div_op = io.req.bits.fn(2)
    io.req.ready := false.B
    io.resp.valid := false.B
    io.resp.bits := rRes

    // # State Machine
    switch(state) {
        is(sIdle) {
            // Arbitration logic:
            // If Div: Check Adaptor Ready
            // If Mul: Always Ready (internal registers, pipelined service)

            when(is_div_op) {
                // Pass control to Adaptor
                dividerAdaptor.io.req.valid := io.req.valid
                io.req.ready := dividerAdaptor.io.req.ready

                when(io.req.fire) {
                    state := sDiv
                }
            }.otherwise {
                // Handle Multiplication locally
                io.req.ready := true.B

                when(io.req.fire) {
                    rA := io.req.bits.a
                    rB := io.req.bits.b
                    rFn := io.req.bits.fn

                    // Setup Multiplication Wait
                    cnt := wallace.cycleCount.U
                    state := sMul
                }
            }
        }

        is(sMul) {
            when(cnt === 0.U) {
                rRes := Mux(
                  rFn === MDUFunc.MUL,
                  wallace_corrected(31, 0),
                  wallace_corrected(63, 32)
                )
                state := sDone
            }.otherwise {
                cnt := cnt - 1.U
            }
        }

        is(sDiv) {
            // Wait for Adaptor Response
            when(dividerAdaptor.io.resp.valid) {
                rRes := dividerAdaptor.io.resp.bits.result
                // Note: divByZero and overflow flags are available here if needed for CSRs
                state := sDone
            }
        }

        is(sDone) {
            io.resp.valid := true.B
            when(io.resp.fire) { state := sIdle }
        }
    }
}
