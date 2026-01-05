package components.backend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import common._

class IssueBufferTest extends AnyFlatSpec with Matchers {
    "IssueBuffer" should "enqueue and issue an instruction when operands are ready" in {
        simulate(new IssueBuffer(new ALUInfo, 4)) { dut =>
            // Enqueue an instruction with ready operands
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
        simulate(new IssueBuffer(new ALUInfo, 4)) { dut =>
            // Enqueue an instruction with non-ready src1
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

    it should "flush all entries on flush signal" in {
        simulate(new IssueBuffer(new ALUInfo, 4)) { dut =>
            // Enqueue an instruction
            dut.io.in.valid.poke(true.B)
            dut.io.in.bits.src1Ready.poke(true.B)
            dut.io.in.bits.src2Ready.poke(true.B)
            dut.clock.step()
            dut.io.in.valid.poke(false.B)

            dut.io.out.valid.expect(true.B)

            // Flush
            dut.io.flush.poke(true.B)
            dut.clock.step()
            dut.io.flush.poke(false.B)

            // Should be empty
            dut.io.out.valid.expect(false.B)
        }
    }
}
