#!/usr/bin/env python3
import argparse
import sys
import re

def parse_log(lines):
    pcs = []
    # Regex to capture PC from ROB Commit log lines
    # Matches: ... ROB: Commit ... pc=0x[hex] ...
    pattern = re.compile(r"ROB: Commit .* pc=(0x[0-9a-fA-F]+)")
    
    for line in lines:
        match = pattern.search(line)
        if match:
            pc_str = match.group(1)
            # Normalize to 0xXXXXXXXX
            pc_int = int(pc_str, 16)
            pcs.append(f"0x{pc_int:08x}")
    return pcs

def main():
    parser = argparse.ArgumentParser(description="Extract committed instruction PC trace from BOOM log.")
    parser.add_argument("log_file", type=str, nargs="?", help="Path to log file (default: stdin)")
    parser.add_argument("-o", "--output", type=str, help="Output file for trace (default: stdout)")
    
    args = parser.parse_args()
    
    lines = []
    if args.log_file:
        with open(args.log_file, "r") as f:
            lines = f.readlines()
    else:
        lines = sys.stdin.readlines()
        
    pcs = parse_log(lines)
    
    if args.output:
        with open(args.output, "w") as f:
            for pc in pcs:
                f.write(pc + "\n")
        # print(f"Extracted {len(pcs)} PCs to {args.output}", file=sys.stderr)
    else:
        for pc in pcs:
            print(pc)

if __name__ == "__main__":
    main()
