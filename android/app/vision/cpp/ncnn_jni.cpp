#include <jni.h>

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <gpu.h>
#include <mat.h>
#include <net.h>

namespace {

constexpr char kLogTag[] = "BionicFishNCNN";
constexpr int kInputSize = 640;
constexpr int kOutputChannels = 5;
constexpr int kCandidateCount = 8400;
constexpr int kMaxDetections = 300;

struct Detection {
    float x1;
    float y1;
    float x2;
    float y2;
    float score;
};

struct Detector {
    ncnn::Net net;
    bool using_vulkan = false;
    std::mutex inference_mutex;
};

std::mutex g_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Detector>> g_detectors;
std::atomic<jlong> g_next_handle{1};

std::mutex g_error_mutex;
std::string g_last_error;

void set_last_error(std::string message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = std::move(message);
    if (!g_last_error.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", g_last_error.c_str());
    }
}

std::string get_last_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}

class UtfChars final {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) {
            chars_ = env_->GetStringUTFChars(value_, nullptr);
        }
    }

    ~UtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;

    const char* get() const { return chars_; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

class BitmapPixelLock final {
public:
    BitmapPixelLock(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
        result_ = AndroidBitmap_lockPixels(env_, bitmap_, &pixels_);
    }

    ~BitmapPixelLock() {
        if (result_ == ANDROID_BITMAP_RESULT_SUCCESS && pixels_ != nullptr) {
            AndroidBitmap_unlockPixels(env_, bitmap_);
        }
    }

    BitmapPixelLock(const BitmapPixelLock&) = delete;
    BitmapPixelLock& operator=(const BitmapPixelLock&) = delete;

    int result() const { return result_; }
    const unsigned char* pixels() const {
        return static_cast<const unsigned char*>(pixels_);
    }

private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
    int result_ = ANDROID_BITMAP_RESULT_BAD_PARAMETER;
};

std::shared_ptr<Detector> find_detector(jlong handle) {
    if (handle <= 0) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const auto it = g_detectors.find(handle);
    return it == g_detectors.end() ? nullptr : it->second;
}

float intersection_over_union(const Detection& a, const Detection& b) {
    const float left = std::max(a.x1, b.x1);
    const float top = std::max(a.y1, b.y1);
    const float right = std::min(a.x2, b.x2);
    const float bottom = std::min(a.y2, b.y2);
    const float intersection = std::max(0.0f, right - left) * std::max(0.0f, bottom - top);
    const float area_a = std::max(0.0f, a.x2 - a.x1) * std::max(0.0f, a.y2 - a.y1);
    const float area_b = std::max(0.0f, b.x2 - b.x1) * std::max(0.0f, b.y2 - b.y1);
    const float union_area = area_a + area_b - intersection;
    return union_area > 0.0f ? intersection / union_area : 0.0f;
}

std::vector<Detection> non_max_suppression(std::vector<Detection> candidates, float iou_threshold) {
    std::sort(candidates.begin(), candidates.end(), [](const Detection& a, const Detection& b) {
        return a.score > b.score;
    });

    std::vector<Detection> kept;
    kept.reserve(std::min(static_cast<int>(candidates.size()), kMaxDetections));
    for (const Detection& candidate : candidates) {
        bool suppressed = false;
        for (const Detection& accepted : kept) {
            // Ultralytics keeps boxes whose IoU is <= the configured threshold.
            if (intersection_over_union(candidate, accepted) > iou_threshold) {
                suppressed = true;
                break;
            }
        }
        if (!suppressed) {
            kept.push_back(candidate);
            if (static_cast<int>(kept.size()) >= kMaxDetections) {
                break;
            }
        }
    }
    return kept;
}

int extract_output(Detector& detector, const ncnn::Mat& input, ncnn::Mat& output) {
    ncnn::Extractor extractor = detector.net.create_extractor();
    extractor.set_light_mode(true);
    int result = extractor.input("in0", input);
    if (result != 0) {
        return result;
    }
    return extractor.extract("out0", output);
}

bool has_expected_output_layout(const ncnn::Mat& output) {
    return output.dims == 2 &&
           ((output.h == kOutputChannels && output.w == kCandidateCount) ||
            (output.w == kOutputChannels && output.h == kCandidateCount)) &&
           output.elempack == 1 && output.elembits() == 32;
}

int warm_up(Detector& detector, ncnn::Mat& output) {
    ncnn::Mat input(kInputSize, kInputSize, 3);
    if (input.empty()) {
        return -100;
    }
    input.fill(0.0f);
    return extract_output(detector, input, output);
}

bool read_candidate(const ncnn::Mat& output, int index, Detection& detection) {
    float cx;
    float cy;
    float width;
    float height;
    float score;

    if (output.dims == 2 && output.h == kOutputChannels) {
        cx = output.row(0)[index];
        cy = output.row(1)[index];
        width = output.row(2)[index];
        height = output.row(3)[index];
        score = output.row(4)[index];
    } else if (output.dims == 2 && output.w == kOutputChannels) {
        const float* row = output.row(index);
        cx = row[0];
        cy = row[1];
        width = row[2];
        height = row[3];
        score = row[4];
    } else {
        return false;
    }

    if (!std::isfinite(cx) || !std::isfinite(cy) || !std::isfinite(width) ||
        !std::isfinite(height) || !std::isfinite(score) || width <= 0.0f || height <= 0.0f) {
        return false;
    }

    detection.x1 = cx - width * 0.5f;
    detection.y1 = cy - height * 0.5f;
    detection.x2 = cx + width * 0.5f;
    detection.y2 = cy + height * 0.5f;
    detection.score = score;
    return true;
}

std::string output_shape(const ncnn::Mat& output) {
    std::ostringstream stream;
    stream << "dims=" << output.dims << ", w=" << output.w << ", h=" << output.h
           << ", d=" << output.d << ", c=" << output.c << ", elempack=" << output.elempack;
    return stream.str();
}

jfloatArray empty_float_array(JNIEnv* env) {
    return env->NewFloatArray(0);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
#if NCNN_VULKAN
    ncnn::create_gpu_instance();
#endif
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        g_detectors.clear();
    }
#if NCNN_VULKAN
    ncnn::destroy_gpu_instance();
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bionicfish_cameradiagnostic_NcnnDetector_nativeCreate(
        JNIEnv* env,
        jclass,
        jobject asset_manager,
        jstring param_asset,
        jstring bin_asset,
        jboolean prefer_vulkan) {
    set_last_error("");
    if (asset_manager == nullptr || param_asset == nullptr || bin_asset == nullptr) {
        set_last_error("nativeCreate requires an AssetManager and both model asset paths");
        return 0;
    }

    AAssetManager* manager = AAssetManager_fromJava(env, asset_manager);
    if (manager == nullptr) {
        set_last_error("Could not obtain the native Android AssetManager");
        return 0;
    }

    UtfChars param_path(env, param_asset);
    UtfChars bin_path(env, bin_asset);
    if (param_path.get() == nullptr || bin_path.get() == nullptr) {
        set_last_error("Could not read the NCNN model asset paths");
        return 0;
    }

    try {
        auto detector = std::make_shared<Detector>();

#if NCNN_VULKAN
        if (prefer_vulkan == JNI_TRUE && ncnn::get_gpu_count() > 0) {
            detector->net.opt.use_vulkan_compute = true;
            detector->net.set_vulkan_device(0);
            detector->using_vulkan = true;
        }
#else
        (void)prefer_vulkan;
#endif

        const auto load_assets = [&]() -> int {
            const int param_result = detector->net.load_param(manager, param_path.get());
            if (param_result != 0) {
                return param_result;
            }
            const int bin_result = detector->net.load_model(manager, bin_path.get());
            return bin_result == 0 ? 0 : bin_result;
        };

        int load_result = load_assets();
        if (load_result != 0) {
            std::ostringstream message;
            message << "Failed to load NCNN assets '" << param_path.get() << "' and '"
                    << bin_path.get() << "' (code " << load_result << ")";
            set_last_error(message.str());
            return 0;
        }

        // Build the selected backend's pipelines now. If Vulkan exists but this particular
        // model/device combination cannot execute, rebuild the Net from the assets on CPU rather
        // than attempting an unsupported hot switch on already-created Vulkan pipelines.
        ncnn::Mat warmup_output;
        int warmup_result = warm_up(*detector, warmup_output);
        bool warmup_valid = warmup_result == 0 && has_expected_output_layout(warmup_output);
        if (!warmup_valid && detector->using_vulkan) {
            __android_log_print(
                    ANDROID_LOG_WARN,
                    kLogTag,
                    "Vulkan warm-up failed (code=%d, %s); rebuilding the detector for CPU",
                    warmup_result,
                    output_shape(warmup_output).c_str());
            detector->net.clear();
            detector->net.opt.use_vulkan_compute = false;
            detector->using_vulkan = false;
            load_result = load_assets();
            if (load_result != 0) {
                std::ostringstream message;
                message << "Vulkan failed and CPU model reload failed (code " << load_result << ")";
                set_last_error(message.str());
                return 0;
            }
            warmup_output.release();
            warmup_result = warm_up(*detector, warmup_output);
            warmup_valid = warmup_result == 0 && has_expected_output_layout(warmup_output);
        }
        if (!warmup_valid) {
            std::ostringstream message;
            message << "NCNN model warm-up failed (code " << warmup_result << ", "
                    << output_shape(warmup_output) << ")";
            set_last_error(message.str());
            return 0;
        }

        const jlong handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> lock(g_registry_mutex);
            g_detectors.emplace(handle, detector);
        }
        __android_log_print(
                ANDROID_LOG_INFO,
                kLogTag,
                "Loaded NCNN detector (handle=%lld, backend=%s)",
                static_cast<long long>(handle),
                detector->using_vulkan ? "Vulkan" : "CPU");
        return handle;
    } catch (const std::exception& exception) {
        set_last_error(std::string("Exception while loading NCNN model: ") + exception.what());
    } catch (...) {
        set_last_error("Unknown exception while loading NCNN model");
    }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_bionicfish_cameradiagnostic_NcnnDetector_nativeDetect(
        JNIEnv* env,
        jclass,
        jlong handle,
        jobject bitmap,
        jfloat confidence_threshold,
        jfloat nms_threshold) {
    set_last_error("");
    const std::shared_ptr<Detector> detector = find_detector(handle);
    if (detector == nullptr) {
        set_last_error("nativeDetect received an invalid or destroyed detector handle");
        return nullptr;
    }
    if (bitmap == nullptr) {
        set_last_error("nativeDetect received a null Bitmap");
        return nullptr;
    }
    if (!(confidence_threshold >= 0.0f && confidence_threshold <= 1.0f) ||
        !(nms_threshold >= 0.0f && nms_threshold <= 1.0f)) {
        set_last_error("Confidence and NMS thresholds must both be in [0, 1]");
        return nullptr;
    }

    std::lock_guard<std::mutex> inference_lock(detector->inference_mutex);

    try {
        AndroidBitmapInfo bitmap_info{};
        const int info_result = AndroidBitmap_getInfo(env, bitmap, &bitmap_info);
        if (info_result != ANDROID_BITMAP_RESULT_SUCCESS) {
            std::ostringstream message;
            message << "AndroidBitmap_getInfo failed (code " << info_result << ")";
            set_last_error(message.str());
            return nullptr;
        }
        if (bitmap_info.width == 0 || bitmap_info.height == 0) {
            set_last_error("nativeDetect received an empty Bitmap");
            return nullptr;
        }
        if (bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            std::ostringstream message;
            message << "Bitmap must use ARGB_8888/RGBA_8888; Android format was "
                    << bitmap_info.format;
            set_last_error(message.str());
            return nullptr;
        }

        const int source_width = static_cast<int>(bitmap_info.width);
        const int source_height = static_cast<int>(bitmap_info.height);
        const float scale = std::min(
                static_cast<float>(kInputSize) / static_cast<float>(source_width),
                static_cast<float>(kInputSize) / static_cast<float>(source_height));
        const int resized_width = std::max(
                1, static_cast<int>(std::nearbyint(static_cast<float>(source_width) * scale)));
        const int resized_height = std::max(
                1, static_cast<int>(std::nearbyint(static_cast<float>(source_height) * scale)));
        const int horizontal_padding = kInputSize - resized_width;
        const int vertical_padding = kInputSize - resized_height;
        const int left = horizontal_padding / 2;
        const int right = horizontal_padding - left;
        const int top = vertical_padding / 2;
        const int bottom = vertical_padding - top;

        ncnn::Mat resized;
        {
            BitmapPixelLock pixel_lock(env, bitmap);
            if (pixel_lock.result() != ANDROID_BITMAP_RESULT_SUCCESS || pixel_lock.pixels() == nullptr) {
                std::ostringstream message;
                message << "AndroidBitmap_lockPixels failed (code " << pixel_lock.result()
                        << "); hardware Bitmaps are not supported";
                set_last_error(message.str());
                return nullptr;
            }

            resized = ncnn::Mat::from_pixels_resize(
                    pixel_lock.pixels(),
                    ncnn::Mat::PIXEL_RGBA2RGB,
                    source_width,
                    source_height,
                    static_cast<int>(bitmap_info.stride),
                    resized_width,
                    resized_height);
        }

        if (resized.empty()) {
            set_last_error("NCNN failed to convert and resize the input Bitmap");
            return nullptr;
        }

        ncnn::Mat input;
        ncnn::copy_make_border(
                resized,
                input,
                top,
                bottom,
                left,
                right,
                ncnn::BORDER_CONSTANT,
                114.0f);
        if (input.empty() || input.w != kInputSize || input.h != kInputSize || input.c != 3) {
            set_last_error("Failed to construct the 640x640 RGB letterbox input");
            return nullptr;
        }

        constexpr float kNorm[3] = {
                1.0f / 255.0f,
                1.0f / 255.0f,
                1.0f / 255.0f,
        };
        input.substract_mean_normalize(nullptr, kNorm);

        ncnn::Mat output;
        const int inference_result = extract_output(*detector, input, output);
        if (inference_result != 0) {
            std::ostringstream message;
            message << "NCNN inference failed (code " << inference_result << ")";
            set_last_error(message.str());
            return nullptr;
        }

        if (!has_expected_output_layout(output)) {
            set_last_error(
                    "Unexpected NCNN output shape; expected 5x8400 or 8400x5, got " +
                    output_shape(output));
            return nullptr;
        }

        const int candidate_count = output.h == kOutputChannels ? output.w : output.h;
        std::vector<Detection> candidates;
        candidates.reserve(std::min(candidate_count, 256));
        for (int index = 0; index < candidate_count; ++index) {
            Detection detection{};
            if (read_candidate(output, index, detection) && detection.score > confidence_threshold) {
                candidates.push_back(detection);
            }
        }

        std::vector<Detection> kept = non_max_suppression(std::move(candidates), nms_threshold);
        std::vector<jfloat> flattened;
        flattened.reserve(kept.size() * 6);
        for (const Detection& detection : kept) {
            // NMS is intentionally performed in letterbox coordinates, just like Ultralytics.
            // Only the kept boxes are then mapped back into the caller's Bitmap coordinates.
            const float x1 = std::clamp((detection.x1 - static_cast<float>(left)) / scale,
                                        0.0f, static_cast<float>(source_width));
            const float y1 = std::clamp((detection.y1 - static_cast<float>(top)) / scale,
                                        0.0f, static_cast<float>(source_height));
            const float x2 = std::clamp((detection.x2 - static_cast<float>(left)) / scale,
                                        0.0f, static_cast<float>(source_width));
            const float y2 = std::clamp((detection.y2 - static_cast<float>(top)) / scale,
                                        0.0f, static_cast<float>(source_height));
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            flattened.push_back(x1);
            flattened.push_back(y1);
            flattened.push_back(x2);
            flattened.push_back(y2);
            flattened.push_back(detection.score);
            flattened.push_back(0.0f);  // The only class is oil_leak (class id 0).
        }

        if (flattened.empty()) {
            return empty_float_array(env);
        }
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(flattened.size()));
        if (result == nullptr) {
            set_last_error("Could not allocate the Java detection result array");
            return nullptr;
        }
        env->SetFloatArrayRegion(
                result,
                0,
                static_cast<jsize>(flattened.size()),
                flattened.data());
        if (env->ExceptionCheck()) {
            set_last_error("Could not copy detections into the Java result array");
            return nullptr;
        }
        return result;
    } catch (const std::exception& exception) {
        set_last_error(std::string("Exception during NCNN inference: ") + exception.what());
    } catch (...) {
        set_last_error("Unknown exception during NCNN inference");
    }
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bionicfish_cameradiagnostic_NcnnDetector_nativeIsVulkan(
        JNIEnv*, jclass, jlong handle) {
    const std::shared_ptr<Detector> detector = find_detector(handle);
    if (detector == nullptr) {
        set_last_error("nativeIsVulkan received an invalid or destroyed detector handle");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> inference_lock(detector->inference_mutex);
    return detector->using_vulkan ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bionicfish_cameradiagnostic_NcnnDetector_nativeGetLastError(
        JNIEnv* env, jclass) {
    const std::string message = get_last_error();
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_bionicfish_cameradiagnostic_NcnnDetector_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    if (handle <= 0) {
        return;
    }
    std::shared_ptr<Detector> removed;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        const auto it = g_detectors.find(handle);
        if (it == g_detectors.end()) {
            return;
        }
        removed = std::move(it->second);
        g_detectors.erase(it);
    }
    // If an inference is still running, the shared_ptr retained by that call keeps the detector alive.
    __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Destroyed NCNN detector handle=%lld",
            static_cast<long long>(handle));
}
