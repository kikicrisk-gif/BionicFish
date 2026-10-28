package com.fish.phonetailtest;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Surface;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.effects.Frame;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.bionicfish.cameradiagnostic.NcnnDetector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class OilVision {
    public interface Listener {
        void onStatus(String text);
        void onTrigger(String eventId);
        void onError(String text);
    }

    private final ComponentActivity activity;
    private final PreviewView preview;
    private final Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private final HandlerThread renderThread =
            new HandlerThread("fish-overlay");
    private final Handler renderHandler;

    private final NcnnDetector detector = new NcnnDetector();

    private final ConcurrentSkipListMap<Long, Sample> samples =
            new ConcurrentSkipListMap<>();

    private final AtomicLong lastRendered = new AtomicLong();

    private ProcessCameraProvider provider;
    private ImageAnalysis analysis;
    private OverlayEffect effect;
    private PendingRecording pendingRecording;
    private Recording recording;

    private volatile int generation;
    private volatile boolean wanted;
    private volatile boolean modelReady;
    private volatile boolean destroyed;

    private boolean modelFailed;
    private boolean preparing;
    private boolean bound;
    private boolean frameReady;
    private boolean showPreview;
    private boolean closing;
    private boolean executorsClosed;

    private String preparedId;
    private String backend = "未加载";
    private volatile Run run;

    // 以下只由 worker 线程使用。
    private Gate previewGate = new Gate();
    private int previewGeneration = -1;
    private long lastUiUpdate;

    public OilVision(
            ComponentActivity activity,
            PreviewView preview,
            Listener listener) {

        this.activity = activity;
        this.preview = preview;
        this.listener = listener;

        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        // 只预加载模型，不开启摄像头。
        worker.execute(() -> {
            try {
                NcnnDetector.LoadInfo info =
                        detector.load(activity.getAssets(), true);

                main.post(() -> {
                    if (destroyed) return;
                    modelReady = true;
                    backend = info.backendLabel;

                    if (wanted) {
                        prepare(showPreview);
                    } else {
                        status("模型已加载，相机关闭");
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    modelFailed = true;
                    fatal("模型加载失败：" + error.getMessage());
                });
            }
        });
    }

    public boolean isClosing() { return closing; }

    public boolean isCameraRequested() { return wanted && !closing; }

    public boolean isRunning() { return run != null && run.accepting && !closing; }

    public boolean isBusy() {
        return closing || run != null;
    }

    // 勾选时调用：只准备和预览，不保存录像、不产生触发事件。
    public void prepare(boolean visible) {
        if (destroyed || closing) return;

        wanted = true;
        showPreview = visible;
        preview.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);

        if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            status("等待摄像头权限");
            return;
        }

        if (modelFailed) {
            status("模型不可用，请检查错误信息");
            return;
        }

        if (!modelReady) {
            status("正在加载模型……");
            return;
        }

        if (bound) {
            showState();
            return;
        }

        if (preparing) return;
        preparing = true;
        frameReady = false;

        final int token = ++generation;
        lastRendered.set(0);

        Run current = run;
        preparedId = current != null ? current.id : newId();

        status("正在准备相机、识别与录像配置……");

        var future = ProcessCameraProvider.getInstance(activity);

        future.addListener(() -> {
            if (destroyed || !wanted || token != generation) return;

            try {
                provider = future.get();

                Preview cameraPreview = new Preview.Builder()
                        .setTargetRotation(Surface.ROTATION_0)
                        .build();

                cameraPreview.setSurfaceProvider(
                        preview.getSurfaceProvider());

                analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setTargetRotation(Surface.ROTATION_0)
                        .setOutputImageFormat(
                                ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(worker,
                        image -> analyze(token, image));

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(
                                QualitySelector.fromOrderedList(
                                        Arrays.asList(
                                                Quality.HD,
                                                Quality.SD,
                                                Quality.FHD)))
                        .build();

                VideoCapture<Recorder> video =
                        VideoCapture.withOutput(recorder);
                video.setTargetRotation(Surface.ROTATION_0);

                // 缓冲有限数量的画面，等待对应的检测结果。
                effect = new OverlayEffect(
                        CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE,
                        8,
                        renderHandler,
                        error -> main.post(() -> {
                            if (token == generation) {
                                fatal("画面合成失败：" + error.getMessage());
                            }
                        }));

                effect.setOnDrawListener(
                        frame -> render(token, frame));

                UseCaseGroup group = new UseCaseGroup.Builder()
                        .addUseCase(cameraPreview)
                        .addUseCase(video)
                        .addUseCase(analysis)
                        .addEffect(effect)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(
                        activity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        group);

                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME,
                        preparedId + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        FishStorage.VIDEOS);

                MediaStoreOutputOptions output =
                        new MediaStoreOutputOptions.Builder(
                                activity.getContentResolver(),
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                                .setContentValues(values)
                                .build();

                // 这里只配置输出，尚未调用 start，不保存视频。
                pendingRecording =
                        recorder.prepareRecording(activity, output);

                preparing = false;
                bound = true;
                showState();

                Run requested = run;
                if (requested != null && requested.accepting &&
                        requested.saveVideo) {
                    startVideo(requested);
                }

            } catch (Exception error) {
                preparing = false;
                fatal("相机准备失败：" + error.getMessage());
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    // 由“确认执行”调用；主界面必须先调用 tail.startRoute。
    public void startRun(boolean saveVideo) {
        if (destroyed || closing || run != null) {
            listener.onError("上一段视觉任务尚未结束，本次未启动视觉记录");
            return;
        }

        Run current = new Run(
                preparedId != null ? preparedId : newId(),
                saveVideo);

        run = current;

        worker.execute(() -> log(current, "RUN_REQUEST",
                VisionLog.data(
                        "session_id", current.id,
                        "record_video", saveVideo,
                        "request_elapsed_ns", current.requestNs,
                        "hardware_signal", "PB11_OPEN_DRAIN",
                        "confirm_threshold", 0.50,
                        "clear_threshold", 0.35,
                        "confirm_samples", 3,
                        "clear_samples", 5)));

        prepare(true);

        if (bound && saveVideo) {
            startVideo(current);
        }

        showState();
    }

    private void startVideo(Run current) {
        if (run != current || !current.accepting ||
                !current.saveVideo || current.videoRequested ||
                pendingRecording == null || recording != null) {
            return;
        }

        current.videoRequested = true;

        try {
            recording = pendingRecording.start(
                    ContextCompat.getMainExecutor(activity),
                    event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            current.videoStarted = true;
                            worker.execute(() -> log(
                                    current, "VIDEO_STARTED",
                                    VisionLog.data(
                                            "note",
                                            "录像开始回调时间，不是相机曝光时间")));
                            showState();
                        }

                        if (event instanceof VideoRecordEvent.Status) {
                            current.videoDurationUs =
                                    event.getRecordingStats()
                                            .getRecordedDurationNanos() / 1000;

                            showState();
                        }

                        if (event instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize result =
                                    (VideoRecordEvent.Finalize) event;

                            recording = null;
                            current.videoError = result.hasError();
                            current.videoUri =
                                    result.getOutputResults()
                                            .getOutputUri().toString();

                            worker.execute(() -> log(
                                    current, "VIDEO_FINALIZED",
                                    VisionLog.data(
                                            "error_code", result.getError(),
                                            "video_uri", current.videoUri,
                                            "duration_us",
                                            event.getRecordingStats()
                                                    .getRecordedDurationNanos()
                                                    / 1000)));

                            if (result.hasError() && !destroyed) {
                                listener.onError(
                                        "录像结束，错误码：" + result.getError()
                                                + "。视频完整性未确认；运动控制未改变。");
                            }

                            if (current.accepting) {
                                current.reason = "video_finished_unexpectedly";
                            }

                            finish(current);
                        }
                    });

            pendingRecording = null;

        } catch (Exception error) {
            current.videoError = true;
            listener.onError(
                    "录像启动失败：" + error.getMessage()
                            + "。检测继续，运动控制未改变。");

            worker.execute(() -> log(
                    current, "VIDEO_START_ERROR",
                    VisionLog.data("message",
                            String.valueOf(error.getMessage()))));

            showState();
        }
    }

    public void stop(String reason) {
        Run current = run;

        if (current == null) {
            releaseCamera();
            status(modelReady ? "相机关闭，模型已加载" : "相机关闭");
            if (destroyed) closeExecutors();
            return;
        }

        if (closing) return;

        closing = true;
        current.accepting = false;
        current.reason = reason;
        preview.setVisibility(View.INVISIBLE);

        if (analysis != null) analysis.clearAnalyzer();

        status("正在保存录像、关键帧和日志……");

        if (recording != null) {
            recording.stop();
        } else {
            finish(current);
        }
    }

    private void finish(Run current) {
        if (current.finishing) return;

        current.finishing = true;
        current.accepting = false;
        closing = true;

        releaseCamera();

        worker.execute(() -> {
            log(current, "RUN_END",
                    VisionLog.data(
                            "reason", current.reason,
                            "trigger_count", current.events,
                            "video_requested", current.saveVideo,
                            "video_error", current.videoError,
                            "video_uri", current.videoUri));

            current.logClosed = true;

            if (current.log != null) {
                try {
                    current.log.close();
                } catch (Exception error) {
                    current.storageError = true;
                    notifyError("日志收尾失败：" + error.getMessage());
                }
            }

            main.post(() -> {
                if (run == current) run = null;
                closing = false;

                status("本次视觉任务已结束；触发 "
                        + current.events + " 次"
                        + ((current.storageError || current.videoError)
                        ? "。部分文件保存异常，请查看提示"
                        : "。文件已完成保存"));

                if (destroyed) closeExecutors();
            });
        });
    }

    private void analyze(int token, ImageProxy image) {
        Bitmap source = null;
        Bitmap upright = null;
        Run current = run;

        try {
            if (destroyed || !wanted || token != generation ||
                    !modelReady) {
                return;
            }

            long receivedNs = SystemClock.elapsedRealtimeNanos();
            long sensorNs = image.getImageInfo().getTimestamp();
            int rotation = image.getImageInfo().getRotationDegrees();

            // 在确认执行后的分析流中建立录像起始边界，
            // 不把此前等待显示的预览缓冲画面直接录入。
            if (current != null && current.accepting &&
                    current.firstSensorNs == Long.MAX_VALUE) {
                current.firstSensorNs = sensorNs;
            }

            Matrix bufferToSensor = new Matrix();
            if (!image.getImageInfo()
                    .getSensorToBufferTransformMatrix()
                    .invert(bufferToSensor)) {
                throw new IllegalStateException("相机坐标转换失败");
            }

            source = image.toBitmap();

            Matrix bufferToUpright =
                    rotationMatrix(source.getWidth(),
                            source.getHeight(), rotation);

            int width = rotation % 180 == 0
                    ? source.getWidth() : source.getHeight();
            int height = rotation % 180 == 0
                    ? source.getHeight() : source.getWidth();

            upright = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);

            new Canvas(upright).drawBitmap(
                    source, bufferToUpright, null);

            long beginNs = SystemClock.elapsedRealtimeNanos();

            List<NcnnDetector.Result> results =
                    detector.detect(upright, 0.35f, 0.50f);

            double inferMs =
                    (SystemClock.elapsedRealtimeNanos() - beginNs)
                            / 1_000_000.0;

            if (destroyed || !wanted || token != generation) return;

            float best = 0;
            for (NcnnDetector.Result result : results) {
                best = Math.max(best, result.confidence);
            }

            boolean inRun = current != null &&
                    current == run && current.accepting;

            if (previewGeneration != token) {
                previewGeneration = token;
                previewGate = new Gate();
            }

            Gate gate = inRun ? current.gate : previewGate;
            int transition = gate.update(best, receivedNs);

            Matrix uprightToBuffer = new Matrix();
            bufferToUpright.invert(uprightToBuffer);

            Matrix uprightToSensor = new Matrix();
            uprightToSensor.setConcat(
                    bufferToSensor, uprightToBuffer);

            List<float[]> sensorBoxes = new ArrayList<>();
            JSONArray boxesJson = new JSONArray();

            for (NcnnDetector.Result result : results) {
                RectF rect = new RectF(
                        result.left, result.top,
                        result.right, result.bottom);

                uprightToSensor.mapRect(rect);

                sensorBoxes.add(new float[]{
                        rect.left, rect.top, rect.right, rect.bottom,
                        result.confidence
                });

                boxesJson.put(VisionLog.data(
                        "left", result.left,
                        "top", result.top,
                        "right", result.right,
                        "bottom", result.bottom,
                        "confidence", result.confidence,
                        "class_id", result.classId));
            }

            int eventNumber = 0;
            if (inRun && transition == 1) {
                eventNumber = ++current.events;
            }

            Sample sample = new Sample(
                    sensorBoxes, gate.confirmed,
                    inRun ? current : null, eventNumber);

            boolean annotationLate = sensorNs <= lastRendered.get();

            samples.put(sensorNs, sample);
            while (samples.size() > 64) samples.pollFirstEntry();

            if (inRun) {
                log(current, "DETECTION",
                        VisionLog.data(
                                "sensor_ns", sensorNs,
                                "receive_elapsed_ns", receivedNs,
                                "since_request_ms",
                                (receivedNs - current.requestNs) / 1_000_000.0,
                                "infer_ms", inferMs,
                                "backend", backend,
                                "width", width,
                                "height", height,
                                "rotation_degrees", rotation,
                                "boxes", boxesJson,
                                "confirmed", gate.confirmed,
                                "hit_count", gate.hits,
                                "miss_count", gate.misses,
                                "annotation_late", annotationLate,
                                "video_duration_snapshot_us",
                                current.videoDurationUs));

                if (transition == -1) {
                    log(current, "OIL_CLEARED",
                            VisionLog.data("sensor_ns", sensorNs));
                }

                if (eventNumber != 0) {
                    String eventId = current.id + "_E" + eventNumber;

                    // 先通知主界面发送语音指令，
                    // 不等待关键帧JPEG编码和保存。
                    main.post(() -> {
                        if (!destroyed &&
                                run == current &&
                                current.accepting) {
                            listener.onTrigger(eventId);
                        }
                    });

                    // 本地事件先记录；硬件触发接口后续接入。
                    log(current, "OIL_TRIGGER",
                            VisionLog.data(
                                    "event_id", eventId,
                                    "sensor_ns", sensorNs,
                                    "confidence", best,
                                    "hardware_signal", "PENDING_DISPATCH"));

                    List<float[]> imageBoxes = new ArrayList<>();
                    for (NcnnDetector.Result result : results) {
                        imageBoxes.add(new float[]{
                                result.left, result.top,
                                result.right, result.bottom,
                                result.confidence
                        });
                    }

                    Canvas canvas = new Canvas(upright);
                    paintBoxes(canvas, imageBoxes, true);
                    paintCaption(canvas, eventId, 12, 28);

                    try {
                        String uri = VisionLog.saveKeyFrame(
                                activity, eventId, upright);

                        log(current, "KEYFRAME_SAVED",
                                VisionLog.data(
                                        "event_id", eventId,
                                        "sensor_ns", sensorNs,
                                        "image_uri", uri));
                    } catch (Exception error) {
                        current.storageError = true;
                        log(current, "KEYFRAME_ERROR",
                                VisionLog.data(
                                        "event_id", eventId,
                                        "message",
                                        String.valueOf(error.getMessage())));
                        notifyError("触发关键帧保存失败：" + error.getMessage());
                    }
                }
            }

            if (SystemClock.elapsedRealtimeNanos() - lastUiUpdate
                    > 500_000_000L) {
                lastUiUpdate = SystemClock.elapsedRealtimeNanos();

                main.post(() -> {
                    if (token != generation || destroyed) return;
                    frameReady = true;
                    showState();
                });
            }

        } catch (Exception error) {
            if (current != null && current.accepting) {
                current.gate.resetStreaks();
                log(current, "DETECTION_ERROR",
                        VisionLog.data("message",
                                String.valueOf(error.getMessage())));
            }

            long now = SystemClock.elapsedRealtimeNanos();
            if (now - lastUiUpdate > 1_000_000_000L) {
                lastUiUpdate = now;
                main.post(() -> {
                    if (token == generation && !destroyed) {
                        status("识别异常：" + error.getMessage());
                    }
                });
            }

        } finally {
            if (upright != null) upright.recycle();
            if (source != null) source.recycle();
            image.close();
        }
    }

    private boolean render(int token, Frame frame) {
        if (token != generation || destroyed || !wanted) return false;

        long timestamp = frame.getTimestampNanos();
        lastRendered.set(timestamp);

        Sample sample = samples.remove(timestamp);
        Run current = run;

        if (current != null && current.accepting &&
                current.saveVideo &&
                timestamp < current.firstSensorNs) {
            return false;
        }

        Canvas canvas = frame.getOverlayCanvas();
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        Matrix toUpright = rotationMatrix(
                frame.getSize().getWidth(),
                frame.getSize().getHeight(),
                frame.getRotationDegrees());

        Matrix uprightToBuffer = new Matrix();
        toUpright.invert(uprightToBuffer);

        Matrix sensorToUpright = new Matrix();
        sensorToUpright.setConcat(
                toUpright, frame.getSensorToBufferTransform());

        RectF visible = new RectF(frame.getCropRect());
        toUpright.mapRect(visible);

        canvas.save();
        canvas.clipRect(frame.getCropRect());
        canvas.concat(uprightToBuffer);

        if (sample != null) {
            List<float[]> boxes = new ArrayList<>();

            for (float[] box : sample.sensorBoxes) {
                RectF rect = new RectF(
                        box[0], box[1], box[2], box[3]);
                sensorToUpright.mapRect(rect);

                boxes.add(new float[]{
                        rect.left, rect.top, rect.right, rect.bottom,
                        box[4]
                });
            }

            paintBoxes(canvas, boxes, sample.confirmed);
            paintCaption(canvas,
                    sample.confirmed ? "OIL CONFIRMED" : "YOLO",
                    visible.left + 12, visible.top + 28);

            if (sample.eventNumber != 0 && sample.owner != null) {
                worker.execute(() -> log(
                        sample.owner, "TRIGGER_FRAME_RENDERED",
                        VisionLog.data(
                                "event_number", sample.eventNumber,
                                "sensor_ns", timestamp,
                                "note",
                                "已合成到相机输出，不单独证明编码文件写入成功")));
            }
        } else {
            // 未取得本帧结果时，不能把上一帧的旧框贴过来。
            paintCaption(canvas, "YOLO: NO FRAME RESULT",
                    visible.left + 12, visible.top + 28);
        }

        canvas.restore();
        return true;
    }

    private static Matrix rotationMatrix(int width, int height, int degrees) {
        Matrix matrix = new Matrix();

        switch (degrees) {
            case 90:
                matrix.setValues(new float[]{
                        0, -1, height,
                        1, 0, 0,
                        0, 0, 1});
                break;
            case 180:
                matrix.setValues(new float[]{
                        -1, 0, width,
                        0, -1, height,
                        0, 0, 1});
                break;
            case 270:
                matrix.setValues(new float[]{
                        0, 1, 0,
                        -1, 0, width,
                        0, 0, 1});
                break;
            default:
                break;
        }

        return matrix;
    }

    private static void paintBoxes(
            Canvas canvas, List<float[]> boxes, boolean confirmed) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(confirmed ? Color.RED : Color.YELLOW);
        paint.setStrokeWidth(3);
        paint.setTextSize(22);

        for (float[] box : boxes) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(box[0], box[1], box[2], box[3], paint);

            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(
                    String.format(Locale.US, "oil %.2f", box[4]),
                    box[0], Math.max(24, box[1] - 5), paint);
        }
    }

    private static void paintCaption(
            Canvas canvas, String text, float x, float y) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(22);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(3, 1, 1, Color.BLACK);
        canvas.drawText(text, x, y, paint);
    }

    // 只在 worker 线程写日志。
    private void log(Run current, String event, JSONObject details) {
        if (current.logClosed || current.logFailed) return;

        try {
            if (current.log == null) {
                current.log = new VisionLog(activity, current.id);
            }

            details.put("session_id", current.id);
            current.log.write(event, details);

        } catch (Exception error) {
            current.logFailed = true;
            current.storageError = true;
            notifyError("检测日志写入失败：" + error.getMessage());
        }
    }

    private void releaseCamera() {
        generation++;
        wanted = false;
        preparing = false;
        bound = false;
        frameReady = false;

        if (analysis != null) {
            analysis.clearAnalyzer();
            analysis = null;
        }

        if (provider != null) {
            provider.unbindAll();
            provider = null;
        }

        if (effect != null) {
            effect.close();
            effect = null;
        }

        samples.clear();
        pendingRecording = null;
        preparedId = null;
        preview.setVisibility(View.INVISIBLE);
    }

    private void showState() {
        Run current = run;

        if (closing) {
            status("正在保存录像、关键帧和日志……");
        } else if (current != null) {
            String recordingText = !current.saveVideo
                    ? "不保存视频"
                    : current.videoError
                    ? "录像异常"
                    : current.videoStarted
                    ? "录像 " + current.videoDurationUs / 1_000_000 + " 秒"
                    : "录像启动中";

            status("运行检测中 · " + recordingText
                    + " · 已触发 " + current.events + " 次");
        } else if (bound && frameReady) {
            status("预览、识别已就绪；录像待确认执行");
        } else if (wanted) {
            status("正在准备预览、识别与录像配置……");
        } else {
            status("相机关闭");
        }
    }

    private void status(String text) {
        if (!destroyed) listener.onStatus(text);
    }

    private void notifyError(String text) {
        main.post(() -> {
            if (!destroyed) listener.onError(text);
        });
    }

    private void fatal(String text) {
        if (!destroyed) listener.onError(text);
        stop("vision_error");
    }

    public void close() {
        destroyed = true;
        stop("activity_destroyed");
    }

    private void closeExecutors() {
        if (executorsClosed) return;
        executorsClosed = true;

        worker.execute(detector::release);
        worker.shutdown();

        renderHandler.post(renderThread::quitSafely);
    }

    private static String newId() {
        return "Fish_" + new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static final class Run {
        final String id;
        final boolean saveVideo;
        final long requestNs = SystemClock.elapsedRealtimeNanos();
        final Gate gate = new Gate();

        volatile boolean accepting = true;
        volatile long firstSensorNs = Long.MAX_VALUE;
        volatile long videoDurationUs;
        volatile int events;

        boolean videoRequested;
        boolean videoStarted;
        boolean videoError;
        boolean finishing;
        boolean storageError;
        String videoUri = "";
        String reason = "stopped";

        // 日志字段只由 worker 线程使用。
        VisionLog log;
        boolean logFailed;
        boolean logClosed;

        Run(String id, boolean saveVideo) {
            this.id = id;
            this.saveVideo = saveVideo;
        }
    }

    private static final class Sample {
        final List<float[]> sensorBoxes;
        final boolean confirmed;
        final Run owner;
        final int eventNumber;

        Sample(List<float[]> sensorBoxes, boolean confirmed,
               Run owner, int eventNumber) {
            this.sensorBoxes = sensorBoxes;
            this.confirmed = confirmed;
            this.owner = owner;
            this.eventNumber = eventNumber;
        }
    }

    private static final class Gate {
        int hits;
        int misses;
        boolean confirmed;
        long lastValidNs;

        int update(float best, long nowNs) {
            if (lastValidNs != 0 &&
                    nowNs - lastValidNs > 1_000_000_000L) {
                resetStreaks();
            }

            lastValidNs = nowNs;

            if (best >= 0.50f) {
                hits = Math.min(3, hits + 1);
                misses = 0;
            } else if (best >= 0.35f) {
                resetStreaks();
            } else {
                hits = 0;
                misses = Math.min(5, misses + 1);
            }

            if (!confirmed && hits >= 3) {
                confirmed = true;
                misses = 0;
                return 1;
            }

            if (confirmed && misses >= 5) {
                confirmed = false;
                misses = 0;
                return -1;
            }

            return 0;
        }

        void resetStreaks() {
            hits = 0;
            misses = 0;
        }
    }
}
