#include <jni.h>
#include <string>
#include <Processing.NDI.Lib.h>
#include <thread>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <cinttypes>
#include <vector>
#include <map>
#include <mutex>
#include <atomic>
#include <chrono>

static const char* TAG = "NDIReceiver";

// PERSISTENT GLOBAL STATE
static NDIlib_find_instance_t g_pNDI_find = nullptr;
static NDIlib_recv_instance_t g_pNDI_recv = nullptr;
static std::thread g_receiverThread;
static std::thread g_discoveryThread;
static std::atomic<bool> g_isRunning(false);
static std::atomic<bool> g_discoveryRunning(false);
static std::mutex g_mutex;
static std::mutex g_receiverMutex;
static bool g_ndi_initialized = false;

// ULTRA-FAST CACHE
static std::map<std::string, std::vector<std::string>> g_cachedDevices;
static std::mutex g_cacheMutex;
static std::atomic<uint32_t> g_cachedSourceCount(0);
static std::atomic<bool> g_forceImmediateScan(false);

static void fourcc_to_string(uint32_t fourcc, char out[5]) {
    out[0] = (char)(fourcc & 0xFF);
    out[1] = (char)((fourcc >> 8) & 0xFF);
    out[2] = (char)((fourcc >> 16) & 0xFF);
    out[3] = (char)((fourcc >> 24) & 0xFF);
    out[4] = '\0';
}

static NDIlib_recv_bandwidth_e getBandwidthForQuality(int quality) {
    switch(quality) {
        case 360: return NDIlib_recv_bandwidth_lowest;
        case 720: return NDIlib_recv_bandwidth_highest;
        case 1080: return NDIlib_recv_bandwidth_highest;
        default: return NDIlib_recv_bandwidth_highest;
    }
}

/**
 * ULTRA-AGGRESSIVE DISCOVERY
 * - Normal mode: 30ms interval (33 updates/sec)
 * - Force scan mode: 10ms interval (100 updates/sec)
 *
 * Strategi:
 * 1. Wait time minimal (10-30ms)
 * 2. Immediate update ke cache
 * 3. No filtering di discovery layer
 * 4. Support force immediate scan untuk warm-up
 */
