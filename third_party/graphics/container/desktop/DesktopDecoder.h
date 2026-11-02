#pragma once
#include "GpuBackend.h"
#include "SocketStream.h"
#include "gl2_dec.h"
#include "renderControl_dec.h"
#include <map>
#include <set>

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
    // Opcodes this endpoint already reported as unsupported (one log line each).
    std::set<uint32_t> skipped_;
    bool compositor_ = false;
    // GLES1 query calls that carry a reply (libGLESv1_CM's opcode space 1024..). They
    // must be answered; skipping them leaves the guest waiting for readback and it aborts
    // with "GL receive: Try again" (application log, round 144).
    bool es1Query(uint32_t opcode, uint32_t bytes, SocketStream& stream);
    bool reply(SocketStream& stream, const void* data, size_t size);
    std::set<uint32_t> answered_;
    void initializeGL();
    void initializeRC();
};
