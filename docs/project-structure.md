# Project structure

This repository is organized as follows:

```
.
├── build.mill               # Mill build definition
├── README.md                # Project overview
├── docs/                    # Documentation (this folder)
├── notes/                   # Personal/internal notes
├── src/                     # Chisel/Scala sources
│   ├── common/              # Shared types/config/bundles
│   │   ├── Configurables.scala
│   │   ├── IOBundles.scala
│   │   └── MicroOps.scala
│   ├── components/          # Core microarchitecture components
│   │   ├── backend/
│   │   ├── frontend/
│   │   │   ├── InstDecoder.scala
│   │   │   └── InstFetcher.scala
│   │   ├── structures/
│   │   └── wrappers/        # Memory/regfile wrappers + local docs
│   │       ├── InstructionMemory.scala
│   │       ├── Memory.scala
│   │       ├── PhysicalRegisterFile.scala
│   │       └── README.md
│   ├── core/                # Top-level core integration
│   │   ├── BoomCore.scala
│   │   └── BoomCoreSections.scala
│   └── utility/             # Utilities/helpers
│       └── CycleAwareModule.scala
└── test/                    # Tests
```
