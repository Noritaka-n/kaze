




#include <windows.h>
#include <bcrypt.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "kaze.pb.h"
#include "kz_protocol.h"

#ifdef _MSC_VER
#pragma comment(lib, "bcrypt.lib")
#endif

namespace {

using kaze::Frame;
using kaze::MsgType;
using kaze::PeekAvailable;
using kaze::ReadFrame;
using kaze::WriteFrame;

constexpr const char* kVersion = "1.0.2";



std::string RandomHex(int bytes) {
    std::vector<unsigned char> buffer(static_cast<size_t>(bytes));
    if (BCryptGenRandom(nullptr, buffer.data(), static_cast<ULONG>(buffer.size()),
                        BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
        for (auto& b : buffer) b = static_cast<unsigned char>(rand() & 0xFF);
    }
    static const char* hex = "0123456789abcdef";
    std::string out;
    out.reserve(buffer.size() * 2);
    for (unsigned char b : buffer) {
        out.push_back(hex[b >> 4]);
        out.push_back(hex[b & 0xF]);
    }
    return out;
}

bool FixedTimeEquals(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    unsigned char diff = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        diff |= static_cast<unsigned char>(a[i] ^ b[i]);
    }
    return diff == 0;
}

int64_t NowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

std::string LocalAppData() {
    const char* v = std::getenv("LOCALAPPDATA");
    return v != nullptr ? std::string(v) : std::string(".");
}

std::string SessionPath() {
    return LocalAppData() + "\\Kaze\\session\\relay.json";
}

void EnsureDirectory(const std::string& path) {
    CreateDirectoryA(path.c_str(), nullptr);
}

void WriteSession(const std::string& pipe, const std::string& token) {
    EnsureDirectory(LocalAppData() + "\\Kaze");
    EnsureDirectory(LocalAppData() + "\\Kaze\\session");

    char json[1024];
    std::snprintf(json, sizeof(json),
                  "{\n"
                  "  \"pipe\": \"%s\",\n"
                  "  \"token\": \"%s\",\n"
                  "  \"pid\": %lu,\n"
                  "  \"proto\": 1,\n"
                  "  \"startedAt\": %lld\n"
                  "}\n",
                  pipe.c_str(), token.c_str(), GetCurrentProcessId(),
                  static_cast<long long>(NowMillis()));

    FILE* f = nullptr;
    if (fopen_s(&f, SessionPath().c_str(), "wb") == 0 && f != nullptr) {
        fwrite(json, 1, strlen(json), f);
        fclose(f);
    }
}

void DeleteSession() {
    DeleteFileA(SessionPath().c_str());
}

uint8_t TypeOf(const kaze::v1::Envelope& envelope) {
    switch (envelope.body_case()) {
        case kaze::v1::Envelope::kHello: return MsgType::kHello;
        case kaze::v1::Envelope::kWelcome: return MsgType::kWelcome;
        case kaze::v1::Envelope::kAck: return MsgType::kAck;
        case kaze::v1::Envelope::kError: return MsgType::kError;
        case kaze::v1::Envelope::kGame: return MsgType::kGame;
        case kaze::v1::Envelope::kAttach: return MsgType::kAttach;
        case kaze::v1::Envelope::kAttachResult: return MsgType::kAttachResult;
        case kaze::v1::Envelope::kTextBatch: return MsgType::kTextBatch;
        case kaze::v1::Envelope::kTextPush: return MsgType::kTextPush;
        case kaze::v1::Envelope::kControl: return MsgType::kControl;
        case kaze::v1::Envelope::kPing: return MsgType::kPing;
        case kaze::v1::Envelope::kPong: return MsgType::kPong;
        case kaze::v1::Envelope::kInvokeRequest: return MsgType::kInvokeRequest;
        case kaze::v1::Envelope::kInvokeResult: return MsgType::kInvokeResult;
        default: return 0;
    }
}



class FakeAdapter {
public:
    static constexpr const char* kId = "fake";
    static constexpr const char* kEngine = "fake.engine";

