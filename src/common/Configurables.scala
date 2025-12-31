package common

object Configurables {
    val PREG_WIDTH = 6
    val ROB_WIDTH = 6
    val FTQ_WIDTH = 4 // for storing the PC for branch unit use
    val IMEM_WIDTH = 8 // 1KB instruction memory (32-bit per slot)
    val MEM_WIDTH = 12 // 4KB data memory (8-bit per slot)

    object Derived {
        val PREG_COUNT = 2 << PREG_WIDTH
        val ROB_COUNT = 2 << ROB_WIDTH
        val FTQ_SIZE = 2 << FTQ_WIDTH
        val IMEM_SIZE = 2 << IMEM_WIDTH
        val MEM_SIZE = 2 << MEM_WIDTH
    }
}
