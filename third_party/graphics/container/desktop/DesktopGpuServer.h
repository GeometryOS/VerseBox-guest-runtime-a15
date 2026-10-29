#pragma once
#include "GpuBackend.h"
#include "DesktopDisplay.h"
#include <atomic>
#include <memory>
#include <mutex>
#include <map>
#include <string>
#include <thread>
#include <vector>

class DesktopGpuServer {
public:
    explicit DesktopGpuServer(const std::string& path);
    ~DesktopGpuServer();
    GpuBackend& backend() { return backend_; }
    int64_t attach(DesktopPresenter::Window window);
    void detach(int64_t generation);
    std::string error();
    size_t workerCount() const { return decoderCount_.load(); }
private:
    void run();
    void client(int fd, pid_t process, int processDescriptor);
    void reapDecoders();
    void reapProcesses();
    void fail(const std::exception& error);
    GpuBackend backend_{desktopdisplay::width, desktopdisplay::height};
    std::string path_, error_;
    int listener_ = -1;
    int wake_ = -1;
    std::atomic<bool> closing_{false};
    std::mutex mutex_;
    std::map<int, pid_t> clients_;
    struct Process { int descriptor; bool dead = false; };
    std::map<pid_t, Process> processes_;
    std::thread worker_;
    struct DecoderWorker {
        std::thread thread;
        std::shared_ptr<std::atomic<bool>> done;
        pid_t process = 0;
    };
    std::vector<DecoderWorker> decoders_;
    std::atomic<size_t> decoderCount_{0};
};
