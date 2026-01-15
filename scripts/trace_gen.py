#!/usr/bin/env python3
import argparse
import subprocess
import sys
import os
import re
from pathlib import Path

# Paths relative to the script location
SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_ROOT = SCRIPT_DIR.parent
TEST_DIR = PROJECT_ROOT / "test" / "e2e-tests"
RESOURCE_DIR = TEST_DIR / "resources"
C_DIR = RESOURCE_DIR / "c"
LINKAGE_DIR = RESOURCE_DIR / "linkage"

# Utilities for finding toolchain
def get_toolchain_prefix():
    bin_dir = os.environ.get("RISCV_BIN", "")
    if bin_dir and not bin_dir.endswith("/"):
        bin_dir += "/"
    
    prefixes = [
        "riscv32-unknown-elf-",
        "riscv64-unknown-elf-",
        "riscv64-linux-gnu-",
        "riscv32-linux-gnu-"
    ]
    
    for p in prefixes:
        gcc = f"{bin_dir}{p}gcc"
        if subprocess.call(f"command -v {gcc} >/dev/null 2>&1", shell=True) == 0:
            return f"{bin_dir}{p}"
    
    # Default fallback
    return "riscv32-unknown-elf-"

PREFIX = get_toolchain_prefix()
GCC = f"{PREFIX}gcc"
OBJCOPY = f"{PREFIX}objcopy"
RUN = f"{PREFIX}run" # gdb simulator

def compile_c(c_file, output_elf):
    crt0 = LINKAGE_DIR / "crt0.S"
    math_c = LINKAGE_DIR / "math.c"
    link_ld = LINKAGE_DIR / "link.ld"
    
    if not c_file.exists():
        print(f"Error: C file not found: {c_file}")
        sys.exit(1)

    cmd = [
        GCC,
        "-march=rv32i",
        "-mabi=ilp32",
        "-O0",
        "-ffreestanding",
        "-mstrict-align",
        "-fno-builtin",
        "-I", str(C_DIR.resolve()),
        "-T", str(link_ld.resolve()),
        "-nostdlib",
        "-static",
        "-Wl,--no-warn-rwx-segments",
        str(crt0.resolve()),
        str(math_c.resolve()),
        str(c_file.resolve()),
        "-o", str(output_elf.resolve())
    ]
    
    # print("Compiling with:", " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print("Compilation failed:")
        print(result.stderr)
        sys.exit(1)
    
    return output_elf

def run_simulation(elf_file):
    # Check if 'run' command matches the toolchain or fallback to 'riscv32-unknown-elf-run'
    sim_cmd = RUN
    if subprocess.call(f"command -v {sim_cmd} >/dev/null 2>&1", shell=True) != 0:
         # try plain riscv32-unknown-elf-run
         sim_cmd = "riscv32-unknown-elf-run"
    
    cmd = [
        sim_cmd, 
        "--trace-insn",
        "--memory-region", "0x80000000,0x4000", # Map 16KB at 0x80000000 for put()
        str(elf_file.resolve())
    ]
    
    # print("Running simulation:", " ".join(cmd))
    # Run and capture stderr (tracing output often goes to stdout or stderr depending on tool version)
    # riscv32-unknown-elf-run outputs trace to stdout usually
     
    # Add memory regions to handle MMIO
    # 0x80000000 for put()
    # 0xFFFFFFFF for exit() - we map a region at the top of memory
    # Use nonstrict alignment to allow write to 0xFFFFFFFF
    
    cmd += [
        "--trace-memory",
        "--memory-region", "0x80000000,0x100", 
        "--memory-region", "0xFFFF0000,0x10000",
        "--alignment", "nonstrict"
    ]

    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    
    stdout, stderr = process.communicate()
    
    return stdout + "\n" + stderr

def parse_trace(trace_output):
    pcs = []
    # Regex to match: insn:     0x00038c                       -add ...
    # We want 0x00038c
    insn_pattern = re.compile(r"^\s*insn:\s+(0x[0-9a-fA-F]+)\s+")
    
    # Regex to detect exit write: memory: 0xffffffff write ...
    # Or just check if line contains "0xffffffff" and "write"
    
    for line in trace_output.splitlines():
        # Check for exit condition first
        # simulator might print address as 0xffffffff
        if "0xffffffff" in line.lower() and "write" in line.lower():
             # We reached the exit write. Stop.
             break

        match = insn_pattern.search(line)
        if match:
            pc_str = match.group(1)
            # Normalize to 0xXXXXXXXX (8 digits)
            pc_int = int(pc_str, 16)
            pcs.append(f"0x{pc_int:08x}")
    return pcs

def main():
    parser = argparse.ArgumentParser(description="Generate committed instruction PC trace from C or ELF file using interpreter.")
    parser.add_argument("input_file", type=str, help="Path to .c or .elf file")
    parser.add_argument("-o", "--output", type=str, help="Output file for trace (default: stdout)")
    
    args = parser.parse_args()
    
    input_path = Path(args.input_file).resolve()
    
    temp_elf = None
    elf_path = None
    
    if input_path.suffix == ".c":
        # Compile it
        temp_elf = input_path.with_suffix(".temp.elf")
        print(f"Compiling {input_path} to {temp_elf}...", file=sys.stderr)
        elf_path = compile_c(input_path, temp_elf)
    elif input_path.suffix == ".elf":
        elf_path = input_path
    else:
        print("Error: Input file must be .c or .elf", file=sys.stderr)
        sys.exit(1)
        
    print(f"Running simulation on {elf_path}...", file=sys.stderr)
    output_raw = run_simulation(elf_path)
    
    pcs = parse_trace(output_raw)
    
    if not pcs:
        print("Warning: No PCs found in trace output. Raw output follows:", file=sys.stderr)
        print(output_raw, file=sys.stderr)
    
    if args.output:
        with open(args.output, "w") as f:
            for pc in pcs:
                f.write(pc + "\n")
        print(f"Trace written to {args.output}", file=sys.stderr)
    else:
        for pc in pcs:
            print(pc)
            
    # Cleanup temp file
    if temp_elf and temp_elf.exists():
        temp_elf.unlink()

if __name__ == "__main__":
    main()
