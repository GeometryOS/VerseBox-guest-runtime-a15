#include "DesktopDecoder.h"
#include "DesktopTrace.h"
#include "DesktopDisplay.h"
#include "DesktopThread.h"
#include "codec_defs.h"
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstring>
#include <string>

thread_local DesktopDecoder* DesktopDecoder::current_ = nullptr;

namespace {
int copyString(const char* text, void* output, int bytes) {
    if (!text) return 0;
    const auto length = static_cast<int>(std::strlen(text) + 1);
    if (!output || bytes < length) return -length;
    std::memcpy(output, text, length);
    return length;
}

const EGLint configAttributes[] = {EGL_BUFFER_SIZE, EGL_ALPHA_SIZE, EGL_BLUE_SIZE,
    EGL_GREEN_SIZE, EGL_RED_SIZE, EGL_DEPTH_SIZE, EGL_STENCIL_SIZE, EGL_CONFIG_CAVEAT,
    EGL_CONFIG_ID, EGL_LEVEL, EGL_MAX_PBUFFER_HEIGHT, EGL_MAX_PBUFFER_PIXELS,
    EGL_MAX_PBUFFER_WIDTH, EGL_NATIVE_RENDERABLE, EGL_NATIVE_VISUAL_ID,
    EGL_NATIVE_VISUAL_TYPE, EGL_SAMPLES, EGL_SAMPLE_BUFFERS, EGL_SURFACE_TYPE,
    EGL_TRANSPARENT_TYPE, EGL_TRANSPARENT_BLUE_VALUE, EGL_TRANSPARENT_GREEN_VALUE,
    EGL_TRANSPARENT_RED_VALUE, EGL_BIND_TO_TEXTURE_RGB, EGL_BIND_TO_TEXTURE_RGBA,
    EGL_MIN_SWAP_INTERVAL, EGL_MAX_SWAP_INTERVAL, EGL_LUMINANCE_SIZE, EGL_ALPHA_MASK_SIZE,
    EGL_COLOR_BUFFER_TYPE, EGL_RENDERABLE_TYPE, EGL_CONFORMANT};
}

DesktopDecoder::DesktopDecoder(GpuBackend& backend) : backend_(backend) {
    checksum_.setVersion(1);
    current_ = this;
    initializeGL();
    initializeRC();
}

