# Project Structure

This repository is organized as follows:

```
.
├── build.mill               # Mill build definition
├── README.md                # Project overview
├── docs/                    # Documentation (this folder)
├── notes/                   # Personal/internal notes
├── src/                     # Chisel/Scala sources
│   ├── common/              # Shared types/config/bundles
│   ├── components/          # Core microarchitecture components
│   │   ├── backend/         # Backend components
│   │   ├── frontend/        # Frontend components
│   │   ├── structures/      # Structural components that build on predefined data structures
│   │   └── wrappers/        # Wrapper modules around external components like memories
│   ├── core/                # Top-level core integration
│   └── utility/             # Utilities/helpers: things that can be reused across projects
└── test/                    # Tests
```
