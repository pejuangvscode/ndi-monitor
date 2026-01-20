#include <jni.h>
#include <string>
#include <Processing.NDI.Lib.h>
#include <thread>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <cinttypes>

static const char* TAG = "NDIReceiver";

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

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_startNDIReceiver(
        JNIEnv* env,
        jobject /* this */,
        jobject surface) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "startNDIReceiver called");

    // Get ANativeWindow from Surface
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ANativeWindow_fromSurface returned NULL");
        return;
    }

    // Initialize NDI
    if (!NDIlib_initialize()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_initialize failed");
        // Handle error
        ANativeWindow_release(window);
        return;
    }

    // Create finder
    NDIlib_find_instance_t pNDI_find = NDIlib_find_create_v2();

    if (!pNDI_find) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_find_create_v2 returned NULL");
        ANativeWindow_release(window);
        return;
    }

    // Wait for sources with longer timeout and more attempts
    uint32_t no_sources = 0;
    const NDIlib_source_t* p_sources = NULL;

    // Increase attempts to 30 (30 seconds total) to allow more time for discovery
    int waitCount = 0;
    while ((!p_sources || no_sources == 0) && waitCount < 30) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Waiting for NDI sources... (attempt %d/30)", waitCount + 1);
        NDIlib_find_wait_for_sources(pNDI_find, 2000); // Wait 2 seconds per attempt
        p_sources = NDIlib_find_get_current_sources(pNDI_find, &no_sources);
        __android_log_print(ANDROID_LOG_INFO, TAG, "Found %d sources", no_sources);
        if (p_sources && no_sources > 0) {
            for (uint32_t i = 0; i < no_sources; i++) {
                __android_log_print(ANDROID_LOG_INFO, TAG, "  Source %d: %s (%s)", i, p_sources[i].p_ndi_name, p_sources[i].p_url_address);
            }
            break;
        }
        waitCount++;
    }

    if (!p_sources || no_sources == 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "No NDI sources found after 60 seconds - cannot continue");
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Troubleshooting: 1) Check both devices on same subnet 2) Disable firewall on laptop 3) Check NDI sender is running 4) Try restarting NDI sender");
        // Clean up finder
        NDIlib_find_destroy(pNDI_find);
        ANativeWindow_release(window);
        return;
    }

    // Create receiver with explicit settings requesting BGRA/BGRX formats
    NDIlib_recv_create_v3_t recv_settings;
    recv_settings.source_to_connect_to = p_sources[0];
    recv_settings.color_format = NDIlib_recv_color_format_BGRX_BGRA; // prefer BGRA/BGRX output
    recv_settings.bandwidth = NDIlib_recv_bandwidth_highest; // use highest for quality
    recv_settings.allow_video_fields = true;
    recv_settings.p_ndi_recv_name = "AndroidNDIReceiver";

    NDIlib_recv_instance_t pNDI_recv = NDIlib_recv_create_v3(&recv_settings);

    if (!pNDI_recv) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "NDIlib_recv_create_v3 failed");
        NDIlib_find_destroy(pNDI_find);
        ANativeWindow_release(window);
        return;
    }

    // Connect to source
    NDIlib_recv_connect(pNDI_recv, p_sources);

    // Destroy finder (we no longer need it)
    NDIlib_find_destroy(pNDI_find);

    // Start receiver thread
    std::thread receiverThread([pNDI_recv, window]() {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Receiver thread started");

        while (true) {
            NDIlib_video_frame_v2_t video_frame;
            NDIlib_audio_frame_v3_t audio_frame;
            NDIlib_metadata_frame_t metadata_frame;

            NDIlib_frame_type_e frameType = NDIlib_recv_capture_v3(pNDI_recv, &video_frame, &audio_frame, &metadata_frame, 1000);
            switch (frameType) {
                case NDIlib_frame_type_video: {
                    char fourccStr[5];
                    fourcc_to_string((uint32_t)video_frame.FourCC, fourccStr);
                    __android_log_print(ANDROID_LOG_DEBUG, TAG, "Received video frame: %dx%d, FourCC=%s (0x%08" PRIx32 ") stride=%d", video_frame.xres, video_frame.yres, fourccStr, (uint32_t)video_frame.FourCC, video_frame.line_stride_in_bytes);

                    if (window) {
                        // Set buffers geometry. Try to use RGBA_8888 which Android supports widely.
                        ANativeWindow_setBuffersGeometry(window, video_frame.xres, video_frame.yres, WINDOW_FORMAT_RGBA_8888);

                        ANativeWindow_Buffer buffer;
                        int lockRes = ANativeWindow_lock(window, &buffer, nullptr);
                        if (lockRes != 0) {
                            __android_log_print(ANDROID_LOG_ERROR, TAG, "ANativeWindow_lock failed: %d", lockRes);
                            NDIlib_recv_free_video_v2(pNDI_recv, &video_frame);
                            continue;
                        }

                        uint8_t* dst = (uint8_t*)buffer.bits;
                        uint8_t* src = (uint8_t*)video_frame.p_data;
                        int height = video_frame.yres;
                        int width = video_frame.xres;
                        int dstStrideBytes = buffer.stride * 4; // stride pixels * 4 bytes
                        int srcStride = video_frame.line_stride_in_bytes;

                        bool handled = false;

                        // Handle BGRA/BGRX (src B G R A or B G R X)
                        if (video_frame.FourCC == NDIlib_FourCC_video_type_BGRA || video_frame.FourCC == NDIlib_FourCC_video_type_BGRX) {
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

                        // Handle RGBA/RGBX: likely already in correct order (R G B A)
                        if (!handled && (video_frame.FourCC == NDIlib_FourCC_video_type_RGBA || video_frame.FourCC == NDIlib_FourCC_video_type_RGBX)) {
                            // copy line by line respecting strides
                            for (int y = 0; y < height; y++) {
                                uint8_t* srow = src + y * srcStride;
                                uint8_t* drow = dst + y * dstStrideBytes;
                                memcpy(drow, srow, (size_t) (width * 4));
                            }
                            handled = true;
                        }

                        // Handle UYVY / UYVA packed YUV 4:2:2 -> convert to RGBA
                        if (!handled && (video_frame.FourCC == NDIlib_FourCC_video_type_UYVY || video_frame.FourCC == NDIlib_FourCC_video_type_UYVA)) {
                            // src layout per 4 bytes: U Y0 V Y1 -> two pixels
                            auto clamp = [](int v) -> uint8_t { if (v < 0) return 0; if (v > 255) return 255; return (uint8_t)v; };
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

                                    // write pixel x
                                    drow[(x+0)*4 + 0] = clamp(R1);
                                    drow[(x+0)*4 + 1] = clamp(G1);
                                    drow[(x+0)*4 + 2] = clamp(B1);
                                    drow[(x+0)*4 + 3] = 0xFF;

                                    // write pixel x+1
                                    drow[(x+1)*4 + 0] = clamp(R2);
                                    drow[(x+1)*4 + 1] = clamp(G2);
                                    drow[(x+1)*4 + 2] = clamp(B2);
                                    drow[(x+1)*4 + 3] = 0xFF;
                                }
                            }
                            handled = true;
                        }

                        // Handle NV12 planar YUV 4:2:0 -> convert to RGBA
                        if (!handled && video_frame.FourCC == NDIlib_FourCC_video_type_NV12) {
                            // NV12: Y plane (full res), UV interleaved (half res)
                            uint8_t* yPlane = src;
                            uint8_t* uvPlane = src + (height * srcStride); // UV starts after Y
                            auto clamp = [](int v) -> uint8_t { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; };
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
                            __android_log_print(ANDROID_LOG_WARN, TAG, "Unsupported FourCC %s (0x%08" PRIx32 ") - skipping frame", fourccStr, (uint32_t)video_frame.FourCC);
                        }

                        ANativeWindow_unlockAndPost(window);
                        NDIlib_recv_free_video_v2(pNDI_recv, &video_frame);
                    }
                    break;
                }
                case NDIlib_frame_type_audio:
                    __android_log_print(ANDROID_LOG_DEBUG, TAG, "Received audio frame");
                    NDIlib_recv_free_audio_v3(pNDI_recv, &audio_frame);
                    break;
                case NDIlib_frame_type_metadata:
                    __android_log_print(ANDROID_LOG_DEBUG, TAG, "Received metadata frame");
                    NDIlib_recv_free_metadata(pNDI_recv, &metadata_frame);
                    break;
                case NDIlib_frame_type_none:
                    // no data this cycle
                    break;
                case NDIlib_frame_type_error:
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "NDI recv error (connection lost?) - exiting thread");
                    // release window and exit thread
                    ANativeWindow_release(window);
                    return;
                default:
                    break;
            }
        }
    });

    receiverThread.detach();

    // Note: In a real app, store thread handle to join on destroy; keep window reference until exit
}