static void continuousDiscoveryThread() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "=== ULTRA-FAST Discovery Started ===");

    while (g_discoveryRunning) {
        if (!g_pNDI_find) {
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
            continue;
        }

        // Adaptive wait time
        uint32_t waitTimeMs = g_forceImmediateScan ? 10 : 30;
        NDIlib_find_wait_for_sources(g_pNDI_find, waitTimeMs);

        uint32_t no_sources = 0;
        const NDIlib_source_t* p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

        {
            std::lock_guard<std::mutex> lock(g_cacheMutex);

            g_cachedDevices.clear();

            if (p_sources && no_sources > 0) {
                for (uint32_t i = 0; i < no_sources; i++) {
                    std::string fullName = p_sources[i].p_ndi_name ? p_sources[i].p_ndi_name : "";

                    std::string deviceName;
                    size_t openParen = fullName.find('(');
                    size_t closeParen = fullName.find(')');

                    if (openParen != std::string::npos && closeParen != std::string::npos && closeParen > openParen) {
                        deviceName = fullName.substr(0, openParen);
                        while (!deviceName.empty() && deviceName.back() == ' ') {
                            deviceName.pop_back();
                        }
                    } else {
                        deviceName = fullName;
                    }

                    if (deviceName.empty()) {
                        deviceName = "Unknown Device";
                    }

                    g_cachedDevices[deviceName].push_back(fullName);
                }

                g_cachedSourceCount = no_sources;

                if (g_forceImmediateScan) {
                    __android_log_print(ANDROID_LOG_DEBUG, TAG,
                        "⚡ FORCE SCAN: %d sources, %zu devices",
                        no_sources, g_cachedDevices.size());
                }
            } else {
                g_cachedSourceCount = 0;
            }
        }

        // Ultra-fast refresh: 30ms normal, 10ms force mode
        std::this_thread::sleep_for(std::chrono::milliseconds(waitTimeMs));
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Discovery stopped");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

/**
 * INITIALIZE - PERSISTENT FINDER
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_NDIManager_initializeNDIFinder(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "=== Init Persistent Finder ===");

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ndi_initialized) {
        if (!NDIlib_initialize()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "NDI init FAILED");
            return JNI_FALSE;
        }
        g_ndi_initialized = true;
        __android_log_print(ANDROID_LOG_INFO, TAG, "✓ NDI lib initialized");
    }

    if (!g_pNDI_find) {
        NDIlib_find_create_t find_create;
        find_create.show_local_sources = true;
        find_create.p_groups = nullptr;
        find_create.p_extra_ips = nullptr;

        g_pNDI_find = NDIlib_find_create_v2(&find_create);
        if (!g_pNDI_find) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Finder create FAILED");
            return JNI_FALSE;
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Finder created");
    }

    return JNI_TRUE;
}

/**
 * START CONTINUOUS DISCOVERY
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_NDIManager_startContinuousDiscovery(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "Starting discovery...");

    if (g_discoveryRunning) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Already running");
        return;
    }

    g_discoveryRunning = true;

    if (g_discoveryThread.joinable()) {
        g_discoveryThread.join();
    }

    g_discoveryThread = std::thread(continuousDiscoveryThread);

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Discovery started");
}

/**
 * FORCE IMMEDIATE SCAN
 * Untuk warm-up: scan super cepat 10x dalam 200ms
 * Return: jumlah sources yang ditemukan
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapplication_NDIManager_forceImmediateScan(
        JNIEnv* env,
        jobject /* this */) {

    if (!g_pNDI_find || !g_discoveryRunning) {
        return 0;
    }

    // Aktifkan force mode (10ms interval)
    g_forceImmediateScan = true;

    // Wait sebentar untuk discovery thread update
    std::this_thread::sleep_for(std::chrono::milliseconds(15));

    // Baca hasil
    uint32_t count = g_cachedSourceCount.load();

    // Kembali ke normal mode jika sudah ada sources
    if (count > 0) {
        g_forceImmediateScan = false;
    }

    return (jint)count;
}