void DesktopDecoder::initializeGL() {
    gl_.initDispatchByName([](const char* name, void*) -> void* {
        auto* symbol = reinterpret_cast<void*>(eglGetProcAddress(name));
        if (!symbol) symbol = dlsym(RTLD_DEFAULT, name);
        return symbol;
    }, nullptr);
    #include "gl-adapters.inc"
    gl_.glFenceSyncAEMU = [](void*, GLenum condition, GLbitfield flags) -> uint64_t {
        return current_->backend_.syncs().fence(condition, flags, GpuBackend::process());
    };
    gl_.glClientWaitSyncAEMU = [](void*, uint64_t handle, GLbitfield flags, GLuint64 timeout) -> GLenum {
        return current_->backend_.syncs().clientWait(handle, flags, timeout);
    };
    gl_.glWaitSyncAEMU = [](void*, uint64_t handle, GLbitfield flags, GLuint64 timeout) {
        current_->backend_.syncs().wait(handle, flags, timeout);
    };
    gl_.glDeleteSyncAEMU = [](void*, uint64_t handle) { current_->backend_.syncs().destroy(handle); };
    gl_.glIsSyncAEMU = [](void*, uint64_t handle) -> GLboolean { return current_->backend_.syncs().contains(handle); };
    gl_.glGetSyncivAEMU = [](void*, uint64_t handle, GLenum name, GLsizei capacity, GLsizei* length, GLint* values) {
        current_->backend_.syncs().query(handle, name, capacity, length, values);
    };
    gl_.glGetError = []() -> GLenum {
        const auto error = GuestGpuSync::error();
        return error == GL_NO_ERROR ? glGetError() : error;
    };
    gl_.glBindTexture = [](GLenum target, GLuint texture) {
        current_->backend_.bindGuestTexture(target, texture);
    };
    gl_.glDeleteTextures = [](GLsizei count, const GLuint* textures) {
        current_->backend_.deleteGuestTextures(count, textures);
    };
    gl_.glTexImage2D = [](GLenum target, GLint level, GLint internalFormat, GLsizei width,
            GLsizei height, GLint border, GLenum format, GLenum type, const void* pixels) {
        glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        current_->backend_.uploadTexture(target);
    };
    gl_.glTexSubImage2D = [](GLenum target, GLint level, GLint x, GLint y, GLsizei width,
            GLsizei height, GLenum format, GLenum type, const void* pixels) {
        glTexSubImage2D(target, level, x, y, width, height, format, type, pixels);
        current_->backend_.uploadTexture(target);
    };
    gl_.glVertexAttribPointerData = [](void*, GLuint index, GLint size, GLenum type,
            GLboolean normalized, GLsizei stride, void* data, GLuint length) {
        auto& stored = current_->attributes_[index];
        stored.assign(static_cast<unsigned char*>(data), static_cast<unsigned char*>(data) + length);
        // glUtilsPackPointerData removes the guest mesh's interleaved stride.
        glVertexAttribPointer(index, size, type, normalized, 0, stored.data());
    };
    gl_.glVertexAttribPointerOffset = [](void*, GLuint index, GLint size, GLenum type,
            GLboolean normalized, GLsizei stride, GLuint offset) {
        glVertexAttribPointer(index, size, type, normalized, stride,
                              reinterpret_cast<const void*>(static_cast<uintptr_t>(offset)));
    };
    gl_.glDrawElementsOffset = [](void*, GLenum mode, GLsizei count, GLenum type, GLuint offset) {
        glDrawElements(mode, count, type, reinterpret_cast<const void*>(static_cast<uintptr_t>(offset)));
    };
    gl_.glDrawElementsData = [](void*, GLenum mode, GLsizei count, GLenum type, void* data, GLuint length) {
        glDrawElements(mode, count, type, data);
    };
    gl_.glShaderString = [](void*, GLuint shader, const GLchar* string, GLsizei length) {
        glShaderSource(shader, 1, &string, &length);
    };
    gl_.glFinishRoundTrip = [](void*) -> int { glFinish(); return 0; };
    gl_.glGetCompressedTextureFormats = [](void*, GLint count, GLint* formats) {
        glGetIntegerv(GL_COMPRESSED_TEXTURE_FORMATS, formats);
    };
    gl_.glGetIntegerv = [](GLenum name, GLint* values) {
        if (name == GL_MAJOR_VERSION) *values = 2;
        else if (name == GL_MINOR_VERSION) *values = 0;
        else {
            glGetIntegerv(name, values);
            if (name == GL_MAX_VERTEX_ATTRIBS) {
                *values = std::min(*values, static_cast<GLint>(CODEC_MAX_VERTEX_ATTRIBUTES));
            }
        }
    };
    gl_.glEGLImageTargetTexture2DOES = [](GLenum target, GLeglImageOES image) {
        auto function = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        function(target, current_->backend_.image(static_cast<uint32_t>(reinterpret_cast<uintptr_t>(image))));
    };
    gl_.glBindFramebuffer = [](GLenum target, GLuint framebuffer) {
        current_->backend_.bindFramebuffer(target, framebuffer);
    };
}

