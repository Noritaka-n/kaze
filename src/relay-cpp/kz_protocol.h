
#pragma once

#include <windows.h>

#include <cstdint>
#include <string>

namespace kaze {

constexpr uint32_t kMagic = 0x00315A4B;  
constexpr uint8_t kVersion = 1;
constexpr int kHeaderSize = 20;
constexpr uint32_t kMaxPayload = 1u << 20;

struct Frame {
    uint8_t version = kVersion;
    uint8_t type = 0;
    uint16_t flags = 0;
    uint64_t msg_id = 0;
    std::string payload;
};


enum MsgType : uint8_t {
    kHello = 1,
    kWelcome = 2,
    kAck = 3,
    kError = 4,
    kGame = 5,
    kAttach = 6,
    kAttachResult = 7,
    kTextBatch = 8,
    kTextPush = 9,
    kControl = 10,
    kPing = 11,
    kPong = 12,
};

bool ReadFrame(HANDLE pipe, Frame& out, std::string& error);
bool WriteFrame(HANDLE pipe, const Frame& frame, std::string& error);
int PeekAvailable(HANDLE pipe);

}  
