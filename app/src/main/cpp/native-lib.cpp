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

static const char* TAG = "NDIReceiver";

// Global variables untuk menyimpan finder dan receiver
static NDIlib_find_instance_t g_pNDI_find = nullptr;
static NDIlib_recv_instance_t g_pNDI_recv = nullptr;
static std::thread g_receiverThread;
static bool g_isRunning = false;
static std::mutex g_mutex;

static void fourcc_to_string(uint32_t fourcc, char out[5]) {
    out[0] = (char)(fourcc & 0xFF);
    out[1] = (char)((fourcc >> 8) & 0xFF);
    out[2] = (char)((fourcc >> 16) & 0xFF);
    out[3] = (char)((fourcc >> 24) & 0xFF);
    out[4] = '\0';
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

// Fungsi untuk mendapatkan daftar devices dan sources
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_myapplication_MainActivity_getNDIDevicesAndSources(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "getNDIDevicesAndSources called");

    // Initialize NDI if not already
    if (!NDIlib_initialize()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_initialize failed");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    // Create finder if not exists
    if (!g_pNDI_find) {
        g_pNDI_find = NDIlib_find_create_v2();
        if (!g_pNDI_find) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_find_create_v2 failed");
            return nullptr;
        }
    }

    // Enhanced discovery: Try multiple times with increasing timeout for better consistency
    uint32_t no_sources = 0;
    const NDIlib_source_t* p_sources = nullptr;

    // Try up to 5 times with 1 second wait each
    for (int attempt = 0; attempt < 5 && (!p_sources || no_sources == 0); attempt++) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Discovery attempt %d/5", attempt + 1);

        // Wait for sources - increase timeout on each attempt
        NDIlib_find_wait_for_sources(g_pNDI_find, 1000);
        p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

        __android_log_print(ANDROID_LOG_INFO, TAG, "Attempt %d: Found %d sources", attempt + 1, no_sources);

        if (p_sources && no_sources > 0) {
            break; // Found sources, exit loop
        }
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Final result: Found %d NDI sources", no_sources);

    if (!p_sources || no_sources == 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "No NDI sources found after all attempts");
        // Return empty array
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    // Group sources by device name
    // Format: "DEVICE_NAME|||SOURCE_1|||SOURCE_2|||SOURCE_3"
    std::map<std::string, std::vector<std::string>> deviceSourcesMap;

    for (uint32_t i = 0; i < no_sources; i++) {
        std::string fullName = p_sources[i].p_ndi_name ? p_sources[i].p_ndi_name : "";

        __android_log_print(ANDROID_LOG_INFO, TAG, "Source %d: %s (%s)",
            i, fullName.c_str(),
            p_sources[i].p_url_address ? p_sources[i].p_url_address : "no url");

        // Parse device name and source name from format "DEVICE (SOURCE)"
        std::string deviceName;
        std::string sourceName;

        size_t openParen = fullName.find('(');
        size_t closeParen = fullName.find(')');

        if (openParen != std::string::npos && closeParen != std::string::npos && closeParen > openParen) {
            // Extract device name (before parenthesis, trim spaces)
            deviceName = fullName.substr(0, openParen);
            // Trim trailing spaces
            while (!deviceName.empty() && deviceName.back() == ' ') {
                deviceName.pop_back();
            }

            // Extract source name (inside parenthesis)
            sourceName = fullName.substr(openParen + 1, closeParen - openParen - 1);
        } else {
            // No parenthesis format, use full name as both device and source
            deviceName = fullName;
            sourceName = fullName;
        }

        if (deviceName.empty()) {
            deviceName = "Unknown Device";
        }
        if (sourceName.empty()) {
            sourceName = "Unknown Source";
        }

        // Store full NDI name for later use
        deviceSourcesMap[deviceName].push_back(fullName);

        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Parsed - Device: %s, Source: %s",
            deviceName.c_str(), sourceName.c_str());
    }

    // Create result array
    // Each element format: "DEVICE_NAME|||SOURCE_1|||SOURCE_2|||..."
    int numDevices = deviceSourcesMap.size();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(numDevices, stringClass, nullptr);

    int deviceIndex = 0;
    for (const auto& entry : deviceSourcesMap) {
        std::string deviceData = entry.first;

        for (const auto& source : entry.second) {
            deviceData += "|||" + source;
        }

        jstring jDeviceData = env->NewStringUTF(deviceData.c_str());
        env->SetObjectArrayElement(result, deviceIndex, jDeviceData);
        env->DeleteLocalRef(jDeviceData);

        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Device %d data: %s",
            deviceIndex, deviceData.c_str());

        deviceIndex++;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Returning %d devices", numDevices);
    return result;
}

