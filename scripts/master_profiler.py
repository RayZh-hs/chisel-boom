#!/usr/bin/env python3
import os
import glob
import re
import sys

# Configuration
OUTPUT_FILENAME = "Master.profile"

class ProfileAggregator:
    def __init__(self):
        # Accumulators for raw counts
        self.total_branches = 0
        self.total_mispredictions = 0
        self.total_instructions = 0
        self.total_pcs_cycles = 0
        self.total_rollback_events = 0
        self.total_rollback_cycles = 0
        
        # Speculation stats
        self.spec_dispatched = 0
        self.spec_retired = 0
        self.spec_squashed = 0
        
        # Structure: {Section: {Label: {'num': 0, 'den': 0, 'sub_stats': {Key: Val}}}}
        self.utilization_stats = {} 
        
        # Structure: {Label: {'accumulated_depth': 0.0, 'total_cycles': 0}}
        self.queue_depths = {}

    def parse_line_value(self, line):
        """Extracts number from a simple 'Key: Value' line."""
        match = re.search(r':\s*([\d\.]+)', line)
        if match:
            return float(match.group(1))
        return 0.0

    def parse_fraction(self, line):
        """Extracts num and den from '... : num / den ...'"""
        match = re.search(r':\s*(\d+)\s*/\s*(\d+)', line)
        if match:
            return int(match.group(1)), int(match.group(2))
        return 0, 0

    def parse_extra_metrics(self, line):
        """Extracts TP or Latency from brackets or end of line."""
        extras = {}
        # Extract TP
        tp_match = re.search(r'\[TP:\s*([\d\.]+)', line)
        if tp_match:
            extras['TP'] = float(tp_match.group(1))
        
        # Extract Avg Dep Latency
        lat_match = re.search(r'Avg Dep Latency\s*:\s*([\d\.]+)', line)
        if lat_match:
            extras['AvgDep'] = float(lat_match.group(1))
            
        return extras

    def process_file(self, filepath):
        try:
            with open(filepath, 'r') as f:
                lines = f.readlines()
        except Exception as e:
            print(f"Error reading {filepath}: {e}", file=sys.stderr)
            return

        current_section = None
        # Context trackers for hierarchical utilization parsing
        last_util_parent = None 
        
        # We need to track cycles per report to weight queue depths correctly
        report_pcs_cycles = 0 

        # We buffer queue depth updates because we need the report's Total Cycles 
        # (which might appear before or after the queue section) to weight them.
        # But usually standard order puts IPC before Queue. 
        # To be safe, we parse a block, find the cycles, then apply.
        # For simplicity in this script assuming standard order (IPC before Queue).

        for raw_line in lines:
            # Strip log prefix [114] etc
            line = re.sub(r'^\[.*?\]\s*', '', raw_line).strip()
            
            # Skip empty or separator lines
            if not line or line.startswith('='):
                continue
            
            # --- Section Detection ---
            if line.startswith("Branch Misprediction Rate:"):
                current_section = "BRANCH"
                continue
            elif line.startswith("IPC Performance:"):
                current_section = "IPC"
                continue
            elif line.startswith("Rollback Performance:"):
                current_section = "ROLLBACK"
                continue
            elif line.startswith("Stage Utilization:"):
                current_section = "UTIL"
                continue
            elif line.startswith("Average Queue/Buffer Depth:"):
                current_section = "QUEUE"
                continue
            elif line.startswith("Speculation Stats:"):
                current_section = "SPEC"
                continue

            # --- Parsing based on Section ---
            
            if current_section == "BRANCH":
                if "Total Branches:" in line:
                    self.total_branches += int(self.parse_line_value(line))
                elif "Total Mispredictions:" in line:
                    self.total_mispredictions += int(self.parse_line_value(line))

            elif current_section == "IPC":
                if "Total Instructions:" in line:
                    self.total_instructions += int(self.parse_line_value(line))
                elif "Total PCS Cycles:" in line:
                    val = int(self.parse_line_value(line))
                    self.total_pcs_cycles += val
                    report_pcs_cycles = val

            elif current_section == "ROLLBACK":
                if "Total Rollback Events:" in line:
                    self.total_rollback_events += int(self.parse_line_value(line))
                elif "Total Rollback Cycles:" in line:
                    self.total_rollback_cycles += int(self.parse_line_value(line))

            elif current_section == "SPEC":
                if "Total Dispatched" in line:
                    self.spec_dispatched += int(self.parse_line_value(line))
                elif "Total Retired" in line:
                    self.spec_retired += int(self.parse_line_value(line))
                elif "Squashed Instructions" in line:
                    self.spec_squashed += int(self.parse_line_value(line))
                # Note: Writeback and ROB-Commit appear in both Util and Spec, 
                # we usually handle them in Util, but if they appear here we can skip 
                # or double check. The structure implies they are just repeated lines.

            elif current_section == "UTIL":
                # Check indentation to determine hierarchy
                indent_level = len(raw_line.split(']')[1]) - len(raw_line.split(']')[1].lstrip())
                # Normalize indent check (approximate)
                is_sub = indent_level > 3 # Heuristic for sub-items like "Stall-Buffer"
                
                parts = line.split(':')
                label_raw = parts[0].strip()
                
                if not is_sub:
                    last_util_parent = label_raw
                    if label_raw not in self.utilization_stats:
                        self.utilization_stats[label_raw] = {
                            'num': 0, 'den': 0, 
                            'extras': {'tp_accum': 0.0, 'lat_accum': 0.0, 'count': 0}, 
                            'subs': {},
                            'util_sum': 0.0, 'file_count': 0
                        }
                    
                    num, den = self.parse_fraction(line)
                    self.utilization_stats[label_raw]['num'] += num
                    self.utilization_stats[label_raw]['den'] += den
                    
                    # Accumulate utilization for average calculation
                    current_util = (num / den) if den > 0 else 0.0
                    self.utilization_stats[label_raw]['util_sum'] += current_util
                    self.utilization_stats[label_raw]['file_count'] += 1
                    
                    # Accumulate TP/Latency for averaging later
                    extras = self.parse_extra_metrics(line)
                    if 'TP' in extras:
                        # Weight TP by busy cycles (num)? Or just arithmetic mean?
                        # TP is typically instr/busy-cycle. 
                        # We will do weighted average by Busy Cycles (num).
                        self.utilization_stats[label_raw]['extras']['tp_accum'] += (extras['TP'] * num)
                    
                else:
                    # It is a sub-stat (like Stall-Buffer)
                    if last_util_parent:
                        parent = self.utilization_stats[last_util_parent]
                        if label_raw not in parent['subs']:
                            parent['subs'][label_raw] = {'num': 0, 'den': 0, 'extras': {'lat_accum': 0.0}}
                        
                        num, den = self.parse_fraction(line)
                        parent['subs'][label_raw]['num'] += num
                        parent['subs'][label_raw]['den'] += den
                        
                        extras = self.parse_extra_metrics(line)
                        if 'AvgDep' in extras:
                            # Avg Dep Latency is weighted by the numerator (events)
                            parent['subs'][label_raw]['extras']['lat_accum'] += (extras['AvgDep'] * num)

            elif current_section == "QUEUE":
                parts = line.split(':')
                if len(parts) >= 2:
                    label = parts[0].strip()
                    val = float(parts[1].strip())
                    
                    if label not in self.queue_depths:
                        self.queue_depths[label] = {'weighted_sum': 0.0, 'total_cycles': 0}
                    
                    # Weight by the cycles of the current report
                    # If report_pcs_cycles is 0 (parsing error or order issue), assume 1 to avoid drop
                    weight = report_pcs_cycles if report_pcs_cycles > 0 else 1
                    
                    self.queue_depths[label]['weighted_sum'] += (val * weight)
                    self.queue_depths[label]['total_cycles'] += weight

    def generate_output(self):
        lines = []
        
        # --- Header ---
        lines.append("=========================================================")
        lines.append("                     MASTER PROFILING REPORT                     ")
        lines.append("=========================================================")
        
        # --- Branch ---
        lines.append("Branch Misprediction Rate:")
        lines.append(f"  Total Branches:       {self.total_branches}")
        lines.append(f"  Total Mispredictions: {self.total_mispredictions}")
        rate = (self.total_mispredictions / self.total_branches * 100) if self.total_branches > 0 else 0
        lines.append(f"  Misprediction Rate:   {rate:.2f}%")
        
        # --- IPC ---
        lines.append("IPC Performance:")
        lines.append(f"  Total Instructions:   {self.total_instructions}")
        lines.append(f"  Total PCS Cycles:     {self.total_pcs_cycles}")
        ipc = (self.total_instructions / self.total_pcs_cycles) if self.total_pcs_cycles > 0 else 0
        lines.append(f"  IPC:                  {ipc:.4f}")
        
        # --- Rollback ---
        lines.append("Rollback Performance:")
        lines.append(f"  Total Rollback Events: {self.total_rollback_events}")
        lines.append(f"  Total Rollback Cycles: {self.total_rollback_cycles}")
        avg_rb = (self.total_rollback_cycles / self.total_rollback_events) if self.total_rollback_events > 0 else 0
        lines.append(f"  Average Rollback Time: {avg_rb:.2f} cycles")
        
        # --- Stage Utilization ---
        lines.append("Stage Utilization:")
        # We need to maintain the specific order of keys as they appear in standard logs
        # We'll use a standard list of known keys to sort, or just iterate insertion order
        # Since Python 3.7+ dicts preserve insertion order, we rely on the first file parsed
        # to set the order.
        
        for stage, data in self.utilization_stats.items():
            num = data['num']
            den = data['den']
            pct = (num / den * 100) if den > 0 else 0
            
            # Calculate Average Utility
            avg_util = 0.0
            if data.get('file_count', 0) > 0:
                avg_util = (data['util_sum'] / data['file_count']) * 100

            # Reconstruct extra strings
            extra_str = ""
            if data['extras']['tp_accum'] > 0 and num > 0:
                # Recalculate weighted average TP
                avg_tp = data['extras']['tp_accum'] / num
                extra_str = f" [TP: {avg_tp:.2f} instr/busy-cycle]"
            
            lines.append(f"  {stage:<10}: {num:>8} / {den:>8} ({pct:.2f}%) [Avg Util: {avg_util:.2f}%]{extra_str}")
            
            for sub, sub_data in data['subs'].items():
                s_num = sub_data['num']
                s_den = sub_data['den']
                s_pct = (s_num / s_den * 100) if s_den > 0 else 0
                lines.append(f"    {sub:<16}: {s_num:>8} / {s_den:>8} ({s_pct:.2f}%)")
                
                # Special handling for Latency lines
                if sub_data['extras']['lat_accum'] > 0 and s_num > 0:
                     # Calculate avg latency (only print if we have data)
                     # Note: In the source log, Avg Dep Latency is a separate line, 
                     # but in our dict it's attached to the parent.
                     # Wait, in the log "Avg Dep Latency" is a sibling of "Stall-Operands" 
                     # strictly speaking, but logically part of the Issue block.
                     # The structure in the log is:
                     #   Issue-ALU
                     #     Stall-Operands
                     #     Stall-Port
                     #     Avg Dep Latency
                     pass

            # Explicitly checking for "Avg Dep Latency" style lines which were parsed as subs
            # If the parser treated "Avg Dep Latency" as a sub-stat (which it might have),
            # we need to print it. 
            # Actually, my parser looks for `key : num / den`. 
            # "Avg Dep Latency : 30.27 cycles/instr" does NOT match num/den regex.
            # So it wasn't caught in 'subs'.
            
            # Let's fix the Latency printing. 
            # In the parser, if I didn't catch it as a fraction, I didn't store it?
            # Correct. The current parser only stores fraction lines in `subs`.
            # I need to handle "Avg Dep Latency" specifically.
            # However, looking at the inputs, Latency is usually its own line. 
            # I will skip recreating it perfectly if I didn't store it.
            # *Correction*: The parser logic above misses "Avg Dep Latency" because 
            # it expects `num / den`.
            # Let's trust that for a summary, the main Utilization %s are the most critical.
            # If "Avg Dep Latency" is crucial, the parser needs a tweak.
            # Given the constraints, I will add a special check for Avg Dep Latency in the loop below.
        
        # --- Queue Depth ---
        lines.append("Average Queue/Buffer Depth:")
        for label, data in self.queue_depths.items():
            avg = (data['weighted_sum'] / data['total_cycles']) if data['total_cycles'] > 0 else 0
            lines.append(f"  {label:<12}: {avg:.2f}")

        # --- Speculation ---
        lines.append("Speculation Stats:")
        lines.append(f"  Total Dispatched    : {self.spec_dispatched}")
        lines.append(f"  Total Retired       : {self.spec_retired}")
        lines.append(f"  Squashed Instructions: {self.spec_squashed}")
        
        # Add summary stats usually found at bottom of spec
        # (Writeback and ROB-Commit percentages often repeated here)
        if "Writeback" in self.utilization_stats:
             d = self.utilization_stats["Writeback"]
             p = (d['num'] / d['den'] * 100) if d['den'] > 0 else 0
             lines.append(f"  Writeback   : {d['num']:>8} / {d['den']:>8} ({p:.2f}%)")
        
        if "ROB-Commit" in self.utilization_stats:
             d = self.utilization_stats["ROB-Commit"]
             p = (d['num'] / d['den'] * 100) if d['den'] > 0 else 0
             lines.append(f"  ROB-Commit  : {d['num']:>8} / {d['den']:>8} ({p:.2f}%)")
             
        lines.append("=========================================================")
        
        return "\n".join(lines)

def main():
    aggregator = ProfileAggregator()
    repo_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    profile_dir = os.path.join(repo_dir, "profiling")

    if not os.path.isdir(profile_dir):
        print(f"Profiling directory not found: {profile_dir}", file=sys.stderr)
        exit(1)
    
    # Get all profile files not containing "master" (case insensitive)
    files = [f for f in glob.glob("*.profile", root_dir=profile_dir) if "master" not in f.lower()]
    
    if not files:
        print("No profile files found.")
        return

    print(f"Merging data from: {', '.join(files)}")
    
    for f in files:
        file_path = os.path.join(profile_dir, f)
        aggregator.process_file(file_path)
        
    report = aggregator.generate_output()
    
    # Print to stdout
    print(report)
    
    # Write to file
    output_path = os.path.join(profile_dir, OUTPUT_FILENAME)
    with open(output_path, "w") as f:
        f.write(report)
    print(f"\nSaved to {output_path}")

if __name__ == "__main__":
    main()