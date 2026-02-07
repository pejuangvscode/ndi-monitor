#include <jni.h>
#include <string>
#include <Processing.NDI.Lib.h>
#include <thread>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <cinttypes>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstring>
#include <map>
#include <chrono>

static const char* TAG = "NDIReceiver";

// Global state
static std::atomic<bool> g_receiverRunning(false);
static std::thread g_receiverThread;
static std::mutex g_receiverMutex;
static ANativeWindow* g_window = nullptr;
static NDIlib_recv_instance_t g_pNDI_recv = nullptr;
static bool g_ndi_initialized = false;

// ============================================================================
// SOURCE CACHE FOR STABLE DETECTION
// ============================================================================
struct SourceCacheEntry {
    std::string name;
    std::chrono::steady_clock::time_point lastSeen;
    NDIlib_source_t source;
};

static std::map<std::string, SourceCacheEntry> g_sourceCache;
static std::mutex g_sourceCacheMutex;
static const int SOURCE_TIMEOUT_MS = 3000;  // 3 seconds grace period

// Persistent NDI finder (reuse instead of recreate)
static NDIlib_find_instance_t g_pNDI_find = nullptr;
static std::mutex g_finderMutex;

static void fourcc_to_string(uint32_t fourcc, char out[5]) {
    out[0] = (char)(fourcc & 0xFF);
    out[1] = (char)((fourcc >> 8) & 0xFF);
    out[2] = (char)((fourcc >> 16) & 0xFF);
    out[3] = (char)((fourcc >> 24) & 0xFF);
    out[4] = '\0';
}

// ============================================================================
// INITIALIZE PERSISTENT NDI FINDER
// ============================================================================
static bool initializePersistentFinder() {
    std::lock_guard<std::mutex> lock(g_finderMutex);

    if (g_pNDI_find != nullptr) {
        return true;  // Already initialized
    }

    // Initialize NDI if needed
    if (!g_ndi_initialized) {
        if (!NDIlib_initialize()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ NDI init failed");
            return false;
        }
        g_ndi_initialized = true;
        __android_log_print(ANDROID_LOG_INFO, TAG, "✓ NDI initialized");
    }

    // Create persistent finder
    NDIlib_find_create_t find_desc;
    find_desc.show_local_sources = true;
    find_desc.p_groups = nullptr;
    find_desc.p_extra_ips = nullptr;

    g_pNDI_find = NDIlib_find_create_v2(&find_desc);

    if (!g_pNDI_find) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ Persistent finder creation failed");
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Persistent NDI finder created");
    return true;
}

// ============================================================================
// UPDATE SOURCE CACHE
// ============================================================================
static void updateSourceCache(const NDIlib_source_t* sources, uint32_t count) {
    std::lock_guard<std::mutex> lock(g_sourceCacheMutex);
    auto now = std::chrono::steady_clock::now();

    // Update existing sources and add new ones
    for (uint32_t i = 0; i < count; i++) {
        std::string sourceName(sources[i].p_ndi_name);

        if (g_sourceCache.find(sourceName) != g_sourceCache.end()) {
            // Update existing
            g_sourceCache[sourceName].lastSeen = now;
        } else {
            // Add new source
            SourceCacheEntry entry;
            entry.name = sourceName;
            entry.lastSeen = now;
            entry.source = sources[i];
            g_sourceCache[sourceName] = entry;

            __android_log_print(ANDROID_LOG_INFO, TAG, "➕ New source cached: %s",
                sourceName.c_str());
        }
    }
}

// ============================================================================
// GET VALID SOURCES FROM CACHE (with timeout)
// ============================================================================
static std::vector<SourceCacheEntry> getValidCachedSources() {
    std::lock_guard<std::mutex> lock(g_sourceCacheMutex);
    std::vector<SourceCacheEntry> validSources;
    auto now = std::chrono::steady_clock::now();

    // Collect valid sources
    for (auto it = g_sourceCache.begin(); it != g_sourceCache.end(); ) {
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - it->second.lastSeen).count();

        if (elapsed < SOURCE_TIMEOUT_MS) {
            // Source still valid
            validSources.push_back(it->second);
            ++it;
        } else {
            // Source timed out - remove from cache
            __android_log_print(ANDROID_LOG_INFO, TAG, "⏱️ Source timeout: %s (%.1fs)",
                it->second.name.c_str(), elapsed / 1000.0f);
            it = g_sourceCache.erase(it);
        }
    }

    return validSources;
}

