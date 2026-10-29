#ifdef A10VM_DESKTOP_WINDOW
#include "DesktopRenderer.h"
#include "DesktopDisplay.h"
using WindowRenderer = DesktopRenderer;
#else
#include "WindowRenderer.h"
#endif
#include <android/native_window_jni.h>
#include <jni.h>

namespace {
WindowRenderer& renderer(jlong handle) { return *reinterpret_cast<WindowRenderer*>(handle); }
void reportError(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
jlongArray numbers(JNIEnv* env, const int64_t* data, size_t size) {
    jlongArray result = env->NewLongArray(static_cast<jsize>(size));
    if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jlong*>(data));
    return result;
}
}

#ifdef A10VM_DESKTOP_WINDOW
extern "C" JNIEXPORT jintArray JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_displayMode(JNIEnv* env, jclass) {
    const jint values[] = {desktopdisplay::width, desktopdisplay::height,
                           desktopdisplay::refreshHz, desktopdisplay::density};
    jintArray result = env->NewIntArray(4);
    if (result) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_create(JNIEnv* env, jclass, jstring directory) {
    const char* value = env->GetStringUTFChars(directory, nullptr);
    if (!value) return 0;
    std::string path(value);
    env->ReleaseStringUTFChars(directory, value);
    try { return reinterpret_cast<jlong>(new WindowRenderer(path)); }
    catch (const std::exception& error) { reportError(env, error); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_attach(JNIEnv* env, jclass, jlong handle, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Missing native window");
        return 0;
    }
    return renderer(handle).attach(WindowHandle(window, ANativeWindow_release));
}

extern "C" JNIEXPORT void JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_detach(JNIEnv*, jclass, jlong handle, jlong generation) {
    renderer(handle).detach(generation);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_tap(JNIEnv* env, jclass, jlong handle, jint x, jint y) {
    try { return static_cast<jint>(renderer(handle).tap(x, y)); }
    catch (const std::exception& error) { reportError(env, error); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_touch(JNIEnv* env, jclass, jlong handle,
        jint action, jint index, jlong timeNanos, jintArray ids, jintArray xs, jintArray ys, jfloatArray pressures) {
    try {
        if (!ids || !xs || !ys || !pressures) throw std::runtime_error("Missing touch arrays");
        const jsize count = env->GetArrayLength(ids);
        if (count > static_cast<jsize>(touchwire::maxPointers) ||
            env->GetArrayLength(xs) != count || env->GetArrayLength(ys) != count ||
            env->GetArrayLength(pressures) != count) throw std::runtime_error("Touch array size mismatch");
        jint id[10]{}, x[10]{}, y[10]{};
        jfloat pressure[10]{};
        if (count) {
            env->GetIntArrayRegion(ids, 0, count, id);
            env->GetIntArrayRegion(xs, 0, count, x);
            env->GetIntArrayRegion(ys, 0, count, y);
            env->GetFloatArrayRegion(pressures, 0, count, pressure);
            if (env->ExceptionCheck()) return 0;
        }
        touchwire::Event event;
        event.action = action; event.actionIndex = index; event.count = count; event.timeNanos = timeNanos;
        for (jsize i = 0; i < count; ++i) event.pointers[i] = {id[i], x[i], y[i], pressure[i]};
        return renderer(handle).touch(event);
    } catch (const std::exception& error) { reportError(env, error); return 0; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_metrics(JNIEnv* env, jclass, jlong handle) {
    const auto values = renderer(handle).metrics();
    return numbers(env, values.data(), values.size());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_intervals(JNIEnv* env, jclass, jlong handle) {
    const auto values = renderer(handle).intervals();
    return numbers(env, values.data(), values.size());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_error(JNIEnv* env, jclass, jlong handle) {
    return env->NewStringUTF(renderer(handle).error().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_description(JNIEnv* env, jclass, jlong handle) {
    return env->NewStringUTF(renderer(handle).description().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_resetTimings(JNIEnv*, jclass, jlong handle) {
    renderer(handle).resetTimings();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_requestExit(JNIEnv*, jclass, jlong handle) {
    return renderer(handle).requestExit() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_a10vm_nativedisplay_WindowBridge_destroy(JNIEnv*, jclass, jlong handle) {
    delete &renderer(handle);
}
