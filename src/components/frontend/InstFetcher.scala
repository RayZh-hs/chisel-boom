package components.frontend

import chisel3._
import chisel3.util._
import common._
import utility.CycleAwareModule

class InstFetcher extends CycleAwareModule {
    val io = IO(new Bundle {
        val pcOverwrite =
            Input(Valid(UInt(32.W))) // Overwrite PC when misprediction occurs
        val instAddr =
            Output(UInt(32.W)) // Inst Addr to be routed to BTB and ICache
        val instData = Input(UInt(32.W)) // Inst Data from ICache
        val instValid = Input(Bool()) // New: ICache Response Valid
        val instReady = Input(Bool()) // New: ICache Ready
        val btbResult = Input(Valid(UInt(32.W))) // Branch Target from BTB
        val ifOut = Decoupled(
          new FetchToDecodeBundle()
        ) // Output to Decode Stage (wire into queue)
    })

    val pc = RegInit(0.U(32.W))

    // Forward declaration: Stage 2 states
    val s2Valid = RegInit(false.B)
    val s2PC = Reg(UInt(32.W))
    val s2Inst = Reg(UInt(32.W)) // Hold instruction until decode accepts
    val s2Waiting = RegInit(false.B) // New: Waiting for I-Cache

    // =========================================================
    // Control Signals
    // =========================================================

    // In S2 we can send data to decode when the data is valid and Decode is ready.
    val s2Fire = s2Valid && io.ifOut.ready

    // In S1 we can fetch a new instruction if any of the following is true:
    // 1. S2 is empty (!s2_valid)
    // 2. S2 is moving to Decode (s2_fire)
    // 3. We are flushing (io.pcOverwrite) -> everything is cleared and we restart
    // 4. BUT we must not be waiting for I-Cache fill for S2
    val s1Ready = (!s2Valid || s2Fire) && !s2Waiting || io.pcOverwrite.valid
    
    // Request Accepted only if S1 is Ready AND Cache is Ready
    val s1Fire = s1Ready && io.instReady

    // =========================================================
    // Stage 1 (S1): PC Generation & Request
    // =========================================================

    // fetchAddr is what is sent to ICache and BTB
    val fetchAddr = Wire(UInt(32.W))
    when(io.pcOverwrite.valid) {
        fetchAddr := io.pcOverwrite.bits
    }.elsewhen(s2Waiting) {
        fetchAddr := s2PC // Hold the target pc if S2 is not moving
    }.elsewhen(io.btbResult.valid) {
        fetchAddr := io.btbResult.bits
    }.otherwise {
        fetchAddr := pc
    }
    io.instAddr := fetchAddr

    // Update PC for next cycle
    val nextPC = Wire(UInt(32.W))
    when(io.pcOverwrite.valid) {
        // Priority 1: External Flush / Exception (Overrides everything)
        // If Cache Accepted (instReady), we move to Next.
        // If Cache Stalled, we move to Target (to retry).
        when(io.instReady) {
            nextPC := io.pcOverwrite.bits + 4.U
        } .otherwise {
            nextPC := io.pcOverwrite.bits
        }
    }.elsewhen(io.btbResult.valid) {
        // Priority 2: Branch Prediction Redirect
        // Only advance if Cache Accepted
        when(io.instReady) {
            nextPC := io.btbResult.bits + 4.U
        } .otherwise {
            nextPC := pc // Retry same PC? Or lost prediction?
            // If we stall, we fetch 'pc' next cycle. BTB should predict again.
        }
    }.elsewhen(s1Ready) {
        // Priority 3: S1 is working
        when(io.instReady) {
             nextPC := pc + 4.U
        } .otherwise {
             nextPC := pc
        }
    }.otherwise {
        nextPC := pc
    }
    
    // We only update PC if we are not stalled waiting for I-Cache
    // If s2Waiting is true, logic below handles it.
    // We also must update if we are flushing (pcOverwrite).
    when(!s2Waiting || io.instValid || io.pcOverwrite.valid) {
         pc := nextPC
    }

    // =========================================================
    // Stage 2 (S2): State Update
    // =========================================================

    when(io.pcOverwrite.valid) {
        // If we flush, we abort waiting for the old request.
        // We do NOT set s2Waiting to true yet if not ready.
        // We rely on 'pc' holding the target and s2Waiting being false
        // to drive fetchAddr from 'pc' until ready.
        s2Waiting := false.B
        s2Valid := false.B
        
        // If Cache happens to be ready this cycle, we CAN fire immediately.
        when(io.instReady) {
             s2PC := io.pcOverwrite.bits 
             s2Waiting := true.B
        }
    }.elsewhen(s1Fire) {
        // Transition to S2 (Waiting for Data)
        s2PC := fetchAddr
        s2Waiting := true.B 
        // s2Valid is NOT true yet. It becomes true when instValid arrives.
        s2Valid := false.B
    }.elsewhen(s2Waiting && io.instValid) {
        // Data Arrived
        s2Valid := true.B
        s2Inst := io.instData
        s2Waiting := false.B
    }.elsewhen(s2Fire) {
        s2Valid := false.B
    }

    // Mask valid if flushing current cycle
    io.ifOut.valid := s2Valid && !io.pcOverwrite.valid

    io.ifOut.bits.pc := s2PC
    io.ifOut.bits.inst := s2Inst
    io.ifOut.bits.predict := io.btbResult.valid
    io.ifOut.bits.predictedTarget := io.btbResult.bits

    // Debugging
    // printf(p"IF: PC=0x${Hexadecimal(pc)} s2Wait=$s2Waiting s2Valid=$s2Valid Redirect=${io.pcOverwrite.valid} Ready=${io.instReady} InstValid=${io.instValid} InstAddr=0x${Hexadecimal(io.instAddr)}\n")
    when(io.ifOut.fire) {
        printf(
          p"FETCH: PC=0x${Hexadecimal(io.ifOut.bits.pc)} Inst=0x${Hexadecimal(io.ifOut.bits.inst)} Predict=${io.ifOut.bits.predict}\n"
        )
    }
    when(io.pcOverwrite.valid) {
        printf(p"FETCH: Redirect to 0x${Hexadecimal(io.pcOverwrite.bits)}\n")
    }
}
