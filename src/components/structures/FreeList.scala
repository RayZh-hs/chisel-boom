package components.structures

import chisel3._
import chisel3.util._

class FreeList(numRegs: Int, numArchRegs: Int) extends Module {
  val numFreeRegisters = numRegs - numArchRegs
  val capacity = 1 << log2Ceil(numFreeRegisters)
  val width = log2Ceil(numRegs)
  val ptrWidth = log2Ceil(capacity)

  val io = IO(new Bundle {
    val allocate = Decoupled(UInt(width.W))
    val free = Flipped(Decoupled(UInt(width.W)))
    val rollbackFree = Flipped(Decoupled(UInt(width.W)))
  })

  val ram = Mem(capacity, UInt(width.W))

  val head = RegInit(0.U((ptrWidth + 1).W))
  val tail = RegInit(0.U((ptrWidth + 1).W))

  // --- Initialization ---
  val isInit  = RegInit(true.B)
  val initPtr = RegInit(0.U(ptrWidth.W))

  when(isInit) {
    ram.write(initPtr, initPtr + numArchRegs.U)
    initPtr := initPtr + 1.U
    when(initPtr === (numFreeRegisters - 1).U) {
      isInit := false.B
      tail   := numFreeRegisters.U
    }
  }

  // --- Logic ---
  val empty = head === tail
  io.allocate.valid := !empty && !isInit
  io.allocate.bits  := ram.read(head(ptrWidth - 1, 0))

  io.free.ready         := !isInit
  io.rollbackFree.ready := !isInit

  val doAlloc    = io.allocate.fire
  val doFree     = io.free.fire
  val doRollback = io.rollbackFree.fire
  val numEnq     = doFree.asUInt +& doRollback.asUInt

  // Sequential Write Logic (Dual Port Write)
  when(doFree) {
    ram.write(tail(ptrWidth - 1, 0), io.free.bits)
  }
  
  val rollbackIdx = tail + doFree.asUInt 
  when(doRollback) {
    ram.write(rollbackIdx(ptrWidth - 1, 0), io.rollbackFree.bits)
  }

  when(!isInit) {
    // This assertion will trigger in simulation if the Renamer/ROB logic 
    // attempts to return more registers than exist in the system.
    assert( (tail - head +& numEnq - doAlloc.asUInt) <= numFreeRegisters.U, 
           "FreeList Overflow: Architectural limit of %d regs exceeded!", numFreeRegisters.U)
    
    head := head + doAlloc.asUInt
    tail := tail + numEnq
  }
}