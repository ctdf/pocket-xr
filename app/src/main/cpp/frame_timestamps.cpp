// 0.5.58 (findings-choppiness-vc70-vc74.md 13.5 item 1): each Space frame's own display present time, read from
// EGL_ANDROID_get_frame_timestamps a few frames after its swap, for the glasses frame clock's depth (GlassesFrameClock.onShown).
// App only: the extension is Android's own EGL layer, and the times are what SurfaceFlinger already measured for our surface.
// GL thread only, on the current context's draw surface (the Space's window).

#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <cstring>

#ifndef EGL_TIMESTAMPS_ANDROID
#define EGL_TIMESTAMPS_ANDROID 0x3430
#endif
#ifndef EGL_DISPLAY_PRESENT_TIME_ANDROID
#define EGL_DISPLAY_PRESENT_TIME_ANDROID 0x343A
#endif

namespace {

typedef EGLBoolean (*GetNextFrameId)(EGLDisplay, EGLSurface, EGLuint64KHR*);
typedef EGLBoolean (*GetFrameTimestamps)(EGLDisplay, EGLSurface, EGLuint64KHR, EGLint, const EGLint*, EGLnsecsANDROID*);
typedef EGLBoolean (*GetFrameTimestampSupported)(EGLDisplay, EGLSurface, EGLint);

GetNextFrameId nextFrameId = nullptr;
GetFrameTimestamps frameTimestamps = nullptr;
GetFrameTimestampSupported timestampSupported = nullptr;

// Results for Kotlin (FrameTimestamps.kt).
constexpr jlong NO_SURFACE = -10;
constexpr jlong NO_EXTENSION = -11;
constexpr jlong NO_PRESENT_TIME = -12;
constexpr jlong ATTRIB_REFUSED = -13;
constexpr jlong FAILED = -14;

bool hasExtension(EGLDisplay dpy, const char* name) {
    const char* all = eglQueryString(dpy, EGL_EXTENSIONS);
    if (all == nullptr) return false;
    const size_t n = strlen(name);
    for (const char* p = strstr(all, name); p != nullptr; p = strstr(p + n, name)) {
        if ((p == all || p[-1] == ' ') && (p[n] == ' ' || p[n] == '\0')) return true;
    }
    return false;
}

}  // namespace

extern "C" {

// Turns the timestamps on for the current draw surface: 1, or a negative reason.
JNIEXPORT jlong JNICALL
Java_com_hayeco_flipspaces_spatial_FrameTimestamps_nativeEnable(JNIEnv*, jclass) {
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (dpy == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return NO_SURFACE;
    if (!hasExtension(dpy, "EGL_ANDROID_get_frame_timestamps")) return NO_EXTENSION;
    nextFrameId = reinterpret_cast<GetNextFrameId>(eglGetProcAddress("eglGetNextFrameIdANDROID"));
    frameTimestamps = reinterpret_cast<GetFrameTimestamps>(eglGetProcAddress("eglGetFrameTimestampsANDROID"));
    timestampSupported = reinterpret_cast<GetFrameTimestampSupported>(eglGetProcAddress("eglGetFrameTimestampSupportedANDROID"));
    if (nextFrameId == nullptr || frameTimestamps == nullptr) return NO_EXTENSION;
    if (timestampSupported != nullptr && !timestampSupported(dpy, surface, EGL_DISPLAY_PRESENT_TIME_ANDROID)) return NO_PRESENT_TIME;
    if (!eglSurfaceAttrib(dpy, surface, EGL_TIMESTAMPS_ANDROID, EGL_TRUE)) return ATTRIB_REFUSED;
    return 1;
}

// The id the next swap of the current draw surface will have, or a negative reason.
JNIEXPORT jlong JNICALL
Java_com_hayeco_flipspaces_spatial_FrameTimestamps_nativeNextFrameId(JNIEnv*, jclass) {
    if (nextFrameId == nullptr) return NO_EXTENSION;
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (dpy == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return NO_SURFACE;
    EGLuint64KHR id = 0;
    if (!nextFrameId(dpy, surface, &id)) return FAILED;
    return static_cast<jlong>(id & 0x7fffffffffffffffULL);
}

// The display present time of frame [id] (CLOCK_MONOTONIC ns), EGL_TIMESTAMP_PENDING_ANDROID (-2) while not known yet,
// EGL_TIMESTAMP_INVALID_ANDROID (-1) for a frame never shown, or FAILED (no longer in the surface's history).
JNIEXPORT jlong JNICALL
Java_com_hayeco_flipspaces_spatial_FrameTimestamps_nativePresentTime(JNIEnv*, jclass, jlong id) {
    if (frameTimestamps == nullptr) return NO_EXTENSION;
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (dpy == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return NO_SURFACE;
    const EGLint names[] = {EGL_DISPLAY_PRESENT_TIME_ANDROID};
    EGLnsecsANDROID value = 0;
    if (!frameTimestamps(dpy, surface, static_cast<EGLuint64KHR>(id), 1, names, &value)) return FAILED;
    return static_cast<jlong>(value);
}

}  // extern "C"
