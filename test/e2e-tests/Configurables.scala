package e2e

import utility.CycleAwareModule

object Configurables {
    val MAX_CYCLE_COUNT = 10000

    assert(
      MAX_CYCLE_COUNT <= CycleAwareModule.Configurables.MAX_CYCLE_COUNT,
      s"MAX_CYCLE_COUNT in E2E tests ($MAX_CYCLE_COUNT) exceeds the limit defined in CycleAwareModule (${CycleAwareModule.Configurables.MAX_CYCLE_COUNT})"
    )
}
