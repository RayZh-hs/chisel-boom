package common

object Configurables {
    val PREG_WIDTH = 6
    val ROB_WIDTH = 6
    val IMEM_WIDTH = 12 // 4096 words = 16KB instruction memory
    val MEM_WIDTH = 14 // 16KB data memory (8-bit per slot)

    object Derived {
        val PREG_COUNT = 1 << PREG_WIDTH
        val ROB_COUNT = 1 << ROB_WIDTH
        val IMEM_SIZE = 1 << IMEM_WIDTH
        val MEM_SIZE = 1 << MEM_WIDTH
    }
}
