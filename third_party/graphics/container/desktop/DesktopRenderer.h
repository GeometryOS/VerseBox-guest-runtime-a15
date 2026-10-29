#pragma once
#include "DesktopGpuServer.h"
#include "DesktopInputChannel.h"
#include <array>
#include <vector>

using WindowHandle = DesktopPresenter::Window;
class DesktopRenderer {
public:
    explicit DesktopRenderer(const std::string& directory);
    int64_t attach(WindowHandle window) { return gpu_.attach(std::move(window)); }
    void detach(int64_t generation) { gpu_.detach(generation); }
    uint32_t touch(touchwire::Event event) { return input_.submit(event); }
    uint32_t tap(int x, int y);
    bool requestExit() { input_.finish(); return true; }
    std::array<int64_t, 17> metrics();
    std::string error();
    std::string description();
    std::vector<int64_t> intervals() { return {}; }
    void resetTimings() { }
private:
    DesktopGpuServer gpu_;
    DesktopInputChannel input_;
    std::string description_;
};