// ============================================================================
// STABLE NDI SOURCE DISCOVERY
// ============================================================================

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_myapplication_NDIManager_findNDISources(
        JNIEnv* env,
        jobject /* this */) {

    // Initialize persistent finder if needed
    if (!initializePersistentFinder()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ Finder initialization failed");
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    std::lock_guard<std::mutex> lock(g_finderMutex);

    // Quick check for current sources (non-blocking)
    uint32_t no_sources = 0;
    const NDIlib_source_t* p_sources = nullptr;

    // Gentle network stimulus (tidak agresif)
    NDIlib_find_wait_for_sources(g_pNDI_find, 0);

    // Get current sources
    p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

    // Update cache with detected sources
    if (p_sources && no_sources > 0) {
        updateSourceCache(p_sources, no_sources);
    }

    // Get valid sources from cache (including timeout check)
    std::vector<SourceCacheEntry> validSources = getValidCachedSources();

    // Create result array from cached sources
    jobjectArray result;

    if (!validSources.empty()) {
        result = env->NewObjectArray(validSources.size(),
            env->FindClass("java/lang/String"), nullptr);

        for (size_t i = 0; i < validSources.size(); i++) {
            jstring jSourceName = env->NewStringUTF(validSources[i].name.c_str());
            env->SetObjectArrayElement(result, i, jSourceName);
            env->DeleteLocalRef(jSourceName);
        }

        // Log hanya setiap 20 kali untuk mengurangi spam
        static int logCounter = 0;
        if (++logCounter % 20 == 0) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "📹 Cached sources: %zu (fresh: %u)", validSources.size(), no_sources);
        }
    } else {
        result = env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);

        static int noSourceCounter = 0;
        if (++noSourceCounter % 10 == 0) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "⚠️ No valid cached sources");
        }
    }

    return result;
}

// ============================================================================
// CLEANUP FINDER (for shutdown)
// ============================================================================
static void cleanupPersistentFinder() {
    std::lock_guard<std::mutex> lock(g_finderMutex);

    if (g_pNDI_find) {
        NDIlib_find_destroy(g_pNDI_find);
        g_pNDI_find = nullptr;
        __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Persistent finder destroyed");
    }

    // Clear cache
    {
        std::lock_guard<std::mutex> cacheLock(g_sourceCacheMutex);
        g_sourceCache.clear();
        __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Source cache cleared");
    }
}

