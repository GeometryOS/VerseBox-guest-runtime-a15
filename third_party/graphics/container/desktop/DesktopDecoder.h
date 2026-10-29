#pragma once
#include "GpuBackend.h"
#include "SocketStream.h"
#include "gl2_dec.h"
#include "renderControl_dec.h"
#include <map>

class DesktopDecoder {
public:
    explicit DesktopDecoder(GpuBackend& backend);
    bool next(SocketStream& stream);
private:
    static thread_local DesktopDecoder* current_;
    GpuBackend& backend_;
    gl2_decoder_context_t gl_{};
    renderControl_decoder_context_t rc_{};
    ChecksumCalculator checksum_;
    std::map<GLuint, std::vector<unsigned char>> attributes_;
    std::vector<unsigned char> packet_;
    uint32_t previousOpcode_ = 0, previousBytes_ = 0;
    bool compositor_ = false;
    void initializeGL();
    void initializeRC();
};
