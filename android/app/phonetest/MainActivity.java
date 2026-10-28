package com.fish.phonetailtest;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.*;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;



public class MainActivity extends ComponentActivity {

    private static final String USB_PERMISSION =
            "com.fish.phonetailtest.USB_PERMISSION";

    private UsbManager usb;
    private UsbDevice selected;
    private TailUsb tail;

    private PreviewView preview;
    private TextView recordingInfo;
    private TextView videoMessage;
    private TextView routeVisionHint;

    private Button runStartButton;
    private Button runStopButton;

    private OilVision vision;
    private String visionText = "相机关闭，模型准备中";

    private boolean foreground;
    private boolean connecting;
    private boolean checking;
    private boolean pendingWizard;
    private boolean wizard;
    private boolean active;
    private boolean stopping;
    private boolean recordWithRoute;
    private boolean cameraPermissionPending;

    private AlertDialog parameterDialog;
    private AlertDialog noticeDialog;
    private RouteConfig last = new RouteConfig();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        usb = (UsbManager) getSystemService(USB_SERVICE);
        loadSettings();

        tail = new TailUsb(usb, (event, message) -> {
            if (!foreground) return;

            switch (event) {
                case READY:
                case CHECKED:
                    connecting = false;
                    checking = false;

                    if (tail.running) {
                        pendingWizard = false;
                        active = true;
                        stopping = false;
                        toast("单片机已有运行任务，点击结束运行");
                    } else if (pendingWizard) {
                        pendingWizard = false;
                        active = false;
                        openWizard();
                    }
                    break;

                case STARTED:
                    active = true;
                    stopping = false;
                    toast("开始执行五段路线");
                    break;

                case FINISHING:
                    active = false;

                    if (!stopping) {
                        stopping = true;

                        if (vision != null) {
                            vision.stop("route_complete");
                        }
                    }
                    break;

                case STOPPED:
                    active = false;
                    stopping = false;
                    if (vision != null) vision.stop("route_complete");
                    toast(message);
                    break;

                case ERROR:
                    connecting = false;
                    checking = false;
                    pendingWizard = false;
                    active = false;
                    stopping = false;
                    cancelWizard();
                    showError("USB通信问题", message);
                    break;
            }

            refresh();
        });

        createUi();

