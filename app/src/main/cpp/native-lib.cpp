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

static const char* TAG = "NDIReceiver";

// Global variables
static NDIlib_find_instance_t g_pNDI_find = nullptr;
static NDIlib_recv_instance_t g_pNDI_recv = nullptr;
static std::thread g_receiverThread;
static std::thread g_discoveryThread;
static bool g_isRunning = false;
static std::atomic<bool> g_discoveryRunning(false);
static std::mutex g_mutex;
static bool g_ndi_initialized = false;

// Cache for fast access
static std::map<std::string, std::vector<std::string>> g_cachedDevices;
static std::mutex g_cacheMutex;
static std::atomic<uint32_t> g_cachedSourceCount(0);

static void fourcc_to_string(uint32_t fourcc, char out[5]) {
    out[0] = (char)(fourcc & 0xFF);
    out[1] = (char)((fourcc >> 8) & 0xFF);
    out[2] = (char)((fourcc >> 16) & 0xFF);
    out[3] = (char)((fourcc >> 24) & 0xFF);
    out[4] = '\0';
}

static NDIlib_recv_bandwidth_e getBandwidthForQuality(int quality) {
    switch(quality) {
        case 360:
            return NDIlib_recv_bandwidth_lowest;
        case 720:
            return NDIlib_recv_bandwidth_highest;
        case 1080:
            return NDIlib_recv_bandwidth_highest;
        default:
            return NDIlib_recv_bandwidth_highest;
    }
}

