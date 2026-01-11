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
        }))
    })

    class BTBEntry extends Bundle{
        val tag = UInt(25.W)
        val target = UInt(32.W)
    }
    val buffer = SyncReadMem(32, new BTBEntry)
    val valids = RegInit(0.U(32.W))
    val index = io.pc(6, 2)
    val tag = io.pc(31, 7)
    
    val valid_reg = RegNext(valids(index))
    val tag_reg = RegNext(tag)
    val entry = buffer.read(index)

    when (valid_reg && entry.tag === tag_reg) {
        io.target.valid := true.B
        io.target.bits := entry.target
    } .otherwise {
        io.target.valid := false.B
        io.target.bits := 0.U
    }

    when (io.update.valid) {
        val upd_index = io.update.bits.pc(6, 2)
        val upd_tag = io.update.bits.pc(31, 7)
        val new_entry = Wire(new BTBEntry)
        new_entry.tag := upd_tag
        new_entry.target := io.update.bits.target
        buffer.write(upd_index, new_entry)
        valids := valids | (1.U << upd_index)
    }
}
