package core

import utility.CycleAwareModule

class Frontend(val hexFile: String) extends CycleAwareModule {
    ??? // TODO implement Frontend
}

class Backend extends CycleAwareModule {
    ??? // TODO implement Backend
}

object BoomCoreSections {
    def connectFrontendBackend(frontend: Frontend, backend: Backend): Unit = {
        // TODO implement connection logic
    }
}