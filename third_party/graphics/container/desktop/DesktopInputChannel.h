#pragma once
#include "SocketStream.h"
#include "TouchQueue.h"
#include <array>
#include <atomic>
#include <mutex>
#include <string>
#include <thread>

class DesktopInputChannel {
public:
    explicit DesktopInputChannel(const std::string& path);
    ~DesktopInputChannel();
    uint32_t submit(touchwire::Event event);
    void finish();
    std::array<int64_t, 3> state();
    std::string error();
private:
    void run();
    std::string path_, error_;
    std::mutex mutex_;
    std::atomic<bool> closing_{false};
    bool finished_ = false;
    int listener_ = -1, client_ = -1;
    uint32_t sequence_ = 0, accepted_ = 0;
    int64_t time_ = 0;
    touchwire::Queue queue_;
    std::thread worker_;
};