    void Start(std::function<void(kaze::v1::TextBatch)> emit) {
        Stop();
        worker_ = std::thread([this, emit = std::move(emit)]() {
            struct Line {
                const char* scene;
                const char* speaker;
                const char* text;
            };
            static const Line kLines[] = {
                {"ch03.cafe", "ミナ", "おはよう、今日もいい天気ですね。"},
                {"ch03.cafe", "ユウ", "この店、ずっと気になってたんだ。"},
                {"ch03.cafe", "ミナ", "……ごめん、少し考えさせて。"},
                {"ch03.street", "ナレーション", "雨が上がり、石畳がゆっくり乾いていく。"},
                {"ch03.street", "ユウ", "明日もここで待ち合わせよう。"},
            };
            constexpr int kLineCount = static_cast<int>(sizeof(kLines) / sizeof(kLines[0]));

            std::this_thread::sleep_for(std::chrono::milliseconds(300));
            int index = 0;
            while (!stop_.load()) {
                kaze::v1::TextBatch batch;
                batch.set_adapter(kId);
                for (int i = 0; i < 3; ++i) {
                    const Line& line = kLines[(index + i) % kLineCount];
                    auto* unit = batch.add_units();
                    unit->set_key(std::string(line.scene) + ":" + std::to_string(index + i));
                    unit->set_source(line.text);
                    unit->set_speaker(line.speaker);
                    unit->set_scene(line.scene);
                    unit->set_context("batch " + std::to_string(index));
                }
                emit(batch);
                ++index;

                for (int slept = 0; slept < 2500 && !stop_.load(); slept += 50) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(50));
                }
            }
        });
    }

    void Stop() {
        stop_.store(true);
        if (worker_.joinable()) worker_.join();
        stop_.store(false);
    }

private:
    std::thread worker_;
    std::atomic<bool> stop_{false};
};



struct Connection {
    HANDLE pipe = INVALID_HANDLE_VALUE;
    std::mutex write_mutex;
    std::atomic<bool> alive{true};
    std::atomic<long long> last_seen_ms{0};
    kaze::v1::Role role = kaze::v1::ROLE_UNSPECIFIED;
    std::string adapter_name;
};

class RelayServer {
public:
    RelayServer(std::string pipe_name, std::string token)
        : pipe_name_(std::move(pipe_name)), token_(std::move(token)) {}

    const std::string& pipe_name() const { return pipe_name_; }
    const std::string& token() const { return token_; }