// Fungsi untuk connect ke source tertentu
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_MainActivity_connectToNDISource(
        JNIEnv* env,
        jobject /* this */,
        jstring sourceFullName,
        jobject surface) {

    const char* sourceName = env->GetStringUTFChars(sourceFullName, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "connectToNDISource called for: %s", sourceName);

    // Stop existing receiver if running
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_isRunning) {
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

    // Get ANativeWindow from Surface
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ANativeWindow_fromSurface returned NULL");
        env->ReleaseStringUTFChars(sourceFullName, sourceName);
        return JNI_FALSE;
    }

    // Find the specific source
    uint32_t no_sources = 0;
    const NDIlib_source_t* p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

    const NDIlib_source_t* selectedSource = nullptr;
    for (uint32_t i = 0; i < no_sources; i++) {
        if (strcmp(p_sources[i].p_ndi_name, sourceName) == 0) {
            selectedSource = &p_sources[i];
            __android_log_print(ANDROID_LOG_INFO, TAG, "Found matching source: %s", sourceName);
            break;
        }
    }

    env->ReleaseStringUTFChars(sourceFullName, sourceName);

    if (!selectedSource) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Source not found");
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    // Create receiver
    NDIlib_recv_create_v3_t recv_settings;
    recv_settings.source_to_connect_to = *selectedSource;
    recv_settings.color_format = NDIlib_recv_color_format_BGRX_BGRA;
    recv_settings.bandwidth = NDIlib_recv_bandwidth_highest;
    recv_settings.allow_video_fields = true;
    recv_settings.p_ndi_recv_name = "AndroidNDIReceiver";

    g_pNDI_recv = NDIlib_recv_create_v3(&recv_settings);

    if (!g_pNDI_recv) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_recv_create_v3 failed");
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    // Connect to source
    NDIlib_recv_connect(g_pNDI_recv, selectedSource);

    // Start receiver thread
    g_isRunning = true;
    g_receiverThread = std::thread([window]() {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Receiver thread started");

        while (g_isRunning) {
            NDIlib_video_frame_v2_t video_frame;
            NDIlib_audio_frame_v3_t audio_frame;
            NDIlib_metadata_frame_t metadata_frame;

            NDIlib_frame_type_e frameType = NDIlib_recv_capture_v3(g_pNDI_recv, &video_frame, &audio_frame, &metadata_frame, 1000);

            if (!g_isRunning) break;

            switch (frameType) {
                case NDIlib_frame_type_video: {
                    char fourccStr[5];
                    fourcc_to_string((uint32_t)video_frame.FourCC, fourccStr);
                    __android_log_print(ANDROID_LOG_DEBUG, TAG, "Received video frame: %dx%d, FourCC=%s",
                        video_frame.xres, video_frame.yres, fourccStr);

                    if (window && g_isRunning) {
                        ANativeWindow_setBuffersGeometry(window, video_frame.xres, video_frame.yres, WINDOW_FORMAT_RGBA_8888);

                        ANativeWindow_Buffer buffer;
                        int lockRes = ANativeWindow_lock(window, &buffer, nullptr);
                        if (lockRes != 0) {
                            __android_log_print(ANDROID_LOG_ERROR, TAG, "ANativeWindow_lock failed: %d", lockRes);
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

                        // Handle BGRA/BGRX
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

                        // Handle RGBA/RGBX
                        if (!handled && (video_frame.FourCC == NDIlib_FourCC_video_type_RGBA ||
                                        video_frame.FourCC == NDIlib_FourCC_video_type_RGBX)) {
                            for (int y = 0; y < height; y++) {
                                uint8_t* srow = src + y * srcStride;
                                uint8_t* drow = dst + y * dstStrideBytes;
                                memcpy(drow, srow, (size_t)(width * 4));
                            }
                            handled = true;
                        }

                        // Handle UYVY/UYVA
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

                        // Handle NV12
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

                        if (!handled) {
                            __android_log_print(ANDROID_LOG_WARN, TAG, "Unsupported FourCC %s", fourccStr);
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
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "NDI recv error - exiting thread");
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

// Legacy function - kept for compatibility
extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_startNDIReceiver(
        JNIEnv* env,
        jobject thiz,
        jobject surface) {
    // This now does nothing - use getNDIDevicesAndSources + connectToNDISource instead
    __android_log_print(ANDROID_LOG_INFO, TAG, "startNDIReceiver (legacy) called - use new methods");
}

// Cleanup function
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

    if (g_pNDI_find) {
        NDIlib_find_destroy(g_pNDI_find);
        g_pNDI_find = nullptr;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "NDI cleanup completed");
}