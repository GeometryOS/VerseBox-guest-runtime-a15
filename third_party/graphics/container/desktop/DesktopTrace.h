#pragma once
#include <cstdarg>

// Host GPU endpoint trace. Lines go to stderr *and* to a file next to the GPU socket
// (<instance>/native-window/gpu.sock -> <instance>/native-window/gpu-host.log), because an
// app-process stderr is not readable with adb and every blind round so far started with
// "the host logged nothing we could read".
void desktop_gpu_trace(const char* format, ...);
void desktop_gpu_trace_path(const char* socketPath);