void DesktopDecoder::initializeRC() {
    rc_.rcGetRendererVersion = []() -> GLint { return 1; };
    rc_.rcGetEGLVersion = [](EGLint* major, EGLint* minor) -> EGLint {
        *major = 1; *minor = 4; return EGL_TRUE;
    };
    rc_.rcQueryEGLString = [](EGLenum name, void* output, EGLint bytes) -> EGLint {
        if (name == EGL_EXTENSIONS) return copyString("EGL_KHR_image_base EGL_KHR_gl_texture_2D_image", output, bytes);
        return copyString(eglQueryString(current_->backend_.display(), name), output, bytes);
    };
    rc_.rcGetGLString = [](EGLenum name, void* output, EGLint bytes) -> EGLint {
        if (name == GL_VERSION) return copyString("OpenGL ES 2.0 A10VM", output, bytes);
        if (name == GL_SHADING_LANGUAGE_VERSION) return copyString("OpenGL ES GLSL ES 1.00 A10VM", output, bytes);
        if (name == GL_EXTENSIONS) return copyString(
            "GL_OES_EGL_image GL_OES_EGL_image_external GL_OES_element_index_uint "
            "GL_OES_texture_npot GL_OES_standard_derivatives GL_EXT_texture_format_BGRA8888 "
            "GL_OES_rgb8_rgba8", output, bytes);
        return copyString(reinterpret_cast<const char*>(glGetString(name)), output, bytes);
    };
    rc_.rcGetNumConfigs = [](uint32_t* attributes) -> EGLint {
        *attributes = sizeof(configAttributes) / sizeof(configAttributes[0]);
        return current_->backend_.configs().size();
    };
    rc_.rcGetConfigs = [](uint32_t bytes, GLuint* output) -> EGLint {
        auto& backend = current_->backend_;
        constexpr auto count = sizeof(configAttributes) / sizeof(configAttributes[0]);
        if (bytes < sizeof(configAttributes) * (backend.configs().size() + 1)) return 0;
        std::memcpy(output, configAttributes, sizeof(configAttributes));
        auto* values = reinterpret_cast<EGLint*>(output + count);
        for (auto config : backend.configs()) {
            for (auto attribute : configAttributes) {
                eglGetConfigAttrib(backend.display(), config, attribute, values);
                if (attribute == EGL_SURFACE_TYPE) *values |= EGL_WINDOW_BIT;
                // ES1 is served too (see GpuBackend::context): report it so an app that
                // filters configs by EGL_OPENGL_ES_BIT still finds one.
                if (attribute == EGL_RENDERABLE_TYPE || attribute == EGL_CONFORMANT)
                    *values &= (EGL_OPENGL_ES_BIT | EGL_OPENGL_ES2_BIT);
                ++values;
            }
        }
        return backend.configs().size();
    };
    rc_.rcChooseConfig = [](EGLint* attributes, uint32_t bytes, uint32_t* output, uint32_t capacity) -> EGLint {
        auto& backend = current_->backend_;
        std::vector<EGLint> adapted;
        for (uint32_t i = 0; i + 1 < bytes / sizeof(EGLint) && attributes[i] != EGL_NONE; i += 2) {
            if ((attributes[i] == EGL_RENDERABLE_TYPE || attributes[i] == EGL_CONFORMANT) &&
                    attributes[i + 1] != EGL_DONT_CARE &&
                    (attributes[i + 1] & ~(EGL_OPENGL_ES_BIT | EGL_OPENGL_ES2_BIT))) return 0;
            if (attributes[i] == EGL_SURFACE_TYPE) adapted.insert(adapted.end(), {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT});
            else adapted.insert(adapted.end(), {attributes[i], attributes[i + 1]});
        }
        adapted.push_back(EGL_NONE);
        std::vector<EGLConfig> selected(backend.configs().size());
        EGLint count;
        if (!eglChooseConfig(backend.display(), adapted.data(), selected.data(), selected.size(), &count)) return 0;
        unsigned found = 0;
        for (int i = 0; i < count; ++i) {
            auto item = std::find(backend.configs().begin(), backend.configs().end(), selected[i]);
            if (item == backend.configs().end()) continue;
            if (output && found < capacity) output[found] = item - backend.configs().begin();
            ++found;
        }
        return output ? std::min(found, capacity) : found;
    };
    rc_.rcGetFBParam = [](EGLint name) -> EGLint {
        switch (name) {
            case 1: return current_->backend_.width();
            case 2: return current_->backend_.height();
            case 3: case 4: return desktopdisplay::density;
            case 5: return desktopdisplay::refreshHz;
            case 6: return 0;
            case 7: return 1;
            default: return 0;
        }
    };
    rc_.rcCreateContext = [](uint32_t config, uint32_t share, uint32_t version) {
        return current_->backend_.context(config, share, version);
    };
    rc_.rcDestroyContext = [](uint32_t context) { current_->backend_.destroyContext(context); };
    rc_.rcCreateWindowSurface = [](uint32_t config, uint32_t width, uint32_t height) {
        return current_->backend_.surface(config, width, height);
    };
    rc_.rcDestroyWindowSurface = [](uint32_t surface) { current_->backend_.destroySurface(surface); };
    rc_.rcCreateColorBuffer = [](uint32_t width, uint32_t height, GLenum format) {
        return current_->backend_.colorBuffer(width, height, format);
    };
    rc_.rcCreateColorBufferDMA = [](uint32_t width, uint32_t height, GLenum format, int) {
        return current_->backend_.colorBuffer(width, height, format);
    };
    rc_.rcOpenColorBuffer = [](uint32_t color) { current_->backend_.openColorBuffer(color); };
    rc_.rcOpenColorBuffer2 = [](uint32_t color) -> int { current_->backend_.openColorBuffer(color); return 0; };
    rc_.rcCloseColorBuffer = [](uint32_t color) { current_->backend_.closeColorBuffer(color); };
    rc_.rcSetWindowColorBuffer = [](uint32_t surface, uint32_t color) { current_->backend_.setColorBuffer(surface, color); };
    rc_.rcFlushWindowColorBuffer = [](uint32_t surface) { return current_->backend_.flushColorBuffer(surface); };
    rc_.rcFlushWindowColorBufferAsync = [](uint32_t surface) { current_->backend_.flushColorBuffer(surface); };
    rc_.rcMakeCurrent = [](uint32_t context, uint32_t draw, uint32_t read) -> EGLint {
        return current_->backend_.makeCurrent(context, draw, read);
    };
    rc_.rcBindTexture = [](uint32_t color) { current_->backend_.bindTexture(color); };
    rc_.rcBindRenderbuffer = [](uint32_t color) { current_->backend_.bindRenderbuffer(color); };
    rc_.rcColorBufferCacheFlush = [](uint32_t color, EGLint postCount, int read) -> EGLint {
        return current_->backend_.cacheFlush(color, postCount, read);
    };
    rc_.rcReadColorBuffer = [](uint32_t color, GLint x, GLint y, GLint width, GLint height,
                               GLenum format, GLenum type, void* data) {
        current_->backend_.readColor(color, x, y, width, height, format, type, data);
    };
    rc_.rcUpdateColorBuffer = [](uint32_t color, GLint x, GLint y, GLint width, GLint height,
                                 GLenum format, GLenum type, void* data) {
        return current_->backend_.updateColor(color, x, y, width, height, format, type, data);
    };
    rc_.rcCreateClientImage = [](uint32_t context, EGLenum target, GLuint texture) {
        return current_->backend_.clientImage(context, target, texture);
    };
    rc_.rcDestroyClientImage = [](uint32_t image) { return current_->backend_.destroyImage(image); };
    rc_.rcFBPost = [](uint32_t color) {
        if (!current_->compositor_) {
            desktopthread::configure("a10-compositor", -4);
            current_->compositor_ = true;
        }
        current_->backend_.post(color);
    };
    rc_.rcFBSetSwapInterval = [](EGLint interval) { current_->backend_.setSwapInterval(interval); };
    rc_.rcSelectChecksumHelper = [](uint32_t version, uint32_t) { current_->checksum_.setVersion(version); };
    rc_.rcSetPuid = [](uint64_t) { };
}

