# Terminology

## Functional Units

The backend system differs from the original BOOM implementation to better suit a single-core out-of-order pipeline.

### Arithmetic Logic Queue (ALQ)

Originally part of the Integer Issue Queue (IIQ) in BOOM, the ALQ has been separated to handle all arithmetic and logic operations but no comparisons.

### Branch Unit (BRU)

Originally part of the Integer Issue Queue (IIQ) in BOOM, the BRU has been separated to handle all branch and comparison operations since in the simulation system the small instruction window size means and low memory latency means that stress will be placed on IIQ entries.

### Load Store Queue (LSQ)

Responsible for handling all memory-related operations. Includes:

- Load Queue (LDQ): Handles load operations.
- Store Queue (STQ): Handles store operations. Originally split into Store Address Queue (SAQ) and Store Data Queue (SDQ) in BOOM, but combined here for simplicity (we do not expect to implement store-to-load forwarding and speculative loads in this project).
