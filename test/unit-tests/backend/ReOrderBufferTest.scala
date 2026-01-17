package components.backend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import common._
import common.Configurables._

class ReOrderBufferTest extends AnyFlatSpec with Matchers {
    "ReOrderBuffer" should "assign robTags and commit instructions in order" in {
        simulate(new ReOrderBuffer) { dut =>
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)

            // Dispatch 1
            dut.io.dispatch.valid.poke(true.B)
            dut.io.dispatch.bits.ldst.poke(1.U)
            dut.io.dispatch.bits.pdst.poke(10.U)
            dut.io.dispatch.bits.stalePdst.poke(5.U)

            dut.io.robTag.expect(0.U)
            dut.clock.step()

            // Dispatch 2
            dut.io.dispatch.bits.ldst.poke(2.U)
            dut.io.dispatch.bits.pdst.poke(11.U)
            dut.io.dispatch.bits.stalePdst.poke(6.U)
            dut.io.robTag.expect(1.U)
            dut.clock.step()
            dut.io.dispatch.valid.poke(false.B)

            // Commit should not be valid yet
            dut.io.commit.valid.expect(false.B)

            // Broadcast completion for instruction 0
            dut.io.broadcastInput.valid.poke(true.B)
            dut.io.broadcastInput.bits.robTag.poke(0.U)
            dut.clock.step()
            dut.io.broadcastInput.valid.poke(false.B)

            // Now instruction 0 should be ready to commit
            dut.io.commit.valid.expect(true.B)
            dut.io.commit.bits.ldst.expect(1.U)
            dut.io.commit.ready.poke(true.B)
            dut.clock.step()

            // Instruction 1 is not ready yet
            dut.io.commit.valid.expect(false.B)

            // Broadcast completion for instruction 1
            dut.io.broadcastInput.valid.poke(true.B)
            dut.io.broadcastInput.bits.robTag.poke(1.U)
            dut.clock.step()
            dut.io.broadcastInput.valid.poke(false.B)

            dut.io.commit.valid.expect(true.B)
            dut.io.commit.bits.ldst.expect(2.U)
        }
    }

    it should "handle rollback correctly" in {
        simulate(new ReOrderBuffer) { dut =>
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)

            // Dispatch 3 instructions
            dut.io.dispatch.valid.poke(true.B)
            for (i <- 0 until 3) {
                dut.io.dispatch.bits.ldst.poke((i + 1).U)
                dut.io.dispatch.bits.pdst.poke((i + 10).U)
                dut.clock.step()
            }
            dut.io.dispatch.valid.poke(false.B)

            // Rollback to instruction 0 (mispredict at tag 0)
            dut.io.brUpdate.valid.poke(true.B)
            dut.io.brUpdate.bits.robTag.poke(0.U)
            dut.io.brUpdate.bits.mispredict.poke(true.B)
            dut.clock.step()
            dut.io.brUpdate.valid.poke(false.B)

            // Should see rollback for instruction 2, then 1
            dut.io.rollback.valid.expect(true.B)
            dut.io.rollback.bits.ldst.expect(3.U)
            dut.clock.step()

            dut.io.rollback.valid.expect(true.B)
            dut.io.rollback.bits.ldst.expect(2.U)
            dut.clock.step()

            // Rollback finished
            dut.io.rollback.valid.expect(false.B)
        }
    }
}
