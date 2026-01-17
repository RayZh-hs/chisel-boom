package components.structures

import chisel3._
import chisel3.util._
import utility.CycleAwareModule

/** Free List
  *
  * Holds the list of free physical registers.
  *
  * @param numRegs
  *   Total number of physical registers
  * @param numArchRegs
  *   Number of architectural registers
  */
class FreeList(numRegs: Int, numArchRegs: Int) extends CycleAwareModule {
    // Derived Parameters
    val numFreeRegisters = numRegs - numArchRegs
    val capacity = 1 << log2Ceil(numFreeRegisters)
    val width = log2Ceil(numRegs)
    val ptrWidth = log2Ceil(capacity)

    // IO Definition
    val io = IO(new Bundle {
        val allocate = Decoupled(UInt(width.W))
        val free = Flipped(Decoupled(UInt(width.W)))
        val rollbackFree = Vec(2, Flipped(Decoupled(UInt(width.W))))
    })

    val ram = Mem(capacity, UInt(width.W))

    val head = RegInit(0.U((ptrWidth + 1).W))
    val tail = RegInit(0.U((ptrWidth + 1).W))

    // Initialization Logic
    val isInit = RegInit(true.B)
    val initPtr = RegInit(0.U(ptrWidth.W))

    when(isInit) {
        ram.write(initPtr, initPtr + numArchRegs.U)
        initPtr := initPtr + 1.U
        when(initPtr === (numFreeRegisters - 1).U) {
            isInit := false.B
            tail := numFreeRegisters.U
        }
    }

    // Prefetch Logic
    val prefetchValid = RegInit(false.B)
    val prefetchData = Reg(UInt(width.W))

    // Helper signals
    val empty = head === tail
    val ramHasData = !empty
    val consuming = io.allocate.fire

    // We need to refill if either:
    // 1. Currently invalid (startup or ran dry)
    // 2. Currently valid but consuming the data (pipeline refill)
    val needsRefill = !prefetchValid || consuming

    // Define deq (read from RAM) logic
    val deq = WireDefault(false.B)

    when(!isInit) {
        when(ramHasData && needsRefill) {
            // Case 1: Refill (either from empty, or back-to-back)
            prefetchData := ram.read(head(ptrWidth - 1, 0))
            prefetchValid := true.B
            deq := true.B // Increment head
        }.elsewhen(consuming) {
            // Case 2: We consumed data, but RAM was empty.
            // We run dry.
            prefetchValid := false.B
        }
    }

    // Output assignments
    io.allocate.valid := prefetchValid
    io.allocate.bits := prefetchData

    // Free Logic
    io.free.ready := !isInit
    io.rollbackFree(0).ready := !isInit
    io.rollbackFree(1).ready := !isInit

    val doFree = io.free.fire
    val doRollback0 = io.rollbackFree(0).fire
    val doRollback1 = io.rollbackFree(1).fire
    val numEnq = doFree.asUInt +& doRollback0.asUInt +& doRollback1.asUInt

    when(doFree) {
        ram.write(tail(ptrWidth - 1, 0), io.free.bits)
    }

    val rollbackIdx0 = tail + doFree.asUInt
    when(doRollback0) {
        ram.write(rollbackIdx0(ptrWidth - 1, 0), io.rollbackFree(0).bits)
    }

    val rollbackIdx1 = rollbackIdx0 + doRollback0.asUInt
    when(doRollback1) {
        ram.write(rollbackIdx1(ptrWidth - 1, 0), io.rollbackFree(1).bits)
    }

    when(!isInit) {
        // This assertion will trigger in simulation if the Renamer/ROB logic
        // attempts to return more registers than there exist in the system.
        val nextFreeCount = tail - head +& numEnq - deq.asUInt
        assert(
          nextFreeCount <= numFreeRegisters.U,
          "FreeList Overflow: Architectural limit of %d regs exceeded! head=%d tail=%d numEnq=%d deq=%d nextCount=%d",
          numFreeRegisters.U,
          head,
          tail,
          numEnq,
          deq.asUInt,
          nextFreeCount
        )

        head := head + deq.asUInt
        tail := tail + numEnq
    }
}