    void Run(const std::atomic<bool>& stop) {
        while (!stop.load()) {
            const std::wstring path = L"\\\\.\\pipe\\" + ToWide(pipe_name_);
            HANDLE pipe = CreateNamedPipeW(
                path.c_str(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                PIPE_UNLIMITED_INSTANCES,
                64 * 1024,
                64 * 1024,
                0,
                nullptr);

            if (pipe == INVALID_HANDLE_VALUE) {
                std::printf("[relay] CreateNamedPipe failed: %lu\n", GetLastError());
                return;
            }

            const BOOL connected = ConnectNamedPipe(pipe, nullptr);
            if (!connected && GetLastError() != ERROR_PIPE_CONNECTED) {
                CloseHandle(pipe);
                continue;
            }

            auto conn = std::make_shared<Connection>();
            conn->pipe = pipe;
            std::thread([this, conn]() { HandleClient(conn); }).detach();
        }
    }

private:
    static std::wstring ToWide(const std::string& value) {
        if (value.empty()) return {};
        const int size = MultiByteToWideChar(CP_UTF8, 0, value.c_str(), -1, nullptr, 0);
        std::wstring out(static_cast<size_t>(size > 0 ? size - 1 : 0), L'\0');
        if (size > 1) MultiByteToWideChar(CP_UTF8, 0, value.c_str(), -1, out.data(), size);
        return out;
    }

    bool Send(Connection& conn, kaze::v1::Envelope& envelope) {
        if (!conn.alive.load()) return false;

        std::string bytes;
        if (!envelope.SerializeToString(&bytes)) return false;

        Frame frame;
        frame.type = TypeOf(envelope);
        frame.msg_id = envelope.msg_id();
        frame.payload = std::move(bytes);

        std::lock_guard<std::mutex> lock(conn.write_mutex);
        if (!conn.alive.load()) return false;
        std::string error;
        if (!WriteFrame(conn.pipe, frame, error)) {
            conn.alive.store(false);
            return false;
        }
        return true;
    }

    void HandleClient(std::shared_ptr<Connection> conn) {
        std::string error;

        
        Frame frame;
        if (!ReadFrame(conn->pipe, frame, error)) {
            std::printf("[relay] handshake read failed: %s\n", error.c_str());
            Close(conn);
            return;
        }

        kaze::v1::Envelope hello;
        if (!hello.ParseFromArray(frame.payload.data(), static_cast<int>(frame.payload.size())) ||
            hello.body_case() != kaze::v1::Envelope::kHello) {
            Reject(conn, hello.msg_id(), 400, "hello required");
            Close(conn);
            return;
        }

        if (hello.hello().proto() != 1) {
            Reject(conn, hello.msg_id(), 426, "protocol mismatch");
            Close(conn);
            return;
        }
        if (!FixedTimeEquals(hello.hello().token(), token_)) {
            Reject(conn, hello.msg_id(), 401, "invalid token");
            Close(conn);
            return;
        }

        conn->role = hello.hello().role();
        conn->last_seen_ms.store(NowMillis());

        kaze::v1::Envelope welcome;
        welcome.set_msg_id(hello.msg_id());
        auto* body = welcome.mutable_welcome();
        body->set_proto(1);
        body->set_server_time_ms(NowMillis());
        body->set_session(hello.hello().nonce());
        body->set_relay_version(kVersion);
        body->add_caps("text.batch");
        body->add_caps("text.push");
        body->add_caps("adapter.fake");
        if (!Send(*conn, welcome)) {
            Close(conn);
            return;
        }

        if (conn->role == kaze::v1::ROLE_SHELL) {
            bool busy = false;
            {
                std::lock_guard<std::mutex> lock(shell_mutex_);
                if (shell_ && !shell_->alive.load()) shell_.reset();
                busy = shell_ != nullptr;
                if (!busy) shell_ = conn;
            }
            if (busy) {
                std::printf("[relay] shell rejected (another shell is connected)\n");
                kaze::v1::Envelope reply;
                reply.set_msg_id(hello.msg_id());
                auto* err = reply.mutable_error();
                err->set_code(409);
                err->set_message("another shell is connected");
                Send(*conn, reply);
                conn->alive = false;
            }
        } else if (conn->role == kaze::v1::ROLE_ADAPTER) {
            const std::string name = hello.hello().name();
            if (!name.empty()) {
                std::lock_guard<std::mutex> lock(adapters_mutex_);
                adapters_[name] = conn;
                conn->adapter_name = name;
                std::printf("[relay] adapter registered: %s\n", name.c_str());
            }
        }

        std::printf("[relay] client role=%d pid=%u\n", static_cast<int>(conn->role),
                    hello.hello().pid());

        while (!stop_.load() && conn->alive.load()) {
            DWORD available = 0;
            if (!PeekAvailable(conn->pipe, available, error)) {
                if (conn->alive.load()) std::printf("[relay] read failed: %s\n", error.c_str());
                break;
            }
            if (available < kaze::kHeaderSize) {
                std::this_thread::sleep_for(std::chrono::milliseconds(8));
                continue;
            }

            Frame next;
            if (!ReadFrame(conn->pipe, next, error)) {
                if (!error.empty() && error != "pipe closed") {
                    std::printf("[relay] read failed: %s\n", error.c_str());
                }
                break;
            }

            kaze::v1::Envelope envelope;
            if (!envelope.ParseFromArray(next.payload.data(), static_cast<int>(next.payload.size()))) {
                continue;
            }
            conn->last_seen_ms.store(NowMillis());
            Dispatch(*conn, envelope);
        }

        Close(conn);
    }

    void Dispatch(Connection& conn, kaze::v1::Envelope& envelope) {
        switch (envelope.body_case()) {
            case kaze::v1::Envelope::kInvokeRequest: {
                
                const std::string target_id = envelope.invoke_request().adapter();
                std::shared_ptr<Connection> target;
                bool ambiguous = false;
                {
                    std::lock_guard<std::mutex> lock(adapters_mutex_);
                    if (target_id.empty()) {
                        if (adapters_.size() == 1) target = adapters_.begin()->second;
                        ambiguous = adapters_.size() > 1;
                    } else {
                        auto it = adapters_.find(target_id);
                        if (it != adapters_.end()) target = it->second;
                    }
                }

                std::string failure;
                if (target) {
                    std::printf("[relay] invoke op=%s -> %s\n",
                                envelope.invoke_request().op().c_str(),
                                target->adapter_name.c_str());
                    if (!Send(*target, envelope)) failure = "adapter send failed";
                } else {
                    failure = ambiguous ? "adapter required when multiple adapters are connected"
                                        : "adapter not connected";
                }
                if (!failure.empty()) {
                    kaze::v1::Envelope reply;
                    reply.set_msg_id(envelope.msg_id());
                    reply.mutable_invoke_result()->set_ok(false);
                    reply.mutable_invoke_result()->set_detail(failure);
                    Send(conn, reply);
                }
                break;
            }
            case kaze::v1::Envelope::kInvokeResult: {
                
                std::shared_ptr<Connection> shell;
                {
                    std::lock_guard<std::mutex> lock(shell_mutex_);
                    shell = shell_;
                }
                if (shell) Send(*shell, envelope);
                break;
            }
            case kaze::v1::Envelope::kPing: {
                kaze::v1::Envelope reply;
                reply.set_msg_id(envelope.msg_id());
                reply.mutable_pong()->set_client_time_ms(envelope.ping().client_time_ms());
                reply.mutable_pong()->set_server_time_ms(NowMillis());
                Send(conn, reply);
                break;
            }
            case kaze::v1::Envelope::kAttach:
                HandleAttach(conn, envelope);
                break;
            case kaze::v1::Envelope::kTextBatch:
                
                ForwardBatch(std::move(*envelope.mutable_text_batch()));
                break;
            case kaze::v1::Envelope::kTextPush: {
                const std::string adapter_id = envelope.text_push().adapter();
                std::shared_ptr<Connection> target;
                {
                    std::lock_guard<std::mutex> lock(adapters_mutex_);
                    auto it = adapters_.find(adapter_id);
                    if (it != adapters_.end()) target = it->second;
                }
                const bool delivered = target ? Send(*target, envelope)
                                              : (adapter_id == FakeAdapter::kId && fake_started_.load());
                std::printf("[relay] push adapter=%s units=%d delivered=%d\n", adapter_id.c_str(),
                            envelope.text_push().units_size(), delivered ? 1 : 0);
                kaze::v1::Envelope reply;
                reply.set_msg_id(envelope.msg_id());
                reply.mutable_ack()->set_ref_id(envelope.msg_id());
                reply.mutable_ack()->set_ok(delivered);
                Send(conn, reply);
                break;
            }
            case kaze::v1::Envelope::kControl: {
                kaze::v1::Envelope reply;
                reply.set_msg_id(envelope.msg_id());
                reply.mutable_ack()->set_ref_id(envelope.msg_id());
                reply.mutable_ack()->set_ok(true);
                Send(conn, reply);
                break;
            }
            default: {
                kaze::v1::Envelope reply;
                reply.set_msg_id(envelope.msg_id());
                reply.mutable_error()->set_code(404);
                reply.mutable_error()->set_message("unhandled message");
                Send(conn, reply);
                break;
            }
        }
    }

    void HandleAttach(Connection& conn, kaze::v1::Envelope& envelope) {
        const std::string requested = envelope.attach().adapter();

        std::shared_ptr<Connection> adapter_conn;
        std::string resolved;
        bool ambiguous = false;

        {
            std::lock_guard<std::mutex> lock(adapters_mutex_);
            if (requested.empty() || requested == "auto") {
                if (adapters_.size() == 1) {
                    resolved = adapters_.begin()->first;
                    adapter_conn = adapters_.begin()->second;
                }
                ambiguous = adapters_.size() > 1;
            } else {
                auto it = adapters_.find(requested);
                if (it != adapters_.end()) {
                    resolved = it->first;
                    adapter_conn = it->second;
                }
            }
        }

        if (ambiguous) {
            kaze::v1::Envelope reply;
            reply.set_msg_id(envelope.msg_id());
            auto* result = reply.mutable_attach_result();
            result->set_ok(false);
            result->set_detail("adapter required when multiple adapters are connected");
            Send(conn, reply);
            return;
        }

        if (!resolved.empty() && adapter_conn) {
            kaze::v1::Envelope forward;
            forward.set_msg_id(NextMessageId());
            *forward.mutable_attach() = envelope.attach();
            if (!Send(*adapter_conn, forward)) {
                kaze::v1::Envelope reply;
                reply.set_msg_id(envelope.msg_id());
                auto* result = reply.mutable_attach_result();
                result->set_ok(false);
                result->set_detail("adapter send failed");
                Send(conn, reply);
                return;
            }

            std::printf("[relay] attach -> adapter=%s\n", resolved.c_str());

            kaze::v1::Envelope reply;
            reply.set_msg_id(envelope.msg_id());
            auto* result = reply.mutable_attach_result();
            result->set_ok(true);
            result->set_detail("attached");
            result->set_engine(resolved);
            Send(conn, reply);
            return;
        }

        if (!requested.empty() && requested != "auto" && requested != FakeAdapter::kId) {
            kaze::v1::Envelope reply;
            reply.set_msg_id(envelope.msg_id());
            reply.mutable_attach_result()->set_ok(false);
            reply.mutable_attach_result()->set_detail("adapter '" + requested + "' not connected");
            Send(conn, reply);
            return;
        }

        
        const auto started = std::chrono::steady_clock::now();
        {
            std::lock_guard<std::mutex> lock(adapter_mutex_);
            adapter_.Start([this](kaze::v1::TextBatch batch) { ForwardBatch(std::move(batch)); });
            fake_started_.store(true);
        }

        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                                 std::chrono::steady_clock::now() - started)
                                 .count();

        std::printf("[relay] attach -> adapter=%s (built-in)\n", FakeAdapter::kId);

        kaze::v1::Envelope reply;
        reply.set_msg_id(envelope.msg_id());
        auto* result = reply.mutable_attach_result();
        result->set_ok(true);
        result->set_detail("started");
        result->set_engine(FakeAdapter::kEngine);
        result->set_took_ms(static_cast<int64_t>(elapsed));
        Send(conn, reply);
    }

