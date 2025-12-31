package common

object Configurables {
    val PREG_WIDTH = 6
    val ROB_WIDTH = 6
    val FTQ_WIDTH = 4 // for storing the PC for branch unit use

    object Derived {
        val PREG_COUNT = 2 << PREG_WIDTH
        val ROB_COUNT = 2 << ROB_WIDTH
        val FTQ_SIZE = 2 << FTQ_WIDTH
    }
}
