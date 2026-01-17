package components.structures

import chisel3._
import chisel3.util._

object MulFunc {
  val MUL    = 0.U(3.W)
  val MULH   = 1.U(3.W)
  val MULHSU = 2.U(3.W)
  val MULHU  = 3.U(3.W)
}

/**
 * RiscVMultiplier: Non-pipelined, State Machine Based.
 * Uses Decoupled IO.
 * Cycle 0: Accept Req (Idle)
 * Cycle 1+: Calc
 * Cycle N: Return Resp (Done)
 */
class RiscVMultiplier extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
        val fn = UInt(3.W)
        val a  = UInt(32.W)
        val b  = UInt(32.W)
    }))
    val resp = Decoupled(UInt(32.W))
  })

  // State Definitions
  val sIdle  = 0.U(2.W)
  val sCalc  = 1.U(2.W)
  val sDone  = 2.U(2.W)
  
  val state = RegInit(sIdle)

  // Internal Registers
  val rA     = Reg(UInt(32.W))
  val rB     = Reg(UInt(32.W))
  val rFn    = Reg(UInt(3.W))
  val rRes   = Reg(UInt(32.W))

  // Default IO
  io.req.ready  := false.B
  io.resp.valid := false.B
  io.resp.bits  := rRes

  // -----------------------------------------------------------
  // Math Logic (Combinational, results captured in sCalc)
  // -----------------------------------------------------------
  val func = rFn
  val a_is_signed = (func === MulFunc.MULH) || (func === MulFunc.MULHSU) || (func === MulFunc.MUL)
  val b_is_signed = (func === MulFunc.MULH) || (func === MulFunc.MUL)
  
  val a_sign = a_is_signed && rA(31)
  val b_sign = b_is_signed && rB(31)
  
  val a_sint = Cat(a_sign, rA).asSInt // 33-bit
  val b_sint = Cat(b_sign, rB).asSInt // 33-bit
  
  val full_product = (a_sint * b_sint) // 66-bit
  val lower_32 = full_product(31, 0)
  val upper_32 = full_product(63, 32)
  val result_mux = Mux(func === MulFunc.MUL, lower_32, upper_32)

  // -----------------------------------------------------------
  // FSM Logic
  // -----------------------------------------------------------
  switch(state) {
    is(sIdle) {
      io.req.ready := true.B
      when(io.req.fire) {
        rA  := io.req.bits.a
        rB  := io.req.bits.b
        rFn := io.req.bits.fn
        state := sCalc
      }
    }

    is(sCalc) {
      // Simulate calculation cycle (and capture result)
      // This breaks the critical path from inputs to outputs
      rRes  := result_mux
      state := sDone
    }

    is(sDone) {
      io.resp.valid := true.B
      when(io.resp.fire) {
        state := sIdle
      }
    }
  }
}