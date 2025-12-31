package components.frontend

import chisel3._
import chisel3.util._

class BranchTargetBuffer extends Module {

    val io = IO(new Bundle {
        val pc = Input(UInt(32.W))
        val target = Output(Valid(UInt(32.W)))
    })

    class BTBEntry extends Bundle{
        val tag = UInt(25.W)
        val target = UInt(32.W)
    }
    val buffer = SyncReadMem(32, new BTBEntry)
    val index = io.pc(6, 2)
    val tag = io.pc(31, 7)
    val entry = buffer.read(index)
    when (entry.tag === tag) {
        io.target.valid := true.B
        io.target.bits := entry.target
    } .otherwise {
        io.target.valid := false.B
        io.target.bits := 0.U
    }
}
