# Memory-Mapped I/O (MMIO)

## Overview

To support mmio, instead of directly send load/store requests to the data cache(LSU), we first send it to a routing module(MMIO Router). The MMIO Router will decide whether the request is a normal memory access or an mmio access. If it is a normal memory access, it will forward the request to the data cache(LSU) as usual. If it is an mmio access, it will forward the request to the mmio device.

## MMIO Address Range

all MMIO devices have the highest bit of the address set to 1, which means the address range is from 0x8000000 to 0xFFFFFFFF.

## MMIO Device Code

- `0x8000_0000`: Print Device
- `0xFFFF_FFFF`: Exit Device
