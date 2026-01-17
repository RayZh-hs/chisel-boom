package components.structures

import chisel3._
import chisel3.util._
import utility.CycleAwareModule

class FreeList(numRegs: Int, numArchRegs: Int) extends CycleAwareModule {
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

    // --- Optimized Prefetch Logic ---
    val prefetchValid = RegInit(false.B)
    val prefetchData = Reg(UInt(width.W))

    // Helper signals
    val empty = head === tail
    val ramHasData = !empty

    // We consume the prefetch register if the output fires
    val consuming = io.allocate.fire

    // We need to refill if:
    // 1. We are currently invalid (startup or ran dry)
    // 2. OR we are currently valid, but consuming the data (pipeline refill)
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

    // --- Free Logic ---

    io.free.ready := !isInit
    io.rollbackFree.ready := !isInit

    val doFree = io.free.fire
    val doRollback = io.rollbackFree.fire
    val numEnq = doFree.asUInt +& doRollback.asUInt

    when(doFree) {
        ram.write(tail(ptrWidth - 1, 0), io.free.bits)
    }

    val rollbackIdx = tail + doFree.asUInt
    when(doRollback) {
        ram.write(rollbackIdx(ptrWidth - 1, 0), io.rollbackFree.bits)
    }

    when(!isInit) {
        // Assert logic
        // We calculate next state to ensure no overflow
        assert(
          (tail - head +& numEnq - deq.asUInt) <= numFreeRegisters.U,
          "FreeList Overflow: Architectural limit of %d regs exceeded!",
          numFreeRegisters.U
        )

        head := head + deq.asUInt
        tail := tail + numEnq
    }
}