// ============================================================================
// CONNECT TO SPECIFIC SOURCE
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_MainActivity_connectToNDISource(
        JNIEnv* env,
        jobject /* this */,
        jstring sourceFullName,
        jobject surface,
        jint quality) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "🔌 Connecting to NDI source...");

    // Stop existing receiver
    {
        std::lock_guard<std::mutex> lock(g_receiverMutex);
        if (g_receiverRunning) {
            g_receiverRunning = false;
            if (g_receiverThread.joinable()) {
                g_receiverThread.join();
            }
        }
        if (g_pNDI_recv) {
            NDIlib_recv_destroy(g_pNDI_recv);
            g_pNDI_recv = nullptr;
        }
        if (g_window) {
            ANativeWindow_release(g_window);
            g_window = nullptr;
        }
    }

    // Get source name
    const char* sourceNameChars = env->GetStringUTFChars(sourceFullName, nullptr);
    std::string sourceNameStr(sourceNameChars);
    env->ReleaseStringUTFChars(sourceFullName, sourceNameChars);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Looking for: %s", sourceNameStr.c_str());

    // Get window
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ Window creation failed");
        return JNI_FALSE;
    }
    g_window = window;

    // Initialize persistent finder
    if (!initializePersistentFinder()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ Finder init failed");
        ANativeWindow_release(window);
        g_window = nullptr;
        return JNI_FALSE;
    }

    // Try to find source in cache first
    const NDIlib_source_t* target_source = nullptr;
    NDIlib_source_t cached_source;
    bool foundInCache = false;

    {
        std::lock_guard<std::mutex> cacheLock(g_sourceCacheMutex);
        auto it = g_sourceCache.find(sourceNameStr);
        if (it != g_sourceCache.end()) {
            cached_source = it->second.source;
            target_source = &cached_source;
            foundInCache = true;
            __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Found in cache!");
        }
    }

    // If not in cache, search actively
    if (!foundInCache) {
        std::lock_guard<std::mutex> lock(g_finderMutex);

        uint32_t no_sources = 0;
        const NDIlib_source_t* p_sources = nullptr;

        // Quick search (max 5 attempts)
        for (int attempt = 0; attempt < 5 && !target_source; attempt++) {
            NDIlib_find_wait_for_sources(g_pNDI_find, 500);
            p_sources = NDIlib_find_get_current_sources(g_pNDI_find, &no_sources);

            if (p_sources && no_sources > 0) {
                // Update cache
                updateSourceCache(p_sources, no_sources);

                // Find target
                for (uint32_t i = 0; i < no_sources; i++) {
                    if (sourceNameStr == p_sources[i].p_ndi_name) {
                        target_source = &p_sources[i];
                        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "✓ Found target source (attempt %d)!", attempt + 1);
                        break;
                    }
                }
            }
        }
    }

    if (!target_source) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ Source not found: %s",
            sourceNameStr.c_str());
        ANativeWindow_release(window);
        g_window = nullptr;
        return JNI_FALSE;
    }

    // Create receiver
    NDIlib_recv_create_v3_t recv_settings;
    recv_settings.source_to_connect_to = *target_source;
    recv_settings.color_format = NDIlib_recv_color_format_BGRX_BGRA;

    // Set bandwidth based on quality
    switch(quality) {
        case 360:
            recv_settings.bandwidth = NDIlib_recv_bandwidth_lowest;
            break;
        case 720:
            recv_settings.bandwidth = NDIlib_recv_bandwidth_highest;
            break;
        case 1080:
        default:
            recv_settings.bandwidth = NDIlib_recv_bandwidth_highest;
            break;
    }

    recv_settings.allow_video_fields = true;
    recv_settings.p_ndi_recv_name = "Android NDI Receiver";

    NDIlib_recv_instance_t pNDI_recv = NDIlib_recv_create_v3(&recv_settings);

    if (!pNDI_recv) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ Receiver creation failed");
        ANativeWindow_release(window);
        g_window = nullptr;
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Receiver created");

    // Store global receiver
    {
        std::lock_guard<std::mutex> lock(g_receiverMutex);
        g_pNDI_recv = pNDI_recv;
        g_receiverRunning = true;
    }

    // Start receiver thread
    g_receiverThread = std::thread([window]() {
        __android_log_print(ANDROID_LOG_INFO, TAG, "📺 Receiver thread started");

        NDIlib_recv_instance_t pNDI_recv = g_pNDI_recv;
        int frameCount = 0;
        auto startTime = std::chrono::steady_clock::now();

        while (g_receiverRunning && pNDI_recv) {
            NDIlib_video_frame_v2_t video_frame;
            NDIlib_audio_frame_v3_t audio_frame;
            NDIlib_metadata_frame_t metadata_frame;

            NDIlib_frame_type_e frameType = NDIlib_recv_capture_v3(
                pNDI_recv, &video_frame, &audio_frame, &metadata_frame, 1000);

            switch (frameType) {
                case NDIlib_frame_type_video: {
                    frameCount++;

                    char fourccStr[5];
                    fourcc_to_string((uint32_t)video_frame.FourCC, fourccStr);

                    // Log every 60 frames
                    if (frameCount % 60 == 0) {
                        auto now = std::chrono::steady_clock::now();
                        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                            now - startTime).count();
                        float fps = elapsed > 0 ? (float)frameCount / elapsed : 0;

                        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "📊 %dx%d %s @ %.1f fps",
                            video_frame.xres, video_frame.yres, fourccStr, fps);
                    }

                    if (window) {
                        ANativeWindow_setBuffersGeometry(
                            window, video_frame.xres, video_frame.yres,
                            WINDOW_FORMAT_RGBA_8888);

                        ANativeWindow_Buffer buffer;
                        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
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
                                        uint8_t a = (video_frame.FourCC == NDIlib_FourCC_video_type_BGRA) ?
                                            srow[x*4 + 3] : 0xFF;
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
                                    memcpy(dst + y * dstStrideBytes,
                                           src + y * srcStride, width * 4);
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
                                        drow[(x+0)*4 + 0] = clamp((C1 + 409*V + 128) >> 8);
                                        drow[(x+0)*4 + 1] = clamp((C1 - 100*U - 208*V + 128) >> 8);
                                        drow[(x+0)*4 + 2] = clamp((C1 + 516*U + 128) >> 8);
                                        drow[(x+0)*4 + 3] = 0xFF;

                                        int C2 = 298 * Y1;
                                        drow[(x+1)*4 + 0] = clamp((C2 + 409*V + 128) >> 8);
                                        drow[(x+1)*4 + 1] = clamp((C2 - 100*U - 208*V + 128) >> 8);
                                        drow[(x+1)*4 + 2] = clamp((C2 + 516*U + 128) >> 8);
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
                                        drow[x*4 + 0] = clamp((C + 409*vVal + 128) >> 8);
                                        drow[x*4 + 1] = clamp((C - 100*uVal - 208*vVal + 128) >> 8);
                                        drow[x*4 + 2] = clamp((C + 516*uVal + 128) >> 8);
                                        drow[x*4 + 3] = 0xFF;
                                    }
                                }
                                handled = true;
                            }

                            if (!handled) {
                                __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "⚠️ Unsupported FourCC: %s", fourccStr);
                            }

                            ANativeWindow_unlockAndPost(window);
                        }
                    }

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
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ Connection error");
                    g_receiverRunning = false;
                    break;

                default:
                    break;
            }
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "📺 Receiver thread stopped");
    });

    g_receiverThread.detach();

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Connection successful");
    return JNI_TRUE;
}