namespace {
uint32_t readU32(const unsigned char* data, size_t size, size_t offset) {
    uint32_t value = 0;
    if (offset + sizeof(value) <= size) std::memcpy(&value, data + offset, sizeof(value));
    return value;
}
}

// Write a reply (payload + protocol checksum) back to the guest. The GL protocol v1
// checksum covers the reply payload and then a per-reply counter, so the host has to
// produce it with the same calculator it validates requests with.
bool DesktopDecoder::reply(SocketStream& stream, const void* data, size_t size) {
    const size_t checksumSize = checksum_.checksumByteSize();
    std::vector<unsigned char> out(size + checksumSize);
    if (size) std::memcpy(out.data(), data, size);
    if (checksumSize) {
        checksum_.addBuffer(data, size);
        checksum_.writeChecksum(out.data() + size, checksumSize);
    }
    return stream.writeFully(out.data(), out.size()) >= 0;
}

// GLES1 queries. Wire format (GLESv1_enc/gl_enc.cpp):
//   get*v:   [opcode][total][pname][payloadSize][checksum]  -> payloadSize bytes + checksum
//   geterror:[opcode][total][checksum]                      -> 4 bytes + checksum
//   is*:     [opcode][total][arg][checksum]                 -> 1 byte + checksum
//   tex/buf: [opcode][total][target][pname][payloadSize][checksum]
bool DesktopDecoder::es1Query(uint32_t opcode, uint32_t bytes, SocketStream& stream) {
    const auto u32 = [&](size_t offset) { return readU32(packet_.data(), bytes, offset); };
    const auto payload = [&](size_t offset) {
        size_t size = u32(offset);
        return size > 65536 ? size_t{65536} : size;
    };
    std::vector<unsigned char> result;
    switch (opcode) {
        case 1105: {  // glGetError
            const GLenum error = gl_.glGetError ? gl_.glGetError() : GL_NO_ERROR;
            result.resize(sizeof(error));
            std::memcpy(result.data(), &error, sizeof(error));
            break;
        }
        case 1107: case 1034: case 1100: case 1106: {  // GetIntegerv/Floatv/Booleanv/Fixedv
            const GLenum pname = u32(8);
            result.assign(payload(12), 0);
            if (opcode == 1107) { if (gl_.glGetIntegerv) gl_.glGetIntegerv(pname, reinterpret_cast<GLint*>(result.data())); }
            else if (opcode == 1034) { if (gl_.glGetFloatv) gl_.glGetFloatv(pname, reinterpret_cast<GLfloat*>(result.data())); }
            else if (opcode == 1100) { if (gl_.glGetBooleanv) gl_.glGetBooleanv(pname, reinterpret_cast<GLboolean*>(result.data())); }
            else {
                // glGetFixedv: ES2 has no fixed-point getter; answer 16.16 from GLfloat.
                GLfloat floats[64];
                std::memset(floats, 0, sizeof(floats));
                if (gl_.glGetFloatv) gl_.glGetFloatv(pname, floats);
                int32_t* fixed = reinterpret_cast<int32_t*>(result.data());
                for (size_t i = 0; i < 64 && (i + 1) * sizeof(int32_t) <= result.size(); ++i)
                    fixed[i] = static_cast<int32_t>(floats[i] * 65536.0f);
            }
            break;
        }
        case 1103: case 1104: {  // glGenBuffers / glGenTextures
            const GLsizei count = static_cast<GLsizei>(u32(8));
            result.assign(payload(12), 0);
            GLuint* names = reinterpret_cast<GLuint*>(result.data());
            if (opcode == 1104) { if (gl_.glGenTextures) gl_.glGenTextures(count, names); }
            else { if (gl_.glGenBuffers) gl_.glGenBuffers(count, names); }
            break;
        }
        case 1118: {  // glIsEnabled
            const GLboolean enabled = gl_.glIsEnabled ? gl_.glIsEnabled(u32(8)) : GL_FALSE;
            result.assign(1, enabled ? 1 : 0);
            break;
        }
        case 1119: {  // glIsTexture
            const GLboolean texture = gl_.glIsTexture
                ? gl_.glIsTexture(static_cast<GLuint>(u32(8))) : GL_FALSE;
            result.assign(1, texture ? 1 : 0);
            break;
        }
        case 1117: {  // glIsBuffer
            const GLboolean buffer = gl_.glIsBuffer
                ? gl_.glIsBuffer(static_cast<GLuint>(u32(8))) : GL_FALSE;
            result.assign(1, buffer ? 1 : 0);
            break;
        }
        case 1101: case 1038: case 1114: case 1115: {  // tex/buffer parameter queries
            const GLenum target = u32(8);
            const GLenum pname = u32(12);
            result.assign(payload(16), 0);
            if (opcode == 1101) { if (gl_.glGetBufferParameteriv) gl_.glGetBufferParameteriv(target, pname, reinterpret_cast<GLint*>(result.data())); }
            else if (opcode == 1114) { if (gl_.glGetTexParameteriv) gl_.glGetTexParameteriv(target, pname, reinterpret_cast<GLint*>(result.data())); }
            break;
        }
        // Fixed-function state queries: ES2 has no equivalent getter, and these calls only
        // need a reply of the right size. The shapes below are copied from gl_enc.cpp.
        case 1033: case 1210: case 1209:                 // glGetClipPlanef / glGetClipPlanex{,OES}
            result.assign(payload(12), 0);
            break;
        case 1035: case 1108: case 1212:                 // glGetLight{fv,xv,xvOES}
        case 1036: case 1109: case 1213:                 // glGetMaterial{fv,xv,xvOES}
        case 1037: case 1112: case 1113: case 1214:      // glGetTexEnv{fv,iv,xv,xvOES}
            result.assign(payload(16), 0);
            break;
        case 1110: {  // glGetPointerv: client arrays live in the guest
            result.assign(payload(12), 0);
            break;
        }
        case 1185: {  // glFinishRoundTrip: [op][total][checksum] -> 4 bytes + checksum
            // Measured with the host trace (instance 1793525278509, round 147):
            //   desktop_gpu_skip opcode=1025 bytes=32 decoded=0   (glClearColor, one-way)
            //   desktop_gpu_skip opcode=1185 bytes=16 decoded=0   (glFinishRoundTrip, reply!)
            // Skipping 1185 left the guest blocked in readback and produced
            // "GL receive: Try again" -> abort(134).
            if (gl_.glFinish) gl_.glFinish();
            result.assign(4, 0);
            break;
        }
        default:
            return false;
    }
    // Every incoming packet carries one checksum counter tick, including the ones this
    // endpoint answers itself instead of decoding. The generated decoders advance that
    // counter through validate(); a query answered here has to do the same or the next
    // decoded packet compares against a stale counter. Measured consequence without this
    // (instance 1793525278509, round 147):
    //   desktop_gpu_es1_query opcode=1185 size=4
    //   desktop_gpu_client_error ... OP_rcSetWindowColorBuffer: GL checksumCalculator failure
    const size_t requestChecksum = checksum_.checksumByteSize();
    if (requestChecksum && packet_.size() >= 8 + requestChecksum)
        checksum_.validate(packet_.data() + 8, requestChecksum);
    if (answered_.insert(opcode).second)
        desktop_gpu_trace("desktop_gpu_es1_query opcode=%u size=%zu\n", opcode, result.size());
    reply(stream, result.data(), result.size());
    return true;
}

