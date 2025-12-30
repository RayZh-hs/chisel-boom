package common

object Configurables {
    val PREG_WIDTH = 6
    val ROB_WIDTH = 6
    val BR_DEPTH = 2    // the tag length of branch prediction
    val FTQ_WIDTH = 4   // for storing the PC for branch unit use

    object Derived {
        val PREG_COUNT = 2 << PREG_WIDTH
        val ROB_COUNT = 2 << ROB_WIDTH
        val BR_COUNT = 2 << BR_DEPTH
        val FTQ_SIZE = 2 << FTQ_WIDTH
    }
}
