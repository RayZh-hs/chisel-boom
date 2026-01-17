
module DPIDRAM #(
    parameter FILENAME = "test.hex",
    parameter MEM_SIZE = 16777216 // 16MB
)(
    input  logic         clock,
    input  logic         reset,
    
    output logic         req_ready,
    input  logic         req_valid,
    input  logic [3:0]   req_bits_id,
    input  logic [31:0]  req_bits_addr,
    input  logic [127:0] req_bits_data,
    input  logic         req_bits_isWr,
    input  logic [15:0]  req_bits_mask,

    input  logic         resp_ready,
    output logic         resp_valid,
    output logic [3:0]   resp_bits_id,
    output logic [127:0] resp_bits_data
);

    import "DPI-C" context function void dram_init(input string hex_file);
    import "DPI-C" context function void dram_tick(
        input  logic        req_valid,
        input  int          req_id,
        input  longint      req_addr,
        input  logic        req_isWr,
        input  bit [127:0]  req_data,
        input  int          req_mask,
        input  logic        resp_ready,
        
        output logic        req_ready,
        output logic        resp_valid,
        output int          resp_id,
        output bit [127:0]  resp_data
    );

    initial begin
        dram_init(FILENAME);
    end

    logic        dpi_req_ready;
    logic        dpi_resp_valid;
    int          dpi_resp_id;
    logic [127:0] dpi_resp_data;

    // Intermediate signals for DPI outputs to avoid multi-driver issues?
    // The previous error was blocking/non-blocking mix.
    // DPI function drives the reference arguments immediately. 
    // We should treat them as variables updated in the always block.
    
    // We'll use simple variables for DPI output
    logic        next_req_ready;
    logic        next_resp_valid;
    int          next_resp_id;
    bit [127:0]  next_resp_data;

    assign req_ready      = dpi_req_ready;
    assign resp_valid     = dpi_resp_valid;
    assign resp_bits_id   = dpi_resp_id[3:0];
    assign resp_bits_data = dpi_resp_data;

    always @(posedge clock) begin
        if (reset) begin
            dpi_req_ready <= 0;
            dpi_resp_valid <= 0;
            dpi_resp_id <= 0;
            dpi_resp_data <= 0;
        end else begin
            dram_tick(
                req_valid,
                {28'b0, req_bits_id},
                {32'b0, req_bits_addr},
                req_bits_isWr,
                req_bits_data,
                {16'b0, req_bits_mask},
                resp_ready,
                
                next_req_ready,
                next_resp_valid,
                next_resp_id,
                next_resp_data
            );
            
            dpi_req_ready      <= next_req_ready;
            dpi_resp_valid     <= next_resp_valid;
            dpi_resp_id        <= next_resp_id;
            dpi_resp_data      <= next_resp_data;
        end
    end

endmodule
