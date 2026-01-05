package components.structures

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PhysicalRegisterFileTest extends AnyFlatSpec with Matchers {
    "PhysicalRegisterFile" should "write and read data correctly" in {
        simulate(new PhysicalRegisterFile(64)) { dut =>
            // Initial state: all ready
            dut.io.readyAddrs(0).poke(1.U)
            dut.io.isReady(0).expect(true.B)

            // Set busy
            dut.io.setBusy.valid.poke(true.B)
            dut.io.setBusy.bits.poke(1.U)
            dut.clock.step()
            dut.io.setBusy.valid.poke(false.B)
            dut.io.isReady(0).expect(false.B)

            // Write data
            dut.io.write(0).en.poke(true.B)
            dut.io.write(0).addr.poke(1.U)
            dut.io.write(0).data.poke("hDEADBEEF".U)
            dut.clock.step()
            dut.io.write(0).en.poke(false.B)

            // Set ready
            dut.io.setReady.valid.poke(true.B)
            dut.io.setReady.bits.poke(1.U)
            dut.clock.step()
            dut.io.setReady.valid.poke(false.B)
            dut.io.isReady(0).expect(true.B)

            // Read data
            dut.io.read(0).addr.poke(1.U)
            dut.io.read(0).data.expect("hDEADBEEF".U)
        }
    }

    it should "always have register 0 as 0 and ready" in {
        simulate(new PhysicalRegisterFile(64)) { dut =>
            dut.io.readyAddrs(0).poke(0.U)
            dut.io.isReady(0).expect(true.B)

            dut.io.write(0).en.poke(true.B)
            dut.io.write(0).addr.poke(0.U)
            dut.io.write(0).data.poke(0x12345678.U)
            dut.io.setBusy.valid.poke(true.B)
            dut.io.setBusy.bits.poke(0.U)
            dut.clock.step()

            dut.io.read(0).addr.poke(0.U)
            dut.io.read(0).data.expect(0.U)
            dut.io.isReady(0).expect(true.B)
        }
    }
}
