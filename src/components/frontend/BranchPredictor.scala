package components.frontend

import chisel3._
import chisel3.util._

class BranchTargetBuffer extends Module {

    val io = IO(new Bundle {
        // Predictor interface
        val pc = Input(UInt(32.W))
        val target = Output(Valid(UInt(32.W)))

        // Update interface
        val update = Input(Valid(new Bundle {
            val pc = UInt(32.W)
            val target = UInt(32.W)
            val taken = Bool()
            val mispredict = Bool()
        }))
    })

    class BTBEntry extends Bundle {
        val tag = UInt(25.W)
        val target = UInt(32.W)
    }
    val buffer = SyncReadMem(32, new BTBEntry)
    val valids = RegInit(0.U(32.W))
    val counters = RegInit(VecInit(Seq.fill(32)(0.U(2.W))))
    val index = io.pc(6, 2)
    val tag = io.pc(31, 7)

    val validReg = RegNext(valids(index))
    val tagReg = RegNext(tag)
    val entry = buffer.read(index)
    val countReg = RegNext(counters(index))

    when(validReg && entry.tag === tagReg && countReg(1)) {
        io.target.valid := true.B
        io.target.bits := entry.target
    }.otherwise {
        io.target.valid := false.B
        io.target.bits := 0.U
    }

    when(io.update.valid) {
        val updIndex = io.update.bits.pc(6, 2)
        val updTag = io.update.bits.pc(31, 7)
        val newEntry = Wire(new BTBEntry)
        newEntry.tag := updTag
        newEntry.target := io.update.bits.target

        val cnt = counters(updIndex)
        when(io.update.bits.taken) {
            buffer.write(updIndex, newEntry)
            valids := valids | (1.U << updIndex)
            counters(updIndex) := Mux(cnt === 3.U, 3.U, cnt + 1.U)
        }.otherwise {
            counters(updIndex) := Mux(cnt === 0.U, 0.U, cnt - 1.U)
        }
    }
}
