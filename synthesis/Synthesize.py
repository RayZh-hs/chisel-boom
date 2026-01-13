import siliconcompiler
import glob
import os
from siliconcompiler.targets import asap7_demo

is_mem_stub_content = """\
// Stub for Instruction Memory
// Preserves 1-cycle latency and data dependency to prevent logic removal
module imem_4096x32(
  input  [11:0] R0_addr,
  input         R0_en,
                R0_clk,
  output [31:0] R0_data
);
  reg [11:0] r_addr;
  always @(posedge R0_clk) r_addr <= R0_addr;
  assign R0_data = {20'b0, r_addr}; 
endmodule

// Stub for Data Memory
module mem_4096x32(
  input  [11:0] RW0_addr,
  input         RW0_en,
                RW0_clk,
                RW0_wmode,
  input  [31:0] RW0_wdata,
  output [31:0] RW0_rdata,
  input  [3:0]  RW0_wmask
);
  reg [11:0] r_addr;
  always @(posedge RW0_clk) r_addr <= RW0_addr;
  assign RW0_rdata = {20'b0, r_addr};
endmodule
"""

def build_boom():
    chip = siliconcompiler.Chip('BoomCore')

    # Add source files
    script_dir = os.path.dirname(os.path.abspath(__file__))
    generated_dir = os.path.join(script_dir, 'generated')
    sv_files = glob.glob(os.path.join(generated_dir, '*.sv'))

    if not sv_files:
        print(f"Error: No .sv files found in {generated_dir}")
        return

    # Filter out memory files that result in synthesis optimization due to X-propagation/Constant-folding
    # and replace them with stubs that preserve connectivity.
    # Exclude the large generated memories
    sv_files = [f for f in sv_files if 'imem_4096x32.sv' not in f and 'mem_4096x32.sv' not in f]

    # Create stubs.sv
    stub_file = os.path.join(generated_dir, 'stubs.sv')
    with open(stub_file, 'w') as f:
        f.write(is_mem_stub_content)

    chip.input(stub_file)
    chip.input(sv_files)
    chip.use(asap7_demo)
    chip.clock('clock', period=2000)

    # Important: Define 'SYNTHESIS' so Chisel/sv2v strips out $finish/$fatal/printf
    chip.add('option', 'define', 'SYNTHESIS')

    chip.set('option', 'to', 'syn')
    chip.run()
    chip.summary()

if __name__ == "__main__":
    build_boom()