    void ForwardBatch(kaze::v1::TextBatch batch) {
        std::shared_ptr<Connection> shell;
        {
            std::lock_guard<std::mutex> lock(shell_mutex_);
            shell = shell_;
        }
        if (!shell || !shell->alive.load()) {
            std::printf("[relay] batch dropped (no shell) adapter=%s\n", batch.adapter().c_str());
            return;
        }

        if (batch.adapter().empty()) batch.set_adapter(FakeAdapter::kId);
        std::printf("[relay] batch adapter=%s units=%d -> shell\n", batch.adapter().c_str(),
                    batch.units_size());

        kaze::v1::Envelope envelope;
        envelope.set_msg_id(NextMessageId());
        *envelope.mutable_text_batch() = std::move(batch);
        Send(*shell, envelope);
    }

    static uint64_t NextMessageId() {
        static std::atomic<uint64_t> counter{0};
        return ++counter;
    }

    void Reject(std::shared_ptr<Connection> conn, uint64_t msg_id, uint32_t code, const char* message) {
        kaze::v1::Envelope reply;
        reply.set_msg_id(msg_id);
        reply.mutable_error()->set_code(code);
        reply.mutable_error()->set_message(message);
        Send(*conn, reply);
    }

    void Close(std::shared_ptr<Connection> conn) {
        conn->alive.store(false);
        {
            std::lock_guard<std::mutex> lock(shell_mutex_);
            if (shell_ == conn) shell_.reset();
        }
        if (!conn->adapter_name.empty()) {
            std::lock_guard<std::mutex> lock(adapters_mutex_);
            auto it = adapters_.find(conn->adapter_name);
            if (it != adapters_.end() && it->second == conn) adapters_.erase(it);
        }
        {
            std::lock_guard<std::mutex> lock(conn->write_mutex);
            if (conn->pipe != INVALID_HANDLE_VALUE) {
                DisconnectNamedPipe(conn->pipe);
                CloseHandle(conn->pipe);
                conn->pipe = INVALID_HANDLE_VALUE;
            }
        }
        std::printf("[relay] client closed\n");
    }