// Background discovery thread for continuous scanning
static void continuousDiscoveryThread() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Continuous discovery thread started");

    while (g_discoveryRunning) {
        if (!g_pNDI_find) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        // Very short wait for ultra-fast updates
        NDIlib_find_wait_for_sources(g_pNDI_find, 50);

        uint32_t no_sources = 0;
        const NDIlib_source_t* p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

        if (p_sources && no_sources > 0) {
            std::lock_guard<std::mutex> lock(g_cacheMutex);
            g_cachedDevices.clear();

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

            if (no_sources > 0) {
                __android_log_print(ANDROID_LOG_DEBUG, TAG,
                    "Cache updated: %d sources, %zu devices",
                    no_sources, g_cachedDevices.size());
            }
        }

        // Ultra-fast refresh rate for instant detection
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Continuous discovery thread stopped");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_MainActivity_initializeNDIFinder(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "initializeNDIFinder called");

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ndi_initialized) {
        if (!NDIlib_initialize()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_initialize failed");
            return JNI_FALSE;
        }
        g_ndi_initialized = true;
        __android_log_print(ANDROID_LOG_INFO, TAG, "NDI library initialized successfully");
    }

    if (!g_pNDI_find) {
        NDIlib_find_create_t find_create;
        find_create.show_local_sources = true;
        find_create.p_groups = nullptr;
        find_create.p_extra_ips = nullptr;

        g_pNDI_find = NDIlib_find_create_v2(&find_create);
        if (!g_pNDI_find) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_find_create_v2 failed");
            return JNI_FALSE;
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "NDI finder created successfully");
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_startContinuousDiscovery(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "startContinuousDiscovery called");

    if (g_discoveryRunning) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Discovery already running");
        return;
    }

    g_discoveryRunning = true;

    // Start background discovery thread
    if (g_discoveryThread.joinable()) {
        g_discoveryThread.join();
    }

    g_discoveryThread = std::thread(continuousDiscoveryThread);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Continuous discovery thread launched");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_myapplication_MainActivity_getNDIDevicesAndSources(
        JNIEnv* env,
        jobject /* this */) {

    // Return from cache for instant access
    std::lock_guard<std::mutex> lock(g_cacheMutex);

    if (g_cachedDevices.empty()) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Cache empty, returning empty array");
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    int numDevices = g_cachedDevices.size();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(numDevices, stringClass, nullptr);

    int deviceIndex = 0;
    for (const auto& entry : g_cachedDevices) {
        std::string deviceData = entry.first;

        for (const auto& source : entry.second) {
            deviceData += "|||" + source;
        }

        jstring jDeviceData = env->NewStringUTF(deviceData.c_str());
        env->SetObjectArrayElement(result, deviceIndex, jDeviceData);
        env->DeleteLocalRef(jDeviceData);

        deviceIndex++;
    }

    __android_log_print(ANDROID_LOG_DEBUG, TAG, "Returning %d devices from cache", numDevices);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_MainActivity_connectToNDISource(
        JNIEnv* env,
        jobject /* this */,
        jstring sourceFullName,
        jobject surface,
        jint quality) {

    const char* sourceName = env->GetStringUTFChars(sourceFullName, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "connectToNDISource: %s, quality: %d", sourceName, quality);

    // Stop existing receiver if running
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_isRunning) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Stopping existing receiver");
            g_isRunning = false;
            if (g_receiverThread.joinable()) {
                g_receiverThread.join();
            }
            if (g_pNDI_recv) {
                NDIlib_recv_destroy(g_pNDI_recv);
                g_pNDI_recv = nullptr;
            }
        }
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get ANativeWindow");
        env->ReleaseStringUTFChars(sourceFullName, sourceName);
        return JNI_FALSE;
    }

    if (!g_ndi_initialized) {
        if (!NDIlib_initialize()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "NDI not initialized");
            ANativeWindow_release(window);
            env->ReleaseStringUTFChars(sourceFullName, sourceName);
            return JNI_FALSE;
        }
        g_ndi_initialized = true;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_pNDI_find) {
        NDIlib_find_create_t find_create;
        find_create.show_local_sources = true;
        find_create.p_groups = nullptr;
        find_create.p_extra_ips = nullptr;

        g_pNDI_find = NDIlib_find_create_v2(&find_create);
        if (!g_pNDI_find) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create finder");
            ANativeWindow_release(window);
            env->ReleaseStringUTFChars(sourceFullName, sourceName);
            return JNI_FALSE;
        }
    }

    // Quick search with short timeout since we have continuous discovery
    NDIlib_find_wait_for_sources(g_pNDI_find, 500);
    uint32_t no_sources = 0;
    const NDIlib_source_t* p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

    const NDIlib_source_t* target_source = nullptr;
    for (uint32_t i = 0; i < no_sources; i++) {
        if (strcmp(p_sources[i].p_ndi_name, sourceName) == 0) {
            target_source = &p_sources[i];
            __android_log_print(ANDROID_LOG_INFO, TAG, "Found target source");
            break;
        }
    }

    env->ReleaseStringUTFChars(sourceFullName, sourceName);

    if (!target_source) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Source not found");
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    NDIlib_recv_create_v3_t recv_create;
    recv_create.source_to_connect_to = *target_source;
    recv_create.color_format = NDIlib_recv_color_format_BGRX_BGRA;
    recv_create.bandwidth = getBandwidthForQuality((int)quality);
    recv_create.allow_video_fields = false;
    recv_create.p_ndi_recv_name = "Android NDI Monitor";

    __android_log_print(ANDROID_LOG_INFO, TAG, "Creating receiver with bandwidth: %d for %dp",
                       recv_create.bandwidth, quality);

    g_pNDI_recv = NDIlib_recv_create_v3(&recv_create);
    if (!g_pNDI_recv) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create receiver");
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    // Set tally
    NDIlib_tally_t tally;
    tally.on_program = true;
    tally.on_preview = false;
    NDIlib_recv_set_tally(g_pNDI_recv, &tally);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Starting receiver thread");

    g_isRunning = true;
    g_receiverThread = std::thread([window]() {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Receiver thread started");

        while (g_isRunning) {
            NDIlib_video_frame_v2_t video_frame;
            NDIlib_audio_frame_v3_t audio_frame;
            NDIlib_metadata_frame_t metadata_frame;

            NDIlib_frame_type_e frameType = NDIlib_recv_capture_v3(
                g_pNDI_recv,
                &video_frame,
                &audio_frame,
                &metadata_frame,
                100
            );

            switch (frameType) {
                case NDIlib_frame_type_video: {
                    if (window && g_isRunning) {
                        ANativeWindow_setBuffersGeometry(window, video_frame.xres, video_frame.yres, WINDOW_FORMAT_RGBA_8888);

                        ANativeWindow_Buffer buffer;
                        if (ANativeWindow_lock(window, &buffer, nullptr) != 0) {
                            NDIlib_recv_free_video_v2(g_pNDI_recv, &video_frame);
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
                                    uint8_t b = srow[x*4 + 0];
                                    uint8_t g = srow[x*4 + 1];
                                    uint8_t r = srow[x*4 + 2];
                                    uint8_t a = (video_frame.FourCC == NDIlib_FourCC_video_type_BGRA) ? srow[x*4 + 3] : 0xFF;
                                    drow[x*4 + 0] = r;
                                    drow[x*4 + 1] = g;
                                    drow[x*4 + 2] = b;
                                    drow[x*4 + 3] = a;
                                }
                            }
                            handled = true;
                        }

                        // RGBA/RGBX
                        if (!handled && (video_frame.FourCC == NDIlib_FourCC_video_type_RGBA ||
                                        video_frame.FourCC == NDIlib_FourCC_video_type_RGBX)) {
                            for (int y = 0; y < height; y++) {
                                uint8_t* srow = src + y * srcStride;
                                uint8_t* drow = dst + y * dstStrideBytes;
                                memcpy(drow, srow, (size_t)(width * 4));
                            }
                            handled = true;
                        }

                        // UYVY/UYVA
                        if (!handled && (video_frame.FourCC == NDIlib_FourCC_video_type_UYVY ||
                                        video_frame.FourCC == NDIlib_FourCC_video_type_UYVA)) {
                            auto clamp = [](int v) -> uint8_t {
                                return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v;
                            };
                            for (int y = 0; y < height; y++) {
                                uint8_t* srow = src + y * srcStride;
                                uint8_t* drow = dst + y * dstStrideBytes;
                                int srcIndex = 0;
                                for (int x = 0; x < width; x += 2, srcIndex += 4) {
                                    int U = (int)srow[srcIndex + 0] - 128;
                                    int Y0 = (int)srow[srcIndex + 1] - 16;
                                    int V = (int)srow[srcIndex + 2] - 128;
                                    int Y1 = (int)srow[srcIndex + 3] - 16;

                                    if (Y0 < 0) Y0 = 0;
                                    if (Y1 < 0) Y1 = 0;

                                    int C1 = 298 * Y0;
                                    int R1 = (C1 + 409 * V + 128) >> 8;
                                    int G1 = (C1 - 100 * U - 208 * V + 128) >> 8;
                                    int B1 = (C1 + 516 * U + 128) >> 8;

                                    int C2 = 298 * Y1;
                                    int R2 = (C2 + 409 * V + 128) >> 8;
                                    int G2 = (C2 - 100 * U - 208 * V + 128) >> 8;
                                    int B2 = (C2 + 516 * U + 128) >> 8;

                                    drow[(x+0)*4 + 0] = clamp(R1);
                                    drow[(x+0)*4 + 1] = clamp(G1);
                                    drow[(x+0)*4 + 2] = clamp(B1);
                                    drow[(x+0)*4 + 3] = 0xFF;

                                    drow[(x+1)*4 + 0] = clamp(R2);
                                    drow[(x+1)*4 + 1] = clamp(G2);
                                    drow[(x+1)*4 + 2] = clamp(B2);
                                    drow[(x+1)*4 + 3] = 0xFF;
                                }
                            }
                            handled = true;
                        }

                        // NV12
                        if (!handled && video_frame.FourCC == NDIlib_FourCC_video_type_NV12) {
                            uint8_t* yPlane = src;
                            uint8_t* uvPlane = src + (height * srcStride);
                            auto clamp = [](int v) -> uint8_t {
                                return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v;
                            };
                            for (int y = 0; y < height; y++) {
                                uint8_t* drow = dst + y * dstStrideBytes;
                                for (int x = 0; x < width; x++) {
                                    int yVal = yPlane[y * srcStride + x] - 16;
                                    int uVal = uvPlane[(y/2) * (srcStride/2) + (x/2)*2 + 0] - 128;
                                    int vVal = uvPlane[(y/2) * (srcStride/2) + (x/2)*2 + 1] - 128;
                                    if (yVal < 0) yVal = 0;
                                    int C = yVal * 298;
                                    int R = (C + 409 * vVal + 128) >> 8;
                                    int G = (C - 100 * uVal - 208 * vVal + 128) >> 8;
                                    int B = (C + 516 * uVal + 128) >> 8;
                                    drow[x*4 + 0] = clamp(R);
                                    drow[x*4 + 1] = clamp(G);
                                    drow[x*4 + 2] = clamp(B);
                                    drow[x*4 + 3] = 0xFF;
                                }
                            }
                            handled = true;
                        }

                        ANativeWindow_unlockAndPost(window);
                        NDIlib_recv_free_video_v2(g_pNDI_recv, &video_frame);
                    }
                    break;
                }
                case NDIlib_frame_type_audio:
                    NDIlib_recv_free_audio_v3(g_pNDI_recv, &audio_frame);
                    break;
                case NDIlib_frame_type_metadata:
                    NDIlib_recv_free_metadata(g_pNDI_recv, &metadata_frame);
                    break;
                case NDIlib_frame_type_error:
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "NDI recv error");
                    g_isRunning = false;
                    break;
                default:
                    break;
            }
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "Receiver thread exiting");
        ANativeWindow_release(window);
    });

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_startNDIReceiver(
        JNIEnv* env,
        jobject thiz,
        jobject surface) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "startNDIReceiver (legacy) - use new methods");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_stopNDIReceiver(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "stopNDIReceiver called");

    std::lock_guard<std::mutex> lock(g_mutex);

    g_isRunning = false;

    if (g_receiverThread.joinable()) {
        g_receiverThread.join();
    }

    if (g_pNDI_recv) {
        NDIlib_recv_destroy(g_pNDI_recv);
        g_pNDI_recv = nullptr;
    }

    // Stop discovery thread
    g_discoveryRunning = false;
    if (g_discoveryThread.joinable()) {
        g_discoveryThread.join();
    }

    // Clean up finder on app close
    if (g_pNDI_find) {
        NDIlib_find_destroy(g_pNDI_find);
        g_pNDI_find = nullptr;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "NDI cleanup completed");
}