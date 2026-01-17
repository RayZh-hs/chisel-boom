package components.structures

import chisel3._
import chisel3.util._
import common._

object MduFunc {
  // Multiplication
  val MUL    = 0.U(3.W)
  val MULH   = 1.U(3.W)
  val MULHSU = 2.U(3.W)
  val MULHU  = 3.U(3.W)
  // Division / Remainder
  val DIV    = 4.U(3.W)
  val DIVU   = 5.U(3.W)
  val REM    = 6.U(3.W)
  val REMU   = 7.U(3.W)
}

/**
 * RiscV MDU (Multiply Divide Unit)
 * 
 * Latency:
 * - MUL: 2 Cycles (Pipelined for better timing)
 * - DIV: 34 Cycles (Iterative Shift-Subtract)
 */
class MulDivUnit extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
        val fn = UInt(3.W)
        val a  = UInt(32.W)
        val b  = UInt(32.W)
    }))
    val resp = Decoupled(UInt(32.W))
  })

  // Configuration
  val mulLatency = Configurables.MUL_LATENCY // Number of pipeline stages for Multiply

  // State Definitions
  val sIdle  = 0.U(3.W)
  val sCalc  = 1.U(3.W) // Used for MUL wait
  val sDiv   = 2.U(3.W) // Used for DIV iteration
  val sDone  = 3.U(3.W)
  
  val state = RegInit(sIdle)

  // Internal Registers
  val rA     = Reg(UInt(32.W))
  val rB     = Reg(UInt(32.W))
  val rFn    = Reg(UInt(3.W))
  val rRes   = Reg(UInt(32.W)) // Final result buffer
  
  // Counter used for both DIV iterations and MUL pipeline latency
  val cnt    = Reg(UInt(6.W)) 

  // Division specific regs
  val rRem   = Reg(UInt(33.W))
  val rQuo   = Reg(UInt(32.W))
  val rSign  = Reg(Bool())

  // Default IO
  io.req.ready  := false.B
  io.resp.valid := false.B
  io.resp.bits  := rRes

  // ==============================================================================
  // 1. Division Helper Logic (Combinational)
  // ==============================================================================
  val is_signed_div = (io.req.bits.fn === MduFunc.DIV) || (io.req.bits.fn === MduFunc.REM)
  val req_a_sign    = io.req.bits.a(31) && is_signed_div
  val req_b_sign    = io.req.bits.b(31) && is_signed_div
  val abs_a         = Mux(req_a_sign, -io.req.bits.a, io.req.bits.a)
  val abs_b         = Mux(req_b_sign, -io.req.bits.b, io.req.bits.b)

  // ==============================================================================
  // 2. Multiplication Pipeline Logic
  // ==============================================================================
  // Inputs are taken from Registers (stable during sCalc)
  val mul_a_is_signed = (rFn === MduFunc.MULH) || (rFn === MduFunc.MULHSU) || (rFn === MduFunc.MUL)
  val mul_b_is_signed = (rFn === MduFunc.MULH) || (rFn === MduFunc.MUL)
  
  val mul_a_sint = Cat(mul_a_is_signed && rA(31), rA).asSInt
  val mul_b_sint = Cat(mul_b_is_signed && rB(31), rB).asSInt
  
  // --- Pipeline Insertion ---
  // We simply register the result of the multiplication multiple times.
  // Synthesis tools (Retiming) will move the logic cloud *between* these registers
  // to balance the delay (e.g., inside DSP blocks).
  
  // Stage 1
  val mul_pipe_1 = RegNext(mul_a_sint * mul_b_sint)
  // Stage 2 (Add more RegNext here if you increase mulLatency)
  val mul_pipe_2 = RegNext(mul_pipe_1) 
  
  // Select Upper/Lower 32 bits from the end of the pipeline
  val mul_res = Mux(rFn === MduFunc.MUL, mul_pipe_2(31, 0), mul_pipe_2(63, 32))

  // ==============================================================================
  // 3. Finite State Machine
  // ==============================================================================
  switch(state) {
    is(sIdle) {
      io.req.ready := true.B
      
      when(io.req.fire) {
        val fn = io.req.bits.fn
        val a  = io.req.bits.a
        val b  = io.req.bits.b

        rA  := a
        rB  := b
        rFn := fn

        // Decode
        val isDiv = (fn === MduFunc.DIV) || (fn === MduFunc.DIVU)
        val isRem = (fn === MduFunc.REM) || (fn === MduFunc.REMU)
        
        when(isDiv || isRem) {
          // --- Division Setup ---
          val divBy0 = (b === 0.U)
          val signedOverflow = (fn === MduFunc.DIV || fn === MduFunc.REM) && 
                               (a === "h80000000".U) && (b === "hFFFFFFFF".U)

          when (divBy0) {
            rRes := Mux(isDiv, "hFFFFFFFF".U, a)
            state := sDone
          } .elsewhen (signedOverflow) {
            rRes := Mux(isDiv, "h80000000".U, 0.U)
            state := sDone
          } .otherwise {
            cnt  := 32.U
            rQuo := abs_a 
            rRem := 0.U   
            rSign := Mux(isDiv, req_a_sign ^ req_b_sign, req_a_sign)
            rB    := abs_b 
            state := sDiv
          }
        } .otherwise {
          // --- Multiplication Setup ---
          // Initialize counter to wait for pipeline latency
          // We subtract 1 because the cycle we enter sCalc counts as the first propagation
          cnt   := (mulLatency - 1).U 
          state := sCalc
        }
      }
    }

    is(sCalc) {
      // Used for Multiplication Wait
      // The registers mul_pipe_X are constantly shifting every clock cycle.
      // We wait until the correct data reaches the end of the pipe.
      
      when(cnt === 0.U) {
        rRes  := mul_res
        state := sDone
      } .otherwise {
        cnt := cnt - 1.U
      }
    }

    is(sDiv) {
      // Iterative Division Logic
      val shiftRem = Cat(rRem(31, 0), rQuo(31))
      val diff = shiftRem - rB 
      val less = diff(32) 

      rRem := Mux(less, shiftRem, diff)
      rQuo := Cat(rQuo(30, 0), !less) 
      
      cnt := cnt - 1.U

      when(cnt === 1.U) {
        val finalQuo = rQuo
        val finalRem = Mux(less, shiftRem, diff)(31, 0)
        val rawResult = Mux(rFn === MduFunc.DIV || rFn === MduFunc.DIVU, 
                            Cat(finalQuo(30, 0), !less), 
                            finalRem)
        rRes  := Mux(rSign, -rawResult, rawResult)
        state := sDone
      }
    }

    is(sDone) {
      io.resp.valid := true.B
      when(io.resp.fire) {
        state := sIdle
      }
    }
  }
}