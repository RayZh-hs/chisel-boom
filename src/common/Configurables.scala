package common

object Configurables {

    // Constants are UPPERCASE, Compile-time flags are camelCase
    val PREG_WIDTH = 6
    val ROB_WIDTH  = 6
    val IMEM_WIDTH = 12     // 4096 words = 16KB instruction memory
    val MEM_WIDTH  = 14     // 16KB data memory (8-bit per slot)
    val RAS_WIDTH  = 3      // Return Address Stack size

    // Configuration for debug output.
    // Set to true to enable printf outputs in the simulation by default;
    // Otherwise, leave as is and use -v/--verbose flag to enable in mill.
    var verbose: Boolean = false

    // Profiling support:
    // Set to true to enable profiling wiring in the design;
    // It will automatically be disabled in synthesis builds.
    object Profiling {
        var branchMispredictionRate: Boolean = true    // Track branch misprediction rate
        var Utilization:             Boolean = true    // Track utilization of various structures
        var IPC:                     Boolean = true    // Track IPC (Instructions Per Cycle)
        var RollbackTime:            Boolean = true    // Track average rollback time
        var CacheStats:              Boolean = true    // Track L1 D-Cache/I-Cache stats

        // Shorthand for any profiling option being enabled
        def isAnyEnabled: Boolean = {
            branchMispredictionRate || Utilization || IPC || RollbackTime || CacheStats
        }

        // Utility for automatic pruning in synthesis.
        // This will be automatically called when building for synthesis to avoid unnecessary overhead.
        def prune() = {
            branchMispredictionRate = false
            Utilization = false
            IPC = false
            RollbackTime = false
            CacheStats = false
        }
    }

    // Elaboration options for debugging.
    // These options add extra fields to structures to facilitate debugging.
    // They will automatically be suppressed in synthesis.
    object Elaboration {
        var pcInROB:              Boolean = true    // this option adds a PC field to each ROB entry for easier debugging
        var pcInIssueBuffer:      Boolean = true    // this option adds a PC field to each Issue Buffer entry for easier debugging
        var printOnBroadcast:     Boolean = true    // Print CDB broadcasts
        var printOnMemAccess:     Boolean = true    // Print memory accesses (loads/stores)
        var printRegFileOnCommit: Boolean = false   // Print the register file state on each commit
        var cycleAwareness:       Boolean = true    // Add cycle count to debug prints for easier tracing

        // Utility for automatic pruning in synthesis.
        // This will be automatically called when building for synthesis to avoid unnecessary overhead.
        def prune() = {
            pcInROB          = false
            pcInIssueBuffer  = false
            printOnBroadcast = false
            printOnMemAccess = false
            cycleAwareness   = false
        }
    }

    // Derived constants from the above
    object Derived {
        val PREG_COUNT = 1 << PREG_WIDTH
        val ROB_COUNT  = 1 << ROB_WIDTH
        val IMEM_SIZE  = 1 << IMEM_WIDTH
        val MEM_SIZE   = 1 << MEM_WIDTH
        val RAS_SIZE   = 1 << RAS_WIDTH
    }
}