// ============================================================================
// STOP RECEIVER
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_stopNDIReceiver(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "🛑 Stopping receiver...");

    std::lock_guard<std::mutex> lock(g_receiverMutex);

    g_receiverRunning = false;

    if (g_receiverThread.joinable()) {
        g_receiverThread.join();
    }

    if (g_pNDI_recv) {
        NDIlib_recv_destroy(g_pNDI_recv);
        g_pNDI_recv = nullptr;
    }

    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "✓ Receiver stopped");
}

// ============================================================================
// CLEANUP ALL (untuk shutdown app)
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_NDIManager_cleanup(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "🧹 Cleaning up NDI...");

    // Stop receiver first
    Java_com_example_myapplication_MainActivity_stopNDIReceiver(env, nullptr);

    // Cleanup finder
    cleanupPersistentFinder();

    // Deinitialize NDI
    if (g_ndi_initialized) {
        NDIlib_destroy();
        g_ndi_initialized = false;
        __android_log_print(ANDROID_LOG_INFO, TAG, "✓ NDI deinitialized");
    }
}

// ============================================================================
// LEGACY COMPATIBILITY
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_startNDIReceiver(
        JNIEnv* env,
        jobject thiz,
        jobject surface) {

    // This is for backward compatibility
    // Auto-connect to first available source
    __android_log_print(ANDROID_LOG_INFO, TAG, "Auto-connecting to first source...");

    // Find sources
    jobjectArray sources = Java_com_example_myapplication_NDIManager_findNDISources(env, thiz);

    if (env->GetArrayLength(sources) > 0) {
        jstring firstSource = (jstring)env->GetObjectArrayElement(sources, 0);
        Java_com_example_myapplication_MainActivity_connectToNDISource(
            env, thiz, firstSource, surface, 720);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "❌ No sources found for auto-connect");
    }
}