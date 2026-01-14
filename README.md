<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="images/chisel-boom-logo-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="images/chisel-boom-logo-light.png">
    <img alt="Chisel Boom Logo" src="images/chisel-boom-logo-light.png" width="300">
  </picture>
</p>

This repository contains a simplified chisel implementation of an Out-of-Order processor, inspired by the **Berkeley Out-of-Order Machine (BOOM)**.

## 📢 Features

The project implements the following key features:
- Full support for the **RISC-V RV32I** instruction set architecture.
- Out-of-Order execution with dynamic scheduling and RAT.
- (WIP) Multi-layer branch prediction to minimize control hazards.

We synthesized the chip using Silicon Compiler, obtaining a max clock frequency of 0.47GHz. Below are sample visualizations from the synthesis process.

| Clock Distribution | Placement Density |
| :---: | :---: |
| ![Clock Distribution](images/clk0_typical.png) | ![Placement Density](images/placement_density.png) |

## 🚀 Getting Started

This project uses the **Mill** build tool.

### Prerequisites

- **JDK 11+**
- **Mill**
- **Verilator** (for simulation)
- **Silicon Compiler** (for synthesis)

For simpler script execution, you can optionally use [Mango](https://github.com/Mango-CLI/Mango).

### Running Tests (Manual)

To run the full test suite (Unit tests + End-to-End tests):
```bash
mill test
```

To compile a single `.c` file and run it on the simulator:
```bash
mill test.runMain e2e.RunCFile test/e2e-tests/resources/c/add_to_100.c
```

Pass in `--verbose` for detailed printf output during the simulation process.

### Running Tests (Mango)

To run all tests using Mango:
```bash
mango test
```

To execute a specific C test file under `test/e2e-tests/resources/c/`:
```bash
mango run <script-name>
```

This will compile and simulate the specified C file, outputting the hex dump and the path to the log file.

### Elaboration

To generate Verilog files:
```bash
mill runMain VerilogEmission <path-to-hex-file>
```

The system verilog generated will be located in `synthesis/output/`.

### Synthesis

To run synthesis using Silicon Compiler, configure the apptainer path in `.env`(see `.env.example`) and execute at project root:

Using Mango:
```bash
mango synthesize
```

Manually:
```
source .env
apptainer exec --bind .:/workspace "$SILICON_COMPILER_APPTAINER_PATH" python3 /workspace/synthesis/Synthesize.py
```

## 🏗 Architecture Overview

The core (`src/core/BoomCore.scala`) connects the following major subsystems:

### Frontend (`src/components/frontend`)
Responsible for feeding instructions to the pipeline.
*   **InstFetcher**: Fetches instructions from memory.
*   **InstDecoder**: Decodes raw bits into control signals.
*   **InstDispatcher**: Dispatches decoded instructions to the backend.
*   **BranchPredictor**: Predicts control flow to minimize stalls.

### Backend (`src/components/backend`)
Manages the out-of-order execution window.
*   **ReOrderBuffer (ROB)**: Ensures instructions commit in-order to maintain precise exceptions.
*   **Adaptors**: Interface layers for execution units (ALUAdaptor, BRUAdaptor, etc.).

### Structures (`src/components/structures`)
The functional blocks providing validity to the architecture.
*   **RegisterAliasTable (RAT)**: Maps architectural registers to physical registers (Renaming).
*   **FreeList**: Manages available physical registers.
*   **IssueBuffers**: Holds instructions until their operands are ready.
*   **Functional Units**: `ArithmeticLogicUnit` (ALU), `BranchUnit`, `LoadStoreUnit`.
*   **PhysicalRegisterFile**: The simplified unified generic register file.

## 📂 Project Structure

```text
.
├── src/
│   ├── common/          # Shared Bundles, Constants, and Enums
│   ├── components/      # Hardware logic modules separated by stage
│   │   ├── frontend/    # Fetch & Decode logic
│   │   ├── backend/     # Commit & Dispatch logic, Adaptors
│   │   └── structures/  # State-holding elements (RAT, ROB, IssueQueues)
│   ├── core/            # Top-level wiring (BoomCore)
│   └── utility/         # Helper utils (CycleAwareModule, etc.)
├── synthesis/           # Verilog generation scripts
├── test/                # Unit tests and E2E system tests
└── docs/                # Detailed documentation files
```