bool DesktopDecoder::next(SocketStream& stream) {
    current_ = this;
    uint32_t header[2];
    if (!stream.readOrEof(header, sizeof(header))) return false;
    if (header[1] < 8 || header[1] > IOStream::maxTransfer)
        throw std::runtime_error("Invalid desktop GPU command size: opcode=" + std::to_string(header[0]) +
            " bytes=" + std::to_string(header[1]) + " limit=" + std::to_string(IOStream::maxTransfer) +
            " previousOpcode=" + std::to_string(previousOpcode_) +
            " previousBytes=" + std::to_string(previousBytes_));
    packet_.resize(header[1]);
    auto& packet = packet_;
    std::memcpy(packet.data(), header, sizeof(header));
    stream.readFully(packet.data() + 8, packet.size() - 8);
    // GLES1 queries first: they need a reply, and the GLES2 decoder cannot produce one
    // for an ES1 opcode.
    if (header[0] < 10000 && es1Query(header[0], static_cast<uint32_t>(packet.size()), stream)) {
        previousOpcode_ = header[0];
        previousBytes_ = header[1];
        return true;
    }
    size_t decoded;
    try {
        decoded = header[0] >= 10000 && header[0] < 11000
            ? rc_.decode(packet.data(), packet.size(), &stream, &checksum_)
            : gl_.decode(packet.data(), packet.size(), &stream, &checksum_);
    } catch (const std::out_of_range&) {
        uint32_t handle = 0;
        if (packet.size() >= 12) std::memcpy(&handle, packet.data() + 8, sizeof(handle));
        throw std::runtime_error("Desktop GPU missing handle " + std::to_string(handle) +
                                 " for opcode " + std::to_string(header[0]));
    }
    if (decoded != packet.size()) {
        // A packet this endpoint cannot decode must not end the session. The protocol is
        // length-prefixed and the whole packet has already been read in (readFully above),
        // so dropping it keeps the stream aligned and only this one call is lost.
        //
        // The concrete case is GLES1: the guest's libGLESv1_CM.so encodes a *separate*
        // opcode space (gl_opcodes.h: 1024..1317) that this desktop endpoint has no
        // decoder for, while GLES2 starts at 2048 (gl2_opcodes.h). Throwing here is what
        // let a single unknown call take the whole virtual machine down:
        //   application-…-49.log: "Unsupported desktop GPU opcode 1025"
        //   session-state.log: state=ERROR            (round 131, AIDA64)
        // renderControl (>= 10000) stays fatal: a failure there means a broken handle.
        if (header[0] >= 10000)
            throw std::runtime_error("Unsupported desktop GPU opcode " + std::to_string(header[0]));
        // Keep the checksum counter in step. GL protocol v1 stores "reversed argument
        // length + packet counter" after the 8-byte header, and the decoder advances that
        // counter once per validated packet. A skipped packet never reaches the generated
        // validate() call, so every later packet would compare against a stale counter and
        // die with "GL checksumCalculator failure" - exactly how the first ES1 attempt
        // ended (session-state.log: state=ERROR / OP_rcGetGLString checksum failure).
        // The result is deliberately ignored here: this call advances the counter and
        // resets the per-packet length, which is all the next packet needs.
        const size_t checksumSize = checksum_.checksumByteSize();
        if (decoded == 0 && checksumSize && packet.size() >= 8 + checksumSize)
            checksum_.validate(packet.data() + 8, checksumSize);
        if (skipped_.insert(header[0]).second)
            desktop_gpu_trace("desktop_gpu_skip opcode=%u bytes=%u decoded=%zu\n", header[0], header[1], decoded);
        previousOpcode_ = header[0];
        previousBytes_ = header[1];
        return true;
    }
    previousOpcode_ = header[0];
    previousBytes_ = header[1];
    return true;
}
