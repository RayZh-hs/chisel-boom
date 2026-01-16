#!/usr/bin/env python3
import subprocess
import re
import sys

# Default test cases to run
TEST_CASES = {
    "e2e.E2ETests": [
        "fibonacci",
        "matmul_8x8",
    ],
    "e2e.E2ESimTests": [
        "superloop",
        "hanoi",
        "bulgarian",
        "queens",
    ],
}

def run_batched_tests(test_source, test_names):
    """
    Runs a batch of tests and monitors output live to identify progress and extract rates.
    """
    # Build the batched command
    cmd = ["mill", "-Dreport=true", "test.testOnly", test_source, "--"]
    for name in test_names:
        cmd.extend(["-z", name])
    
    tests_run = []
    results_obtained = []
    
    # State tracking for the parser
    current_rate = None

    try:
        # Start the process
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )

        # Regex patterns
        rate_re = re.compile(r"Misprediction Rate:\s+([0-9.]+)%")
        # Matches "- Sim test: name" or "- C test: name"
        task_re = re.compile(r"- (Sim|C) test: (\S+)")

        for line in iter(process.stdout.readline, ""):
            # 1. Look for the rate (usually appears before the test name in logs)
            rate_match = rate_re.search(line)
            if rate_match:
                current_rate = float(rate_match.group(1))
                results_obtained.append(current_rate)

            # 2. Look for the completion of a specific test
            task_match = task_re.search(line)
            if task_match:
                test_type = task_match.group(1) # Sim or C
                test_name = task_match.group(2)
                # Strip anything after \x1b
                if '\x1b' in test_name:
                    test_name = test_name.split('\x1b')[0]
                tests_run.append(test_name)
                
                status_str = f"DONE (Rate: {current_rate}%)" if current_rate is not None else "DONE (No rate found)"
                print(f"Running {test_type} test: {test_name}... {status_str}")
                
                # Reset rate for the next test in the stream
                current_rate = None

        process.wait()
        if process.returncode != 0:
            print(f"Error: Batch {test_source} exited with code {process.returncode}")

    except Exception as e:
        print(f"\nAn unexpected error occurred: {e}")

    return dict(zip(tests_run, results_obtained))

def main():
    print(f"Starting performance analysis for {sum(len(v) for v in TEST_CASES.values())} tests...")
    print("=" * 60)
    
    all_results = []
    
    for test_source, test_names in TEST_CASES.items():
        print(f"\n>>> Batch: {test_source}")
        results = run_batched_tests(test_source, test_names)
        
        # Preserve order from TEST_CASES for the final table
        for name in test_names:
            all_results.append((name, results.get(name)))
            
    print("\n" + "=" * 60)
    print(f"{'Test Case':<30} | {'Misprediction Rate':<20}")
    print("-" * 60)
    
    valid_rates = []
    
    for test, rate in all_results:
        display_rate = f"{rate:.2f}%" if rate is not None else "N/A"
        print(f"{test:<30} | {display_rate:>18}")
        if rate is not None:
            valid_rates.append(rate)

    print("-" * 60)
    
    if valid_rates:
        avg_rate = sum(valid_rates) / len(valid_rates)
        print(f"{'AVERAGE':<30} | {avg_rate:>18.2f}%")
    else:
        print("No valid results collected.")
    print("=" * 60)

if __name__ == "__main__":
    main()