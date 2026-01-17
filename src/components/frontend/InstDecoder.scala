package components.frontend

import chisel3._
import chisel3.util._
import common._
import common.Configurables._
import utility.CycleAwareModule

/**
  * Instruction Decoder
  *
  * Decodes RISC-V instructions into control signals for the backend.
  */
class InstDecoder extends CycleAwareModule {
    // IO Definition
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new FetchToDecodeBundle))
        val out = Decoupled(new DecodedInstBundle)
    })

    when(io.in.valid && io.out.ready) {
        printf(
          p"Decoding Inst: 0x${Hexadecimal(io.in.bits.inst)} at PC: 0x${Hexadecimal(io.in.bits.pc)}\n"
        )
    }

    val inst = io.in.bits.inst
    val pc = io.in.bits.pc

    // Extract fields
    val opcode = inst(6, 0)
    val rd = inst(11, 7)
    val funct3 = inst(14, 12)
    val rs1 = inst(19, 15)
    val rs2 = inst(24, 20)
    val funct7 = inst(31, 25)

    // Immediates
    private def signExt(x: UInt, len: Int): UInt = {
        val w = x.getWidth
        if (w >= len) x(len - 1, 0)
        else Cat(Fill(len - w, x(w - 1)), x)
    }

    val iImm = signExt(inst(31, 20), 32)
    val sImm = signExt(inst(31, 25) ## inst(11, 7), 32)
    val bImm = signExt(
      inst(31) ## inst(7) ## inst(30, 25) ## inst(11, 8) ## 0.U(1.W),
      32
    )
    val uImm = signExt(inst(31, 12) ## 0.U(12.W), 32)
    val jImm = signExt(
      inst(31) ## inst(19, 12) ## inst(20) ## inst(30, 21) ## 0.U(1.W),
      32
    )

    // Default Signals
    val fUnitType = Wire(FunUnitType())
    val aluOpType = Wire(ALUOpType())
    val multOpType = Wire(MultOpType())
    val bruOpType = Wire(BRUOpType())
    val cmpOpType = Wire(CmpOpType())
    val memOpWidth = Wire(MemOpWidth())
    val isLoad = Wire(Bool())
    val isStore = Wire(Bool())
    val isUnsigned = Wire(Bool())
    val useImm = Wire(Bool())
    val imm = Wire(UInt(32.W))

    // Defaults
    fUnitType := FunUnitType.ALU
    aluOpType := ALUOpType.ADD
    multOpType := MultOpType.MUL
    bruOpType := BRUOpType.CBR
    cmpOpType := CmpOpType.EQ
    memOpWidth := MemOpWidth.WORD
    isLoad := false.B
    isStore := false.B
    isUnsigned := false.B
    useImm := false.B
    imm := 0.U

    // Decoding Logic
    when(opcode === "b0110111".U) { // LUI
        fUnitType := FunUnitType.ALU
        aluOpType := ALUOpType.LUI
        useImm := true.B
        imm := uImm
    }.elsewhen(opcode === "b0010111".U) { // AUIPC
        fUnitType := FunUnitType.BRU
        bruOpType := BRUOpType.AUIPC
        useImm := true.B
        imm := uImm
    }.elsewhen(opcode === "b1101111".U) { // JAL
        fUnitType := FunUnitType.BRU
        bruOpType := BRUOpType.JAL
        useImm := true.B
        imm := jImm
    }.elsewhen(opcode === "b1100111".U) { // JALR
        fUnitType := FunUnitType.BRU
        bruOpType := BRUOpType.JALR
        useImm := true.B
        imm := iImm
    }.elsewhen(opcode === "b1100011".U) { // BRANCH
        fUnitType := FunUnitType.BRU
        bruOpType := BRUOpType.CBR
        useImm := true.B
        imm := bImm
        switch(funct3) {
            is(0.U) { cmpOpType := CmpOpType.EQ }
            is(1.U) { cmpOpType := CmpOpType.NEQ }
            is(4.U) { cmpOpType := CmpOpType.LT }
            is(5.U) { cmpOpType := CmpOpType.GE }
            is(6.U) { cmpOpType := CmpOpType.LTU }
            is(7.U) { cmpOpType := CmpOpType.GEU }
        }
    }.elsewhen(opcode === "b0000011".U) { // LOAD
        fUnitType := FunUnitType.MEM
        isLoad := true.B
        useImm := true.B
        imm := iImm
        switch(funct3) {
            is(0.U) { memOpWidth := MemOpWidth.BYTE; isUnsigned := false.B }
            is(1.U) { memOpWidth := MemOpWidth.HALFWORD; isUnsigned := false.B }
            is(2.U) { memOpWidth := MemOpWidth.WORD; isUnsigned := false.B }
            is(4.U) {
                memOpWidth := MemOpWidth.BYTE; isUnsigned := true.B
            } // LBU
            is(5.U) {
                memOpWidth := MemOpWidth.HALFWORD; isUnsigned := true.B
            } // LHU
        }
    }.elsewhen(opcode === "b0100011".U) { // STORE
        fUnitType := FunUnitType.MEM
        isStore := true.B
        useImm := true.B
        imm := sImm
        switch(funct3) {
            is(0.U) { memOpWidth := MemOpWidth.BYTE }
            is(1.U) { memOpWidth := MemOpWidth.HALFWORD }
            is(2.U) { memOpWidth := MemOpWidth.WORD }
        }
    }.elsewhen(opcode === "b0010011".U) { // ALU I-Type
        fUnitType := FunUnitType.ALU
        useImm := true.B
        imm := iImm
        switch(funct3) {
            is(0.U) { aluOpType := ALUOpType.ADD } // ADDI
            is(2.U) { aluOpType := ALUOpType.SLT } // SLTI
            is(3.U) { aluOpType := ALUOpType.SLTU } // SLTIU
            is(4.U) { aluOpType := ALUOpType.XOR } // XORI
            is(6.U) { aluOpType := ALUOpType.OR } // ORI
            is(7.U) { aluOpType := ALUOpType.AND } // ANDI
            is(1.U) { aluOpType := ALUOpType.SLL } // SLLI
            is(5.U) {
                when(funct7(5)) { aluOpType := ALUOpType.SRA } // SRAI
                    .otherwise { aluOpType := ALUOpType.SRL } // SRLI
            }
        }
    }.elsewhen(opcode === "b0110011".U) { // ALU R-Type or M-Extension
        when(funct7 === "b0000001".U) { // M-Extension
            fUnitType := FunUnitType.MULT
            useImm := false.B
            multOpType := MultOpType.fromInt(funct3)
        }.otherwise {
            fUnitType := FunUnitType.ALU
            useImm := false.B
            switch(funct3) {
                is(0.U) {
                    when(funct7(5)) { aluOpType := ALUOpType.SUB }
                        .otherwise { aluOpType := ALUOpType.ADD }
                }
                is(1.U) { aluOpType := ALUOpType.SLL }
                is(2.U) { aluOpType := ALUOpType.SLT }
                is(3.U) { aluOpType := ALUOpType.SLTU }
                is(4.U) { aluOpType := ALUOpType.XOR }
                is(5.U) {
                    when(funct7(5)) { aluOpType := ALUOpType.SRA }
                        .otherwise { aluOpType := ALUOpType.SRL }
                }
                is(6.U) { aluOpType := ALUOpType.OR }
                is(7.U) { aluOpType := ALUOpType.AND }
            }
        }
    }

    // Register Indices
    val validLdst = opcode === "b0110111".U || // LUI
        opcode === "b0010111".U || // AUIPC
        opcode === "b1101111".U || // JAL
        opcode === "b1100111".U || // JALR
        opcode === "b0000011".U || // LOAD
        opcode === "b0010011".U || // ALU I
        opcode === "b0110011".U // ALU R

    val validLrs1 = opcode === "b1100111".U || // JALR
        opcode === "b1100011".U || // BRANCH
        opcode === "b0000011".U || // LOAD
        opcode === "b0100011".U || // STORE
        opcode === "b0010011".U || // ALU I
        opcode === "b0110011".U // ALU R

    val validLrs2 = opcode === "b1100011".U || // BRANCH
        opcode === "b0100011".U || // STORE
        opcode === "b0110011".U // ALU R

    val ldst = Mux(validLdst, rd, 0.U)
    val lrs1 = Mux(validLrs1, rs1, 0.U)
    val lrs2 = Mux(validLrs2, rs2, 0.U)

    // Output Assignment
    io.out.valid := io.in.valid
    io.in.ready := io.out.ready

    io.out.bits.fUnitType := fUnitType
    io.out.bits.aluOpType := aluOpType
    io.out.bits.multOpType := multOpType
    io.out.bits.bruOpType := bruOpType
    io.out.bits.cmpOpType := cmpOpType
    io.out.bits.isLoad := isLoad
    io.out.bits.isStore := isStore
    io.out.bits.pc := pc
    io.out.bits.predict := io.in.bits.predict
    io.out.bits.predictedTarget := io.in.bits.predictedTarget
    io.out.bits.lrs1 := lrs1
    io.out.bits.lrs2 := lrs2
    io.out.bits.ldst := ldst
    io.out.bits.prs1 := lrs1 // No renaming
    io.out.bits.prs2 := lrs2 // No renaming
    io.out.bits.pdst := ldst // No renaming
    io.out.bits.stalePdst := 0.U
    io.out.bits.useImm := useImm
    io.out.bits.imm := imm
    io.out.bits.opWidth := memOpWidth
    io.out.bits.isUnsigned := isUnsigned
}
