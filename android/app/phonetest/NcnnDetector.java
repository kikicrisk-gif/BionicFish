package com.bionicfish.cameradiagnostic;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thin, thread-safe Java owner for the native NCNN detector.
 *
 * <p>The native implementation owns one {@code ncnn::Net} per returned handle. Calls to
 * {@link #detect(Bitmap, float, float)} and {@link #release()} are serialized so a net can never be
 * destroyed while an inference is in flight. The native detector must return boxes in the pixel
 * coordinate system of the exact {@link Bitmap} passed to {@code detect}; preview-space mapping is
 * intentionally handled by {@link DetectionOverlayView}.</p>
 */
public final class NcnnDetector implements AutoCloseable {
    public static final String DEFAULT_PARAM_ASSET = "models/model.ncnn.param";
    public static final String DEFAULT_BIN_ASSET = "models/model.ncnn.bin";
    public static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.50f;
    public static final float DEFAULT_NMS_THRESHOLD = 0.45f;

    private static final String NATIVE_LIBRARY_NAME = "bionicfish_ncnn";
    private static final int NATIVE_RESULT_STRIDE = 6;

    private static final boolean NATIVE_LIBRARY_AVAILABLE;
    private static final String NATIVE_LIBRARY_ERROR;

    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary(NATIVE_LIBRARY_NAME);
            available = true;
        } catch (Throwable cause) {
            error = readableMessage(cause);
        }
        NATIVE_LIBRARY_AVAILABLE = available;
        NATIVE_LIBRARY_ERROR = error;
    }

    private final Object nativeLock = new Object();
    private long nativeHandle;
    private boolean usingVulkan;

    /** Returns whether the JNI shared library was packaged and loaded successfully. */
    public static boolean isNativeLibraryAvailable() {
        return NATIVE_LIBRARY_AVAILABLE;
    }

    /**
     * Returns a user-displayable reason when the detector is unavailable, or an empty string when
     * the JNI library is ready. This method never calls into JNI and is safe before {@link #load}.
     */
    @NonNull
    public static String getAvailabilityError() {
        if (NATIVE_LIBRARY_AVAILABLE) return "";
        return "无法加载 " + NATIVE_LIBRARY_NAME + "：" + NATIVE_LIBRARY_ERROR;
    }

    /** Loads the default model assets and prefers Vulkan when the device supports it. */
    @NonNull
    public LoadInfo load(@NonNull AssetManager assets) throws DetectorException {
        return load(assets, DEFAULT_PARAM_ASSET, DEFAULT_BIN_ASSET, true);
    }

    /** Loads the default model assets with an explicit Vulkan preference. */
    @NonNull
    public LoadInfo load(@NonNull AssetManager assets, boolean preferVulkan)
            throws DetectorException {
        return load(assets, DEFAULT_PARAM_ASSET, DEFAULT_BIN_ASSET, preferVulkan);
    }

    /**
     * Loads an NCNN param/bin pair from APK assets.
     *
     * <p>If this instance already owns a model, that model is released first. A failed reload leaves
     * the detector unloaded, rather than silently continuing with stale weights.</p>
     */
    @NonNull
    public LoadInfo load(@NonNull AssetManager assets,
                         @NonNull String paramAsset,
                         @NonNull String binAsset,
                         boolean preferVulkan) throws DetectorException {
        if (assets == null) throw new DetectorException("AssetManager 不能为空");
        if (paramAsset == null || paramAsset.trim().isEmpty()) {
            throw new DetectorException("NCNN param 资源路径不能为空");
        }
        if (binAsset == null || binAsset.trim().isEmpty()) {
            throw new DetectorException("NCNN bin 资源路径不能为空");
        }
        requireNativeLibrary();

        synchronized (nativeLock) {
            releaseLocked();
            try {
                nativeHandle = nativeCreate(assets, paramAsset, binAsset, preferVulkan);
            } catch (Throwable cause) {
                nativeHandle = 0L;
                usingVulkan = false;
                throw nativeFailure("初始化 NCNN 失败", cause);
            }
            if (nativeHandle == 0L) {
                usingVulkan = false;
                throw nativeFailure("初始化 NCNN 失败", null);
            }

            try {
                usingVulkan = nativeIsVulkan(nativeHandle);
            } catch (Throwable cause) {
                releaseLocked();
                throw nativeFailure("读取 NCNN 后端状态失败", cause);
            }
            return new LoadInfo(usingVulkan, usingVulkan ? "NCNN Vulkan" : "NCNN CPU");
        }
    }

    /** Runs detection using the production defaults: confidence 0.50 and NMS IoU 0.45. */
    @NonNull
    public List<Result> detect(@NonNull Bitmap bitmap) throws DetectorException {
        return detect(bitmap, DEFAULT_CONFIDENCE_THRESHOLD, DEFAULT_NMS_THRESHOLD);
    }

    /**
     * Runs one inference. Returned coordinates are pixels in {@code bitmap}'s coordinate system.
     * The native array layout is six floats per result:
     * {@code left, top, right, bottom, confidence, classId}.
     */
    @NonNull
    public List<Result> detect(@NonNull Bitmap bitmap,
                               float confidenceThreshold,
                               float nmsThreshold) throws DetectorException {
        if (bitmap == null) throw new DetectorException("检测 Bitmap 不能为空");
        if (bitmap.isRecycled()) throw new DetectorException("检测 Bitmap 已被回收");
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            throw new DetectorException("检测 Bitmap 尺寸无效");
        }
        if (!Float.isFinite(confidenceThreshold)
                || confidenceThreshold < 0f || confidenceThreshold > 1f) {
            throw new DetectorException("置信度阈值必须位于 0 到 1");
        }
        if (!Float.isFinite(nmsThreshold) || nmsThreshold < 0f || nmsThreshold > 1f) {
            throw new DetectorException("NMS 阈值必须位于 0 到 1");
        }
        requireNativeLibrary();

        synchronized (nativeLock) {
            if (nativeHandle == 0L) throw new DetectorException("NCNN 模型尚未加载");

            final float[] packed;
            try {
                packed = nativeDetect(nativeHandle, bitmap, confidenceThreshold, nmsThreshold);
            } catch (Throwable cause) {
                throw nativeFailure("NCNN 推理失败", cause);
            }
            if (packed == null) throw nativeFailure("NCNN 推理未返回结果", null);
            if (packed.length % NATIVE_RESULT_STRIDE != 0) {
                throw new DetectorException("NCNN 返回数据长度无效：" + packed.length);
            }
            if (packed.length == 0) return Collections.emptyList();

            int bitmapWidth = bitmap.getWidth();
            int bitmapHeight = bitmap.getHeight();
            ArrayList<Result> results = new ArrayList<>(packed.length / NATIVE_RESULT_STRIDE);
            for (int i = 0; i < packed.length; i += NATIVE_RESULT_STRIDE) {
                float left = packed[i];
                float top = packed[i + 1];
                float right = packed[i + 2];
                float bottom = packed[i + 3];
                float confidence = packed[i + 4];
                float classValue = packed[i + 5];

                if (!allFinite(left, top, right, bottom, confidence, classValue)) continue;
                left = clamp(left, 0f, bitmapWidth);
                top = clamp(top, 0f, bitmapHeight);
                right = clamp(right, 0f, bitmapWidth);
                bottom = clamp(bottom, 0f, bitmapHeight);
                confidence = clamp(confidence, 0f, 1f);
                if (right <= left || bottom <= top) continue;

                int classId = Math.max(0, Math.round(classValue));
                results.add(new Result(left, top, right, bottom, confidence, classId));
            }
            if (results.isEmpty()) return Collections.emptyList();
            return Collections.unmodifiableList(results);
        }
    }

    public boolean isLoaded() {
        synchronized (nativeLock) {
            return nativeHandle != 0L;
        }
    }

    public boolean isUsingVulkan() {
        synchronized (nativeLock) {
            return nativeHandle != 0L && usingVulkan;
        }
    }

    @NonNull
    public String getBackendLabel() {
        synchronized (nativeLock) {
            if (nativeHandle == 0L) return "未加载";
            return usingVulkan ? "NCNN Vulkan" : "NCNN CPU";
        }
    }

    /** Idempotently releases the native net and its Vulkan resources. */
    public void release() {
        synchronized (nativeLock) {
            releaseLocked();
        }
    }

    @Override
    public void close() {
        release();
    }

    private void releaseLocked() {
        long handle = nativeHandle;
        nativeHandle = 0L;
        usingVulkan = false;
        if (handle == 0L || !NATIVE_LIBRARY_AVAILABLE) return;
        try {
            nativeDestroy(handle);
        } catch (Throwable ignored) {
            // Release is deliberately idempotent and must remain safe during Activity teardown.
        }
    }

    private static void requireNativeLibrary() throws DetectorException {
        if (!NATIVE_LIBRARY_AVAILABLE) throw new DetectorException(getAvailabilityError());
    }

    @NonNull
    private static DetectorException nativeFailure(@NonNull String operation, Throwable cause) {
        String nativeDetail = "";
        if (NATIVE_LIBRARY_AVAILABLE) {
            try {
                String value = nativeGetLastError();
                if (value != null) nativeDetail = value.trim();
            } catch (Throwable ignored) {
            }
        }
        String causeDetail = cause == null ? "" : readableMessage(cause);
        String detail = !nativeDetail.isEmpty() ? nativeDetail : causeDetail;
        String message = detail.isEmpty() ? operation : operation + "：" + detail;
        return cause == null
                ? new DetectorException(message)
                : new DetectorException(message, cause);
    }

    private static boolean allFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @NonNull
    private static String readableMessage(Throwable cause) {
        if (cause == null) return "未知错误";
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) return cause.getClass().getSimpleName();
        return message.trim();
    }

    /** One immutable detection in input-Bitmap pixel coordinates. */
    public static final class Result {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final float confidence;
        public final int classId;

        public Result(float left, float top, float right, float bottom,
                      float confidence, int classId) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.confidence = confidence;
            this.classId = classId;
        }

        @NonNull
        public String label() {
            return classId == 0 ? "oil_leak" : "class_" + classId;
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }
    }

    /** Immutable description of the backend selected while loading. */
    public static final class LoadInfo {
        public final boolean usingVulkan;
        @NonNull public final String backendLabel;

        private LoadInfo(boolean usingVulkan, @NonNull String backendLabel) {
            this.usingVulkan = usingVulkan;
            this.backendLabel = backendLabel;
        }
    }

    public static final class DetectorException extends Exception {
        public DetectorException(@NonNull String message) {
            super(message);
        }

        public DetectorException(@NonNull String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static native long nativeCreate(@NonNull AssetManager assets,
                                            @NonNull String paramAsset,
                                            @NonNull String binAsset,
                                            boolean preferVulkan);

    @NonNull
    private static native float[] nativeDetect(long handle,
                                               @NonNull Bitmap bitmap,
                                               float confidenceThreshold,
                                               float nmsThreshold);

    private static native boolean nativeIsVulkan(long handle);

    private static native String nativeGetLastError();

    private static native void nativeDestroy(long handle);
}
