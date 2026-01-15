#include <vector>
#include <string>
#include <fstream>
#include <iostream>
#include <iomanip>
#include <cstring>
#include <svdpi.h>

// 64MB storage
static std::vector<uint8_t> mem_storage(64 * 1024 * 1024, 0); 
static std::ofstream debug_log; 
static const int DELAY = 20;

// SV Logic is 128 bit data. 
// svBitVecVal is usually uint32_t.
// 128 bits = 4 * 32 bits.

struct Response {
    int id;
    uint32_t data[4]; // 128 bit (4 32-bit words)
    int countdown;
};

static std::vector<Response> resp_queue;

extern "C" void dram_init(const char* filename) {
    debug_log.open("/home/rogerw/project/chisel-boom/dram.log", std::ios::out | std::ios::trunc);
    if (!debug_log.is_open()) {
        std::cerr << "[DPI-C] Fail to open dram.log" << std::endl;
    }

    if (!filename) {
        if(debug_log.is_open()) debug_log << "[DPI-C] dram_init called with NULL filename" << std::endl;
        return;
    }
    if(debug_log.is_open()) debug_log << "[DPI-C] Loading memory from: " << filename << std::endl;
    std::ifstream file(filename);
    if (!file.is_open()) {
        if(debug_log.is_open()) debug_log << "[DPI-C] Error: Could not open hex file: " << filename << std::endl;
        return;
    }
    
    std::string line;
    uint32_t addr = 0;
    int loaded = 0;
    int lines_read = 0;
    
    // Reset memory
    std::fill(mem_storage.begin(), mem_storage.end(), 0);

    while (std::getline(file, line)) {
        lines_read++;
        // Strip comments
        size_t comment = line.find("//");
        if (comment != std::string::npos) line = line.substr(0, comment);
        
        while (!line.empty() && isspace(line.back())) line.pop_back();
        while (!line.empty() && isspace(line.front())) line.erase(0, 1);
        
        if (line.empty()) continue;
        
        if (line[0] == '@') {
             continue; 
        }

        try {
            uint32_t val = (uint32_t)std::stoul(line, nullptr, 16);
            
            if (addr + 4 <= mem_storage.size()) {
                mem_storage[addr+0] = val & 0xFF;
                mem_storage[addr+1] = (val >> 8) & 0xFF;
                mem_storage[addr+2] = (val >> 16) & 0xFF;
                mem_storage[addr+3] = (val >> 24) & 0xFF;
                addr += 4;
                loaded += 4;
            }
        } catch (...) {
        }
    }
    if(debug_log.is_open()) {
        debug_log << "[DPI-C] Initialized RAM from " << filename << " (" << loaded << " bytes loaded)" << std::endl;
        debug_log << "[DPI-C] Memory Head (0x00): ";
        debug_log << std::hex << std::setfill('0');
        for(int i=0; i<16; i++) debug_log << std::setw(2) << (int)mem_storage[i] << " ";
        debug_log << std::dec << std::endl;
    }
}

extern "C" void dram_tick(
    unsigned char req_valid,    // 1 bit
    int req_id,                 // 32 bit (int)
    long long req_addr,         // 64 bit (long long)
    unsigned char req_isWr,     // 1 bit
    const svBitVecVal* req_data,// 128 bit vector -> 4x uint32
    int req_mask,               // 32 bit (int) - really 16 bits used
    unsigned char resp_ready,   // 1 bit
    
    unsigned char* req_ready,   // output 1 bit
    unsigned char* resp_valid,  // output 1 bit
    int* resp_id,               // output 32 bit
    svBitVecVal* resp_data      // output 128 bit -> 4x uint32
) {
    const int MAX_QUEUE = 16; 
    const uint32_t DRAM_BASE = 0x00000000; // User requested 0 base

    // 1. Process Queue & Drive Output
    *resp_valid = 0;
    *resp_id = 0;
    resp_data[0] = 0; resp_data[1] = 0; resp_data[2] = 0; resp_data[3] = 0;

    // Tick down delays
    for (auto& r : resp_queue) {
        if (r.countdown > 0) r.countdown--;
    }

    if (!resp_queue.empty()) {
        const auto& head = resp_queue.front();
        if (head.countdown == 0 && resp_ready) {
            *resp_valid = 1;
            *resp_id = head.id;
            resp_data[0] = head.data[0];
            resp_data[1] = head.data[1];
            resp_data[2] = head.data[2];
            resp_data[3] = head.data[3];
            
            if(debug_log.is_open()) {
                 debug_log << "[DPI-C] RESP ID: " << head.id << " Data[0]: 0x" << std::hex << head.data[0] << std::dec << std::endl;
            }

            resp_queue.erase(resp_queue.begin());
        }
    }

    // 2. Accept New Request
    bool can_accept = (resp_queue.size() < MAX_QUEUE);
    *req_ready = can_accept ? 1 : 0;

    if (req_valid && can_accept) {
        uint32_t raw_addr = (uint32_t)req_addr;
        uint32_t offset = raw_addr; // default
        bool in_range = false;

        // Check bounds
        if (raw_addr >= DRAM_BASE && raw_addr < DRAM_BASE + mem_storage.size()) {
            offset = raw_addr - DRAM_BASE;
            in_range = true;
        }

        if (!in_range) {
             if(debug_log.is_open()) debug_log << "[DPI-C] WARNING: Access Out of Bounds: 0x" << std::hex << raw_addr << std::dec << std::endl;
        }

        // Prepare response
        Response resp;
        resp.id = req_id;
        resp.countdown = DELAY;
        memset(resp.data, 0, sizeof(resp.data)); // Clear data

        if (req_isWr) {
            if (in_range) {
                 // Write 128 bits (16 bytes) with mask
                 for (int i=0; i<16; i++) {
                     if ((req_mask >> i) & 1) {
                         // Extract byte from req_data
                         // req_data is array of uint32.
                         int word_idx = i / 4;
                         int byte_shift = (i % 4) * 8;
                         uint8_t b = (req_data[word_idx] >> byte_shift) & 0xFF;
                         
                         if (offset + i < mem_storage.size()) {
                             mem_storage[offset + i] = b;
                         }
                     }
                 }
            }
            resp_queue.push_back(resp); // Write ack
        } else {
            // Read
            if (in_range && (offset + 16 <= mem_storage.size())) {
                // Populate resp.data (128 bits)
                for (int i=0; i<16; i++) {
                    uint8_t b = mem_storage[offset + i];
                    int word_idx = i / 4;
                    int byte_shift = (i % 4) * 8;
                    resp.data[word_idx] |= ((uint32_t)b) << byte_shift;
                }
                
                // Debug print for reads
                if(debug_log.is_open()) {
                    debug_log << "[DPI-C] READ Addr: 0x" << std::hex << std::setw(8) << std::setfill('0') << raw_addr 
                              << " ID: " << req_id
                              << " -> Data: " << std::setw(8) << resp.data[0] << " " << std::setw(8) << resp.data[1] 
                              << " ..." << std::dec << std::endl;
                }
            }
            resp_queue.push_back(resp);
        }
    }
}