    std::string pipe_name_;
    std::string token_;
    std::mutex shell_mutex_;
    std::shared_ptr<Connection> shell_;
    std::mutex adapters_mutex_;
    std::unordered_map<std::string, std::shared_ptr<Connection>> adapters_;
    std::mutex adapter_mutex_;
    FakeAdapter adapter_;
    std::atomic<bool> fake_started_{false};
    std::atomic<bool> stop_{false};
};



struct Client {
    HANDLE pipe = INVALID_HANDLE_VALUE;
    uint64_t seq = 0;

    bool Open(const std::string& pipe_name, std::string& error) {
        const std::wstring path = L"\\\\.\\pipe\\" + ToWide(pipe_name);

        
        for (int attempt = 0; attempt < 100; ++attempt) {
            pipe = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
                               0, nullptr);
            if (pipe != INVALID_HANDLE_VALUE) return true;

            const DWORD code = GetLastError();
            if (code != ERROR_FILE_NOT_FOUND && code != ERROR_PIPE_BUSY) {
                error = "cannot open pipe: " + std::to_string(code);
                return false;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }

        error = "cannot open pipe: timeout";
        return false;
    }

    static std::wstring ToWide(const std::string& value) {
        const int size = MultiByteToWideChar(CP_UTF8, 0, value.c_str(), -1, nullptr, 0);
        std::wstring out(static_cast<size_t>(size > 0 ? size - 1 : 0), L'\0');
        if (size > 1) MultiByteToWideChar(CP_UTF8, 0, value.c_str(), -1, out.data(), size);
        return out;
    }

