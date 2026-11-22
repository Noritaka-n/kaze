#include "kz_protocol.h"

#include <cstring>

namespace kaze {
namespace {

bool ReadExact(HANDLE pipe, char* buffer, DWORD length, std::string& error) {
    DWORD offset = 0;
    while (offset < length) {
        DWORD read = 0;
        if (!ReadFile(pipe, buffer + offset, length - offset, &read, nullptr)) {
            error = "ReadFile failed: " + std::to_string(GetLastError());
            return false;
        }
        if (read == 0) {
            error = "pipe closed";
            return false;
        }
        offset += read;
    }
    return true;
}

bool WriteAll(HANDLE pipe, const char* buffer, DWORD length, std::string& error) {
    DWORD offset = 0;
    while (offset < length) {
        DWORD written = 0;
        if (!WriteFile(pipe, buffer + offset, length - offset, &written, nullptr)) {
            error = "WriteFile failed: " + std::to_string(GetLastError());
            return false;
        }
        if (written == 0) {
            error = "WriteFile wrote 0 bytes";
            return false;
        }
        offset += written;
    }
    return true;
}

void PutU16(char* p, uint16_t v) {
    p[0] = static_cast<char>(v & 0xFF);
    p[1] = static_cast<char>((v >> 8) & 0xFF);
}

void PutU32(char* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<char>((v >> (8 * i)) & 0xFF);
}

void PutU64(char* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<char>((v >> (8 * i)) & 0xFF);
}

uint16_t GetU16(const char* p) {
    return static_cast<uint16_t>(static_cast<uint8_t>(p[0]) | (static_cast<uint8_t>(p[1]) << 8));
}

uint32_t GetU32(const char* p) {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(static_cast<uint8_t>(p[i])) << (8 * i);
    return v;
}

uint64_t GetU64(const char* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(static_cast<uint8_t>(p[i])) << (8 * i);
    return v;
}

}  

bool ReadFrame(HANDLE pipe, Frame& out, std::string& error) {
    char header[kHeaderSize];
    if (!ReadExact(pipe, header, kHeaderSize, error)) return false;

    const uint32_t magic = GetU32(header);
    if (magic != kMagic) {
        error = "bad magic";
        return false;
    }

    out.version = static_cast<uint8_t>(header[4]);
    out.type = static_cast<uint8_t>(header[5]);
    out.flags = GetU16(header + 6);
    const uint32_t length = GetU32(header + 8);
    out.msg_id = GetU64(header + 12);

    if (out.version != kVersion) {
        error = "unsupported version";
        return false;
    }
    if (length > kMaxPayload) {
        error = "payload too large";
        return false;
    }

    out.payload.assign(length, '\0');
    if (length > 0 && !ReadExact(pipe, out.payload.data(), length, error)) return false;
    return true;
}

bool WriteFrame(HANDLE pipe, const Frame& frame, std::string& error) {
    if (frame.payload.size() > kMaxPayload) {
        error = "payload too large";
        return false;
    }

    char header[kHeaderSize];
    PutU32(header, kMagic);
    header[4] = static_cast<char>(frame.version);
    header[5] = static_cast<char>(frame.type);
    PutU16(header + 6, frame.flags);
    PutU32(header + 8, static_cast<uint32_t>(frame.payload.size()));
    PutU64(header + 12, frame.msg_id);

    if (!WriteAll(pipe, header, kHeaderSize, error)) return false;
    if (!frame.payload.empty() &&
        !WriteAll(pipe, frame.payload.data(), static_cast<DWORD>(frame.payload.size()), error)) {
        return false;
    }
    return true;
}

int PeekAvailable(HANDLE pipe) {
    DWORD available = 0;
    if (!PeekNamedPipe(pipe, nullptr, 0, nullptr, &available, nullptr)) return 0;
    return static_cast<int>(available);
}

}  
