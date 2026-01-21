# Memory-Mapped I/O (MMIO)

## Overview

To support mmio, instead of directly send load/store requests to the data cache(LSU), we first send it to a routing module(MMIO Router). The MMIO Router will decide whether the request is a normal memory access or an mmio access. If it is a normal memory access, it will forward the request to the data cache(LSU) as usual. If it is an mmio access, it will forward the request to the mmio device.

## MMIO Address Range

All MMIO devices have the highest bit of the address set to 1, which means the address range is from 0x8000000 to 0xFFFFFFFF.

## MMIO Device Code

- `0x8000_0000`: Print Device
- `0x8000_0004`: Input Device
- `0xFFFF_FFFF`: Exit Device

## MMIO Devices

### Input Device

The input device occupies addresses `0x8000_0004 - 0x8000_0007`. It is a read-only interface that allows the program to read a single byte of input from the user.

- `0x8000_0004`: Byte data input.
- `0x8000_0005`: Status flag (0: no data, -1: data available).

The input device should be used either as (builtin `get() -> u32` abi):

1. Read word from address `0x8000_0004` to get the byte data.
2. Write byte `0` to address `0x8000_0004`. This will mark the data as consumed.

Or as (builtin `peek() -> u32` abi):

1. Read word from address `0x8000_0004` to get the byte data.
2. Write byte `-1` to address `0x8000_0004`. This acts as a peek operation, leaving the valid flag unchanged.

Note that although the actual status flag is stored at address `0x8000_0005`, we write byte to address `0x8000_0004` to update the status flag. This is because the MMIO interface only supports word-aligned accesses.

Any access to the input device that does not follow the above protocols is Undefined Behavior.