/**
 * GET DEVICES - INSTANT READ
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_myapplication_NDIManager_getNDIDevicesAndSources(
        JNIEnv* env,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_cacheMutex);

    std::vector<std::string> result;

    for (const auto& devicePair : g_cachedDevices) {
        const std::string& deviceName = devicePair.first;
        const std::vector<std::string>& sources = devicePair.second;

        if (sources.empty()) continue;

        std::string deviceData = deviceName;
        for (const auto& source : sources) {
            deviceData += "|||" + source;
        }
        result.push_back(deviceData);
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray javaArray = env->NewObjectArray((jsize)result.size(), stringClass, nullptr);

    for (size_t i = 0; i < result.size(); i++) {
        jstring jStr = env->NewStringUTF(result[i].c_str());
        env->SetObjectArrayElement(javaArray, (jsize)i, jStr);
        env->DeleteLocalRef(jStr);
    }

    return javaArray;
}

/**
 * CONNECT TO SOURCE
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_MainActivity_connectToNDISource(
        JNIEnv* env,
        jobject /* this */,
        jstring jSourceName,
        jobject jSurface,
        jint quality) {

    const char* sourceNameStr = env->GetStringUTFChars(jSourceName, nullptr);
    std::string sourceName(sourceNameStr);
    env->ReleaseStringUTFChars(jSourceName, sourceNameStr);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Connect: %s (%dp)",
        sourceName.c_str(), quality);

    std::lock_guard<std::mutex> lock(g_receiverMutex);

    // Stop existing
    g_isRunning = false;
    if (g_receiverThread.joinable()) {
        g_receiverThread.join();
    }
    if (g_pNDI_recv) {
        NDIlib_recv_destroy(g_pNDI_recv);
        g_pNDI_recv = nullptr;
    }

    if (!g_pNDI_find) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Finder not ready");
        return JNI_FALSE;
    }

    uint32_t no_sources = 0;
    const NDIlib_source_t* p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

    const NDIlib_source_t* targetSource = nullptr;
    for (uint32_t i = 0; i < no_sources; i++) {
        std::string currentName = p_sources[i].p_ndi_name ? p_sources[i].p_ndi_name : "";
        if (currentName == sourceName) {
            targetSource = &p_sources[i];
            break;
        }
    }

    if (!targetSource) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Source not found");
        return JNI_FALSE;
    }

    NDIlib_recv_create_v3_t recv_create;
    recv_create.source_to_connect_to = *targetSource;
    recv_create.color_format = NDIlib_recv_color_format_BGRX_BGRA;
    recv_create.bandwidth = getBandwidthForQuality(quality);
    recv_create.allow_video_fields = false;
    recv_create.p_ndi_recv_name = "Android NDI Monitor";

    g_pNDI_recv = NDIlib_recv_create_v3(&recv_create);
    if (!g_pNDI_recv) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Receiver create failed");
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Connected");

    ANativeWindow* window = ANativeWindow_fromSurface(env, jSurface);
    if (!window) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Window failed");
        NDIlib_recv_destroy(g_pNDI_recv);
        g_pNDI_recv = nullptr;
        return JNI_FALSE;
    }

    g_isRunning = true;

    g_receiverThread = std::thread([window]() {
        NDIlib_recv_instance_t pNDI_recv = g_pNDI_recv;
        if (!pNDI_recv) {
            ANativeWindow_release(window);
            return;
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "Receiver thread start");

        NDIlib_video_frame_v2_t video_frame;
        NDIlib_audio_frame_v3_t audio_frame;
        NDIlib_metadata_frame_t metadata_frame;

        while (g_isRunning) {
            NDIlib_frame_type_e frame_type = NDIlib_recv_capture_v3(
                pNDI_recv, &video_frame, &audio_frame, &metadata_frame, 1000);

            switch (frame_type) {
                case NDIlib_frame_type_video: {
                    char fourccStr[5];
                    fourcc_to_string(video_frame.FourCC, fourccStr);

                    ANativeWindow_setBuffersGeometry(window,
                        video_frame.xres, video_frame.yres,
                        AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

                    ANativeWindow_Buffer buffer;
                    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) {
                        NDIlib_recv_free_video_v2(pNDI_recv, &video_frame);
                        continue;
                    }

                    uint8_t* dst = (uint8_t*)buffer.bits;
                    uint8_t* src = (uint8_t*)video_frame.p_data;
                    int height = video_frame.yres;
                    int width = video_frame.xres;
                    int dstStrideBytes = buffer.stride * 4;
                    int srcStride = video_frame.line_stride_in_bytes;

                    bool handled = false;

                    // BGRA/BGRX
                    if (video_frame.FourCC == NDIlib_FourCC_video_type_BGRA ||
                        video_frame.FourCC == NDIlib_FourCC_video_type_BGRX) {
                        for (int y = 0; y < height; y++) {
                            uint8_t* srow = src + y * srcStride;
                            uint8_t* drow = dst + y * dstStrideBytes;
                            for (int x = 0; x < width; x++) {
                                drow[x*4+0] = srow[x*4+2]; // R
                                drow[x*4+1] = srow[x*4+1]; // G
                                drow[x*4+2] = srow[x*4+0]; // B
                                drow[x*4+3] = (video_frame.FourCC == NDIlib_FourCC_video_type_BGRA) ?
                                    srow[x*4+3] : 0xFF;
                            }
                        }
                        handled = true;
                    }

                    // RGBA/RGBX
                    if (!handled && (video_frame.FourCC == NDIlib_FourCC_video_type_RGBA ||
                        video_frame.FourCC == NDIlib_FourCC_video_type_RGBX)) {
                        for (int y = 0; y < height; y++) {
                            memcpy(dst + y * dstStrideBytes, src + y * srcStride, width * 4);
                        }
                        handled = true;
                    }

                    // UYVY/UYVA
                    if (!handled && (video_frame.FourCC == NDIlib_FourCC_video_type_UYVY ||
                        video_frame.FourCC == NDIlib_FourCC_video_type_UYVA)) {
                        auto clamp = [](int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; };
                        for (int y = 0; y < height; y++) {
                            uint8_t* srow = src + y * srcStride;
                            uint8_t* drow = dst + y * dstStrideBytes;
                            for (int x = 0; x < width; x += 2) {
                                int U = srow[x*2+0] - 128;
                                int Y0 = srow[x*2+1] - 16;
                                int V = srow[x*2+2] - 128;
                                int Y1 = srow[x*2+3] - 16;
                                if (Y0 < 0) Y0 = 0;
                                if (Y1 < 0) Y1 = 0;

                                int C1 = 298 * Y0;
                                drow[(x+0)*4+0] = clamp((C1 + 409*V + 128) >> 8);
                                drow[(x+0)*4+1] = clamp((C1 - 100*U - 208*V + 128) >> 8);
                                drow[(x+0)*4+2] = clamp((C1 + 516*U + 128) >> 8);
                                drow[(x+0)*4+3] = 0xFF;

                                int C2 = 298 * Y1;
                                drow[(x+1)*4+0] = clamp((C2 + 409*V + 128) >> 8);
                                drow[(x+1)*4+1] = clamp((C2 - 100*U - 208*V + 128) >> 8);
                                drow[(x+1)*4+2] = clamp((C2 + 516*U + 128) >> 8);
                                drow[(x+1)*4+3] = 0xFF;
                            }
                        }
                        handled = true;
                    }

                    // NV12
                    if (!handled && video_frame.FourCC == NDIlib_FourCC_video_type_NV12) {
                        uint8_t* yPlane = src;
                        uint8_t* uvPlane = src + (height * srcStride);
                        auto clamp = [](int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; };
                        for (int y = 0; y < height; y++) {
                            uint8_t* drow = dst + y * dstStrideBytes;
                            for (int x = 0; x < width; x++) {
                                int yVal = yPlane[y*srcStride + x] - 16;
                                int uVal = uvPlane[(y/2)*(srcStride/2) + (x/2)*2+0] - 128;
                                int vVal = uvPlane[(y/2)*(srcStride/2) + (x/2)*2+1] - 128;
                                if (yVal < 0) yVal = 0;
                                int C = yVal * 298;
                                drow[x*4+0] = clamp((C + 409*vVal + 128) >> 8);
                                drow[x*4+1] = clamp((C - 100*uVal - 208*vVal + 128) >> 8);
                                drow[x*4+2] = clamp((C + 516*uVal + 128) >> 8);
                                drow[x*4+3] = 0xFF;
                            }
                        }
                        handled = true;
                    }

                    ANativeWindow_unlockAndPost(window);
                    NDIlib_recv_free_video_v2(pNDI_recv, &video_frame);
                    break;
                }
                case NDIlib_frame_type_audio:
                    NDIlib_recv_free_audio_v3(pNDI_recv, &audio_frame);
                    break;
                case NDIlib_frame_type_metadata:
                    NDIlib_recv_free_metadata(pNDI_recv, &metadata_frame);
                    break;
                case NDIlib_frame_type_error:
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "Connection lost");
                    g_isRunning = false;
                    break;
                default:
                    break;
            }
        }

        ANativeWindow_release(window);
    });

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_stopNDIReceiver(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "Stop receiver");

    std::lock_guard<std::mutex> lock(g_receiverMutex);

    g_isRunning = false;
    if (g_receiverThread.joinable()) {
        g_receiverThread.join();
    }
    if (g_pNDI_recv) {
        NDIlib_recv_destroy(g_pNDI_recv);
        g_pNDI_recv = nullptr;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Stopped");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_NDIManager_stopContinuousDiscovery(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "Stop discovery");

    g_discoveryRunning = false;

    if (g_discoveryThread.joinable()) {
        g_discoveryThread.join();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_NDIManager_cleanupNDIFinder(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "Cleanup");

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_pNDI_find) {
        NDIlib_find_destroy(g_pNDI_find);
        g_pNDI_find = nullptr;
    }

    {
        std::lock_guard<std::mutex> cacheLock(g_cacheMutex);
        g_cachedDevices.clear();
        g_cachedSourceCount = 0;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Clean");
}