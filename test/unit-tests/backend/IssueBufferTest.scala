package components.backend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import common._
import components.structures.{ALUInfo, IssueBuffer}

class IssueBufferTest extends AnyFlatSpec with Matchers {
    def resetDut[T <: Data](dut: IssueBuffer[T]): Unit = {
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step(2)
    }

    "IssueBuffer" should "enqueue and issue an instruction when operands are ready" in {
        simulate(new IssueBuffer(new ALUInfo, 4, "IB")) { dut =>
            // Reset DUT
            resetDut(dut)

            // Enqueue an instruction with ready operands
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)

            dut.io.in.valid.poke(true.B)
            dut.io.in.bits.robTag.poke(1.U)
            dut.io.in.bits.pdst.poke(10.U)
            dut.io.in.bits.src1.poke(5.U)
            dut.io.in.bits.src2.poke(6.U)
            dut.io.in.bits.src1Ready.poke(true.B)
            dut.io.in.bits.src2Ready.poke(true.B)
            dut.io.in.bits.info.aluOp.poke(ALUOpType.ADD)

            dut.io.out.ready.poke(true.B)
            dut.clock.step()
            dut.io.in.valid.poke(false.B)

            // Should issue immediately
            dut.io.out.valid.expect(true.B)
            dut.io.out.bits.pdst.expect(10.U)
            dut.io.out.bits.robTag.expect(1.U)
        }
    }

    it should "wait for broadcast to wake up instructions" in {
        simulate(new IssueBuffer(new ALUInfo, 4, "IB")) { dut =>
            // Reset DUT
            resetDut(dut)

            // Enqueue an instruction with non-ready src1
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)

            dut.io.broadcast.valid.poke(false.B)
            dut.io.in.valid.poke(true.B)
            dut.io.in.bits.src1.poke(5.U)
            dut.io.in.bits.src1Ready.poke(false.B)
            dut.io.in.bits.src2Ready.poke(true.B)
            dut.clock.step()
            dut.io.in.valid.poke(false.B)

            // Should not issue
            dut.io.out.valid.expect(false.B)

            // Broadcast src1
            dut.io.broadcast.valid.poke(true.B)
            dut.io.broadcast.bits.pdst.poke(5.U)
            dut.clock.step()
            dut.io.broadcast.valid.poke(false.B)

            // Should now be ready to issue
            dut.io.out.valid.expect(true.B)
        }
    }

    it should "flush younger instructions on redirect" in {
        // Enqueue 3 instructions:
        // 1. Tag 1 (Older, keep)
        // 2. Tag 2 (The branch, keep)
        // 3. Tag 3 (Younger, kill)
        simulate(new IssueBuffer(new ALUInfo, 8, "IB")) { dut =>
            // Reset DUT
            resetDut(dut)

            dut.io.in.valid.poke(true.B)
            dut.io.in.bits.robTag.poke(1.U)
            dut.io.in.bits.src1.poke(10.U) // Waiting on P10
            dut.io.in.bits.src1Ready.poke(false.B)
            dut.io.in.bits.src2Ready.poke(true.B)
            dut.clock.step()

            dut.io.in.bits.robTag.poke(2.U)
            dut.clock.step()

            dut.io.in.bits.robTag.poke(3.U)
            dut.clock.step()
            dut.io.in.valid.poke(false.B)

            // Flush with tag=2, head=0. Tag > 2 should be killed.
            dut.io.flush.valid.poke(true.B)
            dut.io.flush.flushTag.poke(2.U)
            dut.io.flush.robHead.poke(0.U)
            dut.clock.step()
            dut.io.flush.valid.poke(false.B)

            // Wake up instructions
            dut.io.broadcast.valid.poke(true.B)
            dut.io.broadcast.bits.pdst.poke(10.U)
            dut.clock.step()
            dut.io.broadcast.valid.poke(false.B)

            // Should see Tag 1
            dut.io.out.valid.expect(true.B)
            dut.io.out.bits.robTag.expect(1.U)
            dut.io.out.ready.poke(true.B)
            dut.clock.step()

            // Should see Tag 2
            dut.io.out.valid.expect(true.B)
            dut.io.out.bits.robTag.expect(2.U)
            dut.clock.step()

            // Should NOT see Tag 3
            dut.io.out.valid.expect(false.B)
        }
    }

    it should "flush younger instructions on redirect (wrap-around)" in {
        simulate(new IssueBuffer(new ALUInfo, 8, "IB")) { dut =>
            // Reset DUT
            resetDut(dut)

            // ROB Head = 60. Flush Tag = 2.
            // Range [60, max] and [0, 2] are safe.
            // 61 -> Safe
            // 2  -> Safe
            // 3  -> Kill
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)

            dut.io.in.valid.poke(true.B)
            dut.io.in.bits.robTag.poke(61.U)
            dut.io.in.bits.src1.poke(10.U)
            dut.io.in.bits.src1Ready.poke(false.B)
            dut.io.in.bits.src2Ready.poke(true.B)
            dut.clock.step()

            dut.io.in.bits.robTag.poke(2.U)
            dut.clock.step()

            dut.io.in.bits.robTag.poke(3.U)
            dut.clock.step()
            dut.io.in.valid.poke(false.B)

            // Flush
            dut.io.flush.valid.poke(true.B)
            dut.io.flush.flushTag.poke(2.U)
            dut.io.flush.robHead.poke(60.U)
            dut.clock.step()
            dut.io.flush.valid.poke(false.B)

            // Wake up
            dut.io.broadcast.valid.poke(true.B)
            dut.io.broadcast.bits.pdst.poke(10.U)
            dut.clock.step()
            dut.io.broadcast.valid.poke(false.B)

            // Should see Tag 61
            dut.io.out.valid.expect(true.B)
            dut.io.out.bits.robTag.expect(61.U)
            dut.io.out.ready.poke(true.B)
            dut.clock.step()

            // Should see Tag 2
            dut.io.out.valid.expect(true.B)
            dut.io.out.bits.robTag.expect(2.U)
            dut.clock.step()

            // Should NOT see Tag 3
            dut.io.out.valid.expect(false.B)
        }
    }
}