        vision = new OilVision(this, preview, new OilVision.Listener() {
            @Override
            public void onStatus(String text) {
                if (isDestroyed()) return;
                visionText = text;
                refresh();
            }

            @Override
            public void onTrigger(String eventId) {
                final long requestNs =
                        SystemClock.elapsedRealtimeNanos();
                final long requestWallMs =
                        System.currentTimeMillis();

                if (isDestroyed() ||
                        !foreground || !active || stopping) {

                    saveVoiceResult(
                            eventId,
                            requestNs,
                            requestWallMs,
                            SystemClock.elapsedRealtimeNanos(),
                            "SKIPPED",
                            "当前不处于前台运行状态");
                    return;
                }

                videoMessage.setText(
                        "已确认漏油：" + eventId +
                        "\n正在发送语音触发指令" +
                        "\n关键帧：Pictures/FishTail" +
                        "\n日志：Download/FishTail");

                tail.triggerVoice(eventId, (status, message) -> {
                    long resultNs =
                            SystemClock.elapsedRealtimeNanos();

                    // 即使路线刚结束或Activity正在销毁，
                    // 也先保存已经返回的指令结果。
                    saveVoiceResult(
                            eventId,
                            requestNs,
                            requestWallMs,
                            resultNs,
                            status,
                            message);

                    if (isDestroyed() || !foreground) return;

                    videoMessage.setText(
                            "漏油事件：" + eventId +
                            "\n" + message +
                            "\n关键帧：Pictures/FishTail" +
                            "\n回执日志：" + eventId + "_voice.jsonl");

                    if ("QUEUED".equals(status)) {
                        toast("漏油事件已提交语音触发");
                    } else if (!"DUPLICATE".equals(status)) {
                        toast(message);
                    }
                });
            }

            @Override
            public void onError(String text) {
                if (isDestroyed()) return;
                videoMessage.setText(text);
                showError("视觉或录像提示", text);
            }
        });

        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void saveVoiceResult(
            String eventId,
            long requestNs,
            long requestWallMs,
            long resultNs,
            String status,
            String message) {

        final Context appContext = getApplicationContext();

        // 每个事件独立保存回执文件。
        // 不依赖视觉会话的日志是否已经关闭。
        new Thread(() -> {
            try (VisionLog log = new VisionLog(
                    appContext, eventId + "_voice")) {

                log.write(
                        "VOICE_RESULT",
                        VisionLog.data(
                                "event_id", eventId,
                                "request_wall_time_ms", requestWallMs,
                                "request_elapsed_ns", requestNs,
                                "result_elapsed_ns", resultNs,
                                "request_to_result_ms",
                                (resultNs - requestNs) / 1_000_000.0,
                                "status", status,
                                "message", message,
                                "gpio", "PB1",
                                "run_gpio", "PB11",
                                "voice_protocol", "RUN_LEVEL_AND_LEAK_PULSE",
                                "active_level", "LOW",
                                "pulse_low_ms", 100,
                                "min_release_ms", 100,
                                "playback_confirmed", false));

            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && foreground) {
                        videoMessage.setText(
                                "语音回执日志保存失败：" +
                                String.valueOf(error.getMessage()));
                    }
                });
            }
        }, "VoiceReceipt").start();
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    dp(8),
                    dp(8) + insets.getSystemWindowInsetTop(),
                    dp(8),
                    dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        // 顶部直接显示相机，不再放USB或舵机状态文字。
        preview = new PreviewView(this);
        preview.setImplementationMode(
                PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setVisibility(View.INVISIBLE);
        root.addView(preview,
                new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout runRow = new LinearLayout(this);
        runStartButton = addButton(
                runRow, "开始运行", this::requestRun);
        runStopButton = addButton(
                runRow, "结束运行", this::endRun);
        root.addView(runRow);

        recordingInfo = new TextView(this);
        recordingInfo.setTextSize(15);
        recordingInfo.setPadding(dp(6), dp(6), dp(6), dp(4));
        root.addView(recordingInfo);

        videoMessage = new TextView(this);
        videoMessage.setTextSize(12);
        videoMessage.setTextIsSelectable(true);
        videoMessage.setText(
                "视频：Movies/FishTail\n" +
                "关键帧：Pictures/FishTail\n" +
                "日志：Download/FishTail");

        ScrollView videoDetails = new ScrollView(this);
        videoDetails.addView(videoMessage);
        root.addView(videoDetails,
                new LinearLayout.LayoutParams(-1, dp(65)));

        setContentView(root);
        refresh();
    }

    private Button addButton(
            LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        parent.addView(button,
                new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private int dp(int value) {
        return Math.round(value *
                getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        if (foreground) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }

    private void showError(String title, String message) {
        if (!foreground || isFinishing() || isDestroyed()) return;

        if (noticeDialog != null) noticeDialog.dismiss();

        noticeDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .create();

        noticeDialog.show();
    }

    private void refresh() {
        if (recordingInfo == null) return;

        recordingInfo.setText(visionText);

        if (routeVisionHint != null) {
            routeVisionHint.setText(visionText);
        }

        runStartButton.setEnabled(foreground &&
                !active && !stopping && !wizard &&
                !connecting && !checking && !pendingWizard &&
                (vision == null || !vision.isBusy()));

        runStopButton.setEnabled(foreground && !stopping);
    }

    private void loadSettings() {
        String text = getSharedPreferences(
                "route_five_v1", MODE_PRIVATE)
                .getString("route", null);

        last = RouteConfig.decode(text);
    }

    private void saveSettings(RouteConfig c) {
        c.validate();
        last = c.copy();

        getSharedPreferences("route_five_v1", MODE_PRIVATE)
                .edit()
                .putString("route", c.encode())
                .apply();
    }

    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (USB_PERMISSION.equals(action)) {
                connecting = false;

                if (!foreground || selected == null) return;

                if (usb.hasPermission(selected)) {
                    connecting = true;
                    tail.open(selected);
                } else {
                    pendingWizard = false;
                    checking = false;
                    showError("USB未授权",
                            "请允许应用访问开发板USB设备，" +
                            "然后重新点击开始运行。");
                }

            } else if (
                    UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (foreground) connectUsb();

            } else if (
                    UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE);

                if (d != null && selected != null &&
                        d.getDeviceId() == selected.getDeviceId()) {
                    selected = null;
                    connecting = false;
                    checking = false;
                    pendingWizard = false;
                    active = false;
                    stopping = false;

                    cancelWizard();
                    tail.close();

                    showError("USB已断开",
                            "运行指令已停止发送，回中指令可能未送达。" +
                            "\n视觉和录像已结束。请检查单片机的实际运行状态。");
                }
            }

            refresh();
        }
    };

    private void connectUsb() {
        if (!foreground || connecting || tail.ready) return;

        selected = null;

        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == 0x0483 &&
                    d.getProductId() == 0x5740) {
                selected = d;
                break;
            }
        }

        if (selected == null) {
    pendingWizard = false;
    checking = false;

    StringBuilder details = new StringBuilder();
    details.append("USB检查版 v5\n");
    details.append("目标设备：0483:5740\n\n");

    int count = 0;

    for (UsbDevice d : usb.getDeviceList().values()) {
        count++;

        details.append(String.format(
                Locale.US,
                "设备%d：VID=%04X PID=%04X\n",
                count,
                d.getVendorId(),
                d.getProductId()
        ));

        details.append("接口数量：")
                .append(d.getInterfaceCount())
                .append("\nUSB权限：")
                .append(usb.hasPermission(d))
                .append("\n\n");
    }

    if (count == 0) {
        details.append(
                "手机当前没有识别到任何USB外设。\n" +
                "请检查数据线、OTG转接方式及开发板连接。"
        );
    } else {
        details.append("已识别USB外设，但未匹配到目标开发板。");
    }

    showError("USB设备检查", details.toString());
    refresh();
    return;
}

        connecting = true;

        try {
            if (usb.hasPermission(selected)) {
                tail.open(selected);
            } else {
                PendingIntent permission =
                        PendingIntent.getBroadcast(
                                this, 0,
                                new Intent(USB_PERMISSION)
                                        .setPackage(getPackageName()),
                                PendingIntent.FLAG_UPDATE_CURRENT |
                                        PendingIntent.FLAG_IMMUTABLE);

                usb.requestPermission(selected, permission);
            }
        } catch (Exception e) {
            connecting = false;
            checking = false;
            pendingWizard = false;

            showError("USB连接失败", String.valueOf(e.getMessage()));
        }

        refresh();
    }

    private void requestRun() {
        if (active || stopping || wizard || checking) return;

        pendingWizard = true;

        if (!tail.ready) {
            connectUsb();
        } else {
            // 参数输入之前做一次新的通信检查。
            checking = true;
            tail.check();
        }

        refresh();
    }
  

    private void openWizard() {
        if (!foreground || !tail.ready) return;

        recordWithRoute = false;
        wizard = true;
        refresh();
        showRoute(last.copy());
    }

    private void showRoute(RouteConfig draft) {
        if (!foreground || !tail.ready) {
            wizardEnded();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        for (int i = 0; i < 5; i++) {
            final int stage = i;

            Button row = new Button(this);
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setText(draft.row(i));
            row.setOnClickListener(v -> {
                if (parameterDialog != null) {
                    parameterDialog.dismiss();
                }
                editStage(draft, stage);
            });

            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        TextView total = new TextView(this);
        total.setText(draft.totalText());
        box.addView(total);

        CheckBox recordOption = new CheckBox(this);
        recordOption.setText("开启录像");
        recordOption.setChecked(recordWithRoute);
        recordOption.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        recordOption.setOnCheckedChangeListener((button, checked) -> {
            recordWithRoute = checked;

            if (checked) {
                // 只启动预览与识别，不开始保存视频。
                ensureVision();
            } else if (vision != null) {
                vision.stop("record_option_unchecked");
            }
        });

        LinearLayout.LayoutParams optionParams =
                new LinearLayout.LayoutParams(-2, -2);
        optionParams.gravity = Gravity.START;
        optionParams.topMargin = dp(8);
        box.addView(recordOption, optionParams);

        routeVisionHint = new TextView(this);
        routeVisionHint.setTextSize(12);
        routeVisionHint.setText(visionText);
        box.addView(routeVisionHint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog current = new AlertDialog.Builder(this)
                .setTitle("五段路线参数")
                .setView(scroll)
                .setPositiveButton("确认执行", null)
                .setNegativeButton("取消", (d, w) -> cancelWizard())
                .setOnCancelListener(d -> cancelWizard())
                .create();

        parameterDialog = current;
        current.show();

        current.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        draft.validate();

                        if (!foreground || !tail.ready) {
                            throw new IllegalArgumentException("USB未连接");
                        }

                        saveSettings(draft);

                    } catch (Exception e) {
                        showError("无法开始",
                                String.valueOf(e.getMessage()));
                        return;
                    }

                    current.dismiss();
                    wizardEnded();

                    active = true;
                    stopping = false;

                    // 必须先提交运动指令。
                    tail.startRoute(draft);

                    // 不等待运动回复，也不让相机准备阻塞运动指令。
                    // 此处才允许开始保存录像、日志和触发关键帧。
                    vision.startRun(recordWithRoute);
                    ensureVision();

                    refresh();
                });
    }

    private void editStage(RouteConfig draft, int stage) {
        if (!foreground || !tail.ready) {
            wizardEnded();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText(RouteConfig.hint(stage) + "\n留空保留当前值。");
        box.addView(hint);

        String[] labels = RouteConfig.labels(stage);
        EditText[] inputs = new EditText[3];

        for (int j = 0; j < 3; j++) {
            TextView label = new TextView(this);
            label.setText(labels[j]);
            box.addView(label);

            boolean decimal =
                    !(RouteConfig.straight(stage) && j == 2);

            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setKeyListener(DigitsKeyListener.getInstance(
                    decimal
                            ? "0123456789.,０１２３４５６７８９．，。"
                            : "0123456789０１２３４５６７８９"));

            input.setRawInputType(
                    InputType.TYPE_CLASS_NUMBER
                            | (decimal
                                ? InputType.TYPE_NUMBER_FLAG_DECIMAL
                                : 0));

            input.setText(RouteConfig.number(draft.values[stage][j]));
            input.setSelectAllOnFocus(true);
            inputs[j] = input;

            LinearLayout row = new LinearLayout(this);
            row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

            if (decimal) {
                Button dot = new Button(this);
                dot.setText("小数点");
                dot.setOnClickListener(v -> insertDecimal(input));
                row.addView(dot);
            }

            box.addView(row);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog current = new AlertDialog.Builder(this)
                .setTitle(RouteConfig.NAMES[stage])
                .setView(scroll)
                .setPositiveButton("保存并返回", null)
                .setNegativeButton(
                        "返回不保存", (d, w) -> showRoute(draft))
                .setOnCancelListener(d -> showRoute(draft))
                .create();

        parameterDialog = current;
        current.show();

        current.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        RouteConfig next = draft.copy();

                        for (int j = 0; j < 3; j++) {
                            String text = RouteConfig.normalize(
                                    inputs[j].getText().toString());

                            if (!text.isEmpty()) {
                                next.values[stage][j] =
                                        Double.parseDouble(text);
                            }
                        }

                        next.validateStage(stage);
                        saveSettings(next);

                        current.dismiss();
                        showRoute(next);

                    } catch (Exception e) {
                        toast(e.getMessage() == null
                                ? "请输入有效数字"
                                : e.getMessage());
                    }
                });
    }

    private void wizardEnded() {
        wizard = false;
        parameterDialog = null;
        routeVisionHint = null;
        refresh();
    }

    private void cancelWizard() {
        if (parameterDialog != null) {
            parameterDialog.dismiss();
        }

        if (vision != null) {
            vision.stop("cancelled");
        }

        wizardEnded();
    }

    private void insertDecimal(EditText input) {
        String text = input.getText().toString();

        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        int from = Math.min(start, end);
        int to = Math.max(start, end);

        String candidate =
                text.substring(0, from) + "." + text.substring(to);

        if (!RouteConfig.normalize(candidate)
                .matches("\\d*\\.?\\d*")) {
            input.setError("只能输入一个小数点");
            return;
        }

        input.getText().replace(from, to, ".");
        input.setSelection(from + 1);
        input.requestFocus();
    }

    private void endRun() {
        boolean wasRunning = active || tail.running;

        // 优先提交停止运动指令。
        if (wasRunning && !stopping) {
            stopping = true;
            tail.stop();
        }

        pendingWizard = false;
        checking = false;

        if (parameterDialog != null) {
            parameterDialog.dismiss();
        }
        wizardEnded();

        if (vision != null) {
            vision.stop("manual_stop");
        }

        if (!wasRunning) {
            toast("已取消准备并关闭相机");
        }

        refresh();
    }

    private void ensureVision() {
        if (!foreground || vision == null) return;

        boolean needed = active || (wizard && recordWithRoute);
        if (!needed) return;

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (!cameraPermissionPending) {
                cameraPermissionPending = true;
                requestPermissions(
                        new String[]{Manifest.permission.CAMERA}, 10);
            }
            return;
        }

        vision.prepare(recordWithRoute);
    }

    @Override
    public void onRequestPermissionsResult(
            int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);

        if (code != 10) return;
        cameraPermissionPending = false;

        if (!foreground) return;

        if (results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
            ensureVision();
        } else {
            if (vision != null) {
                vision.stop("camera_permission_denied");
            }

            showError("未获得摄像头权限",
                    "本次无法检测和录像；已经发送的运动指令不受影响。");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;

        // 打开应用时自动检测USB，但不自动运行或录像。
        connectUsb();
        refresh();
    }

    @Override
    protected void onStop() {
        foreground = false;

        pendingWizard = false;
        checking = false;
        connecting = false;
        active = false;
        stopping = false;

        cancelWizard();

        if (noticeDialog != null) {
            noticeDialog.dismiss();
            noticeDialog = null;
        }

        // 离开应用时释放资源，并尽力发送回中指令。
        if (vision != null) vision.stop("background");
        tail.close();

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        tail.destroy();
        if (vision != null) vision.close();
        super.onDestroy();
    }
}