    bool Send(kaze::v1::Envelope& envelope, std::string& error) {
        envelope.set_msg_id(++seq);
        std::string bytes;
        if (!envelope.SerializeToString(&bytes)) {
            error = "failed to serialize envelope";
            return false;
        }

        Frame frame;
        frame.type = TypeOf(envelope);
        frame.msg_id = envelope.msg_id();
        frame.payload = std::move(bytes);
        return WriteFrame(pipe, frame, error);
    }

    bool Receive(kaze::v1::Envelope& out, std::string& error, int timeout_ms = 5000) {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
        while (std::chrono::steady_clock::now() < deadline) {
            DWORD available = 0;
            if (!PeekAvailable(pipe, available, error)) return false;
            if (available >= kaze::kHeaderSize) {
                Frame frame;
                const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                                           deadline - std::chrono::steady_clock::now())
                                           .count();
                if (!ReadFrame(pipe, frame, error, remaining > 0 ? static_cast<int>(remaining) : 1)) {
                    return false;
                }
                if (!out.ParseFromArray(frame.payload.data(), static_cast<int>(frame.payload.size()))) {
                    error = "invalid protobuf envelope";
                    return false;
                }
                return true;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        error = "timeout";
        return false;
    }

    void ClosePipe() {
        if (pipe != INVALID_HANDLE_VALUE) {
            CloseHandle(pipe);
            pipe = INVALID_HANDLE_VALUE;
        }
    }
};



int RunSelfTest() {
    RelayServer server("kaze-selftest-" + RandomHex(4), RandomHex(6));
    std::atomic<bool> stop{false};
    std::thread server_thread([&]() { server.Run(stop); });

    int code = 1;
    Client client;
    std::string error;

    do {
        if (!client.Open(server.pipe_name(), error)) {
            std::printf("[test] FAIL open: %s\n", error.c_str());
            break;
        }

        kaze::v1::Envelope hello;
        auto* h = hello.mutable_hello();
        h->set_proto(1);
        h->set_role(kaze::v1::ROLE_SHELL);
        h->set_pid(GetCurrentProcessId());
        h->set_token(server.token());
        h->set_nonce(RandomHex(4));
        if (!client.Send(hello, error)) {
            std::printf("[test] FAIL hello send: %s\n", error.c_str());
            break;
        }

        kaze::v1::Envelope welcome;
        if (!client.Receive(welcome, error) ||
            welcome.body_case() != kaze::v1::Envelope::kWelcome) {
            std::printf("[test] FAIL welcome: %s\n", error.c_str());
            break;
        }
        std::printf("[test] handshake ok session=%s\n", welcome.welcome().session().c_str());

        kaze::v1::Envelope attach;
        attach.mutable_attach()->set_adapter("fake");
        attach.mutable_attach()->set_game_id("selftest");
        if (!client.Send(attach, error)) {
            std::printf("[test] FAIL attach send: %s\n", error.c_str());
            break;
        }

        bool attach_ok = false;
        int units = 0;
        bool ack_ok = false;

        while (!(attach_ok && units > 0 && ack_ok)) {
            kaze::v1::Envelope message;
            if (!client.Receive(message, error, 6000)) {
                std::printf("[test] FAIL receive: %s\n", error.c_str());
                break;
            }

            switch (message.body_case()) {
                case kaze::v1::Envelope::kAttachResult:
                    attach_ok = message.attach_result().ok();
                    std::printf("[test] attach ok=%d engine=%s took=%lldms\n", attach_ok ? 1 : 0,
                                message.attach_result().engine().c_str(),
                                static_cast<long long>(message.attach_result().took_ms()));
                    break;
                case kaze::v1::Envelope::kTextBatch: {
                    units += message.text_batch().units_size();
                    std::printf("[test] batch units=%d first=\"%s\"\n",
                                message.text_batch().units_size(),
                                message.text_batch().units_size() > 0
                                    ? message.text_batch().units(0).source().c_str()
                                    : "");
                    kaze::v1::Envelope push;
                    auto* batch = push.mutable_text_push();
                    batch->set_adapter(message.text_batch().adapter());
                    auto* unit = batch->add_units();
                    unit->set_key(message.text_batch().units(0).key());
                    unit->set_target("早上好，今天天气也不错呢。");
                    if (!client.Send(push, error)) {
                        std::printf("[test] FAIL push send: %s\n", error.c_str());
                    }
                    break;
                }
                case kaze::v1::Envelope::kAck:
                    ack_ok = message.ack().ok();
                    std::printf("[test] ack ok=%d\n", ack_ok ? 1 : 0);
                    break;
                case kaze::v1::Envelope::kError:
                    std::printf("[test] relay error %u: %s\n", message.error().code(),
                                message.error().message().c_str());
                    break;
                default:
                    break;
            }
        }

        if (attach_ok && units > 0 && ack_ok) {
            std::printf("[test] PASS  handshake -> attach -> batch -> push -> ack\n");
            code = 0;
        } else {
            std::printf("[test] FAIL attach=%d units=%d ack=%d\n", attach_ok ? 1 : 0, units,
                        ack_ok ? 1 : 0);
        }
    } while (false);

    client.ClosePipe();
    stop.store(true);
    std::printf("[test] done\n");
    (void)server_thread;
    std::_Exit(code);  
    return code;
}



int Serve() {
    const std::string user = std::getenv("USERNAME") != nullptr ? std::getenv("USERNAME") : "user";
    const std::string pipe_name = "kaze-" + user + "-" + RandomHex(6);
    const std::string token = RandomHex(32);

    RelayServer server(pipe_name, token);
    WriteSession(pipe_name, token);

    std::printf("[relay] version  %s\n", kVersion);
    std::printf("[relay] pipe     \\\\.\\pipe\\%s\n", pipe_name.c_str());
    std::printf("[relay] token    %.8s...  (32 bytes)\n", token.c_str());
    std::printf("[relay] session  %s\n", SessionPath().c_str());
    std::printf("[relay] waiting for clients, Ctrl+C to stop\n");

    std::atomic<bool> stop{false};
    SetConsoleCtrlHandler(
        [](DWORD) -> BOOL {
            std::printf("\n[relay] stopping\n");
            std::_Exit(0);
        },
        TRUE);

    server.Run(stop);
    DeleteSession();
    return 0;
}

}  

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const std::string mode = argc > 1 ? argv[1] : "serve";
    if (mode == "--selftest") return RunSelfTest();
    if (mode == "--help" || mode == "-h") {
        std::printf(
            "Kaze relay\n\n"
            "  kaze-relay              start the relay and publish a session file\n"
            "  kaze-relay --selftest   run an in-process protocol round-trip\n");
        return 0;
    }
    return Serve();
}
