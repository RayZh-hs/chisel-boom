# Memory

Our memory system is designed to interface with a DRAM backend, along with instruction and data caches (ICache and DCache). The memory system supports both normal memory accesses and memory-mapped I/O (MMIO) operations.

## DCache

the data cache handles a request per 2 cycles. It have a variable size and is 1-way(which can be improved) . It uses a state machine to handle misses and refills:

- Idle: waiting for requests from LSU
- TagCheck: checking if the requested data is in the cache
- DramAccess: accessing DRAM for misses
- ReplayReq: replaying the original request after a miss is handled

## ICache

Compared to DCache, the ICache use a pipelined design to achieve 1 cycle per request. It only have 2 states:

- Ready: Can accept new requests
- Refill: Refilling cache lines from DRAM on misses

the pipeline is:

- Stage 1: Receive request
- Stage 2: Tag Check & Hit/Miss

if missed, it will enter Refill state. Note that ICache, unlike DCache, does not Replay the original request. If a miss occurs, the Fetcher will need to resend the request after the refill is complete. This is done by simply saying non-valid and expect the Fetcher to retry. This design is due to the original sram-based ICache's behavior.

## DRAM Interface

Though we want to use some actual DRAM interface such as AXI, currently we use a simplified interface.
The DRAM interface supports request with id, with a read-or-write port and a separate data return port. Instead of burst, it uses a wide(128-bit) data bus to achieve high throughput. Future plan includes a controller to translate this simple interface to AXI or other real DRAM interfaces.

## MMIO

We support a minimum MMIO system, by hardcoding some address to some MMIO device and redirect the request if we sees a exact match of address. Currently we can only support stores(support for loads is on the way).
