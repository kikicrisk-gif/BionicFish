package com.fish.phonetailtest;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;

public class MainActivity extends ComponentActivity {

    private static final String PREFS = "center_turn_experiment_v1";
    private static final String USB_PERMISSION =
            "com.fish.phonetailtest.centerexperiment.USB_PERMISSION";

    private UsbManager usb;
    private UsbDevice selected;
    private TailUsb tail;
    private OilVision vision;
    private ManualConfig config;

    private PreviewView preview;
    private TextView cameraPlaceholder;
    private TextView motionStatus;
    private TextView cameraStatus;

    private Button leftButton;
    private Button rightButton;
    private HoldTurnControls holdTurns;
    private Button settingsButton;
    private Button cameraButton;
    private Button startButton;
    private Button stopButton;

    private AlertDialog settingsDialog;

    private boolean foreground;
    private boolean connecting;
    private boolean pendingStart;
    private boolean active;
    private boolean stopping;
    private boolean cameraWanted;
    private boolean cameraPermissionPending;

    private String visionText = "相机关闭";
    private String connectionText = "等待连接主控";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        config = ManualConfig.decode(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("config", null));

        usb = (UsbManager) getSystemService(USB_SERVICE);

        tail = new TailUsb(usb, (event, message) -> {
            if (!foreground) return;

            switch (event) {
                case READY:
                case CHECKED:
                    connecting = false;
                    connectionText = "实验主控已连接";
                    active = tail.running;

                    if (pendingStart) {
                        pendingStart = false;

                        if (tail.running) {
                            toast("主控正在运行，请先点击结束运行");
                        } else {
                            beginRun();
                        }
                    }
                    break;

                case STARTED:
                    active = true;
                    stopping = false;
                    connectionText = "实验主控已连接";

                    if (cameraWanted) {
                        startVisionForRun();
                    }
                    break;

                case MANUAL_UPDATE:
                    break;

                case FINISHING:
                    active = false;
                    stopping = true;
                    closeCamera("motion_finishing");
                    break;

                case STOPPED:
                    active = false;
                    stopping = false;
                    pendingStart = false;
                    closeCamera("motion_stopped");
                    toast("运行已结束");
                    break;

                case ERROR:
                    connecting = false;
                    pendingStart = false;
                    active = false;
                    stopping = false;
                    connectionText = message;
                    closeCamera("usb_error");
                    toast(message);
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

                if (vision != null &&
                        !cameraPermissionPending &&
                        !vision.isClosing()) {
                    cameraWanted = vision.isCameraRequested();
                }

                refresh();
            }

            @Override
            public void onTrigger(String eventId) {
                if (!foreground || !active ||
                        stopping || !tail.manualRunning) {
                    return;
                }

                final long requestNs =
                        SystemClock.elapsedRealtimeNanos();

                final long requestWallMs =
                        System.currentTimeMillis();

                tail.triggerVoice(eventId, (status, message) -> {
                    saveVoiceResult(
                            eventId,
                            requestNs,
                            requestWallMs,
                            status,
                            message);

                    if (!isDestroyed() && foreground) {
                        toast(message);
                    }
                });
            }

            @Override
            public void onError(String text) {
                if (isDestroyed()) return;
                visionText = text;
                toast(text);
                refresh();
            }
        });

        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    dp(8),
                    dp(8) + insets.getSystemWindowInsetTop(),
                    dp(8),
                    dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        FrameLayout cameraBox = new FrameLayout(this);
        cameraBox.setBackgroundColor(Color.rgb(25, 28, 30));

        preview = new PreviewView(this);
        preview.setImplementationMode(
                PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setVisibility(View.INVISIBLE);

        cameraBox.addView(
                preview,
                new FrameLayout.LayoutParams(-1, -1));

        cameraPlaceholder = new TextView(this);
        cameraPlaceholder.setText(
                "相机已关闭\n点击“打开相机”查看起始位置");
        cameraPlaceholder.setTextColor(Color.LTGRAY);
        cameraPlaceholder.setTextSize(17);
        cameraPlaceholder.setGravity(Gravity.CENTER);

        cameraBox.addView(
                cameraPlaceholder,
                new FrameLayout.LayoutParams(-1, -1));

        root.addView(
                cameraBox,
                new LinearLayout.LayoutParams(-1, 0, 1));

        motionStatus = new TextView(this);
        motionStatus.setTextSize(15);
        motionStatus.setPadding(dp(4), dp(6), dp(4), dp(4));
        motionStatus.setMaxLines(2);
        root.addView(motionStatus);

        LinearLayout turnRow = new LinearLayout(this);

        leftButton = addButton(
                turnRow, "左转 · 按住加挡", () -> { });

        rightButton = addButton(
                turnRow, "右转 · 按住加挡", () -> { });

        holdTurns = new HoldTurnControls(
                leftButton,
                rightButton,
                level -> tail.setLevel(level),
                () -> (int) config.values[3]);

        root.addView(turnRow);

        LinearLayout optionsRow = new LinearLayout(this);

        settingsButton = addButton(
                optionsRow, "执行参数", this::showSettings);

        cameraButton = addButton(
                optionsRow, "打开相机", this::toggleCamera);

        root.addView(optionsRow);

        LinearLayout runRow = new LinearLayout(this);

        startButton = addButton(
                runRow, "开始运行", this::requestRun);

        stopButton = addButton(
                runRow, "结束运行", this::endRun);

        root.addView(runRow);

        cameraStatus = new TextView(this);
        cameraStatus.setTextSize(12);
        cameraStatus.setMaxLines(2);
        cameraStatus.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(cameraStatus);

        TextView storage = new TextView(this);
        storage.setText("日志保存在 …/Fishtail");
        storage.setTextSize(12);
        storage.setPadding(dp(4), dp(2), dp(4), dp(2));
        root.addView(storage);

        setContentView(root);
        refresh();
    }

    private Button addButton(
            LinearLayout parent,
            String text,
            Runnable action) {

        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setOnClickListener(view -> action.run());

        parent.addView(
                button,
                new LinearLayout.LayoutParams(0, -2, 1));

        return button;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        if (motionStatus == null) return;

        boolean idle = foreground &&
                !active && !tail.running && !stopping &&
                !pendingStart && !connecting;

        boolean closingCamera =
                vision != null && vision.isClosing();

        boolean busyVision =
                vision != null && vision.isBusy();

        startButton.setEnabled(
                idle && settingsDialog == null &&
                        !busyVision && !cameraPermissionPending);

        stopButton.setEnabled(
                foreground && !stopping &&
                        (active || tail.running || pendingStart));

        settingsButton.setEnabled(
                idle && settingsDialog == null);

        boolean canTurn = foreground &&
                active && tail.ready && tail.manualRunning &&
                !tail.manualTurning && !stopping;

        if (holdTurns != null) {
            holdTurns.setEnabled(canTurn);
        }

        cameraButton.setEnabled(
                foreground && !closingCamera &&
                        !cameraPermissionPending &&
                        !pendingStart && !connecting);

        cameraButton.setText(
                cameraWanted ? "关闭相机" : "打开相机");

        cameraPlaceholder.setVisibility(
                cameraWanted ? View.GONE : View.VISIBLE);

        String text;

        if (stopping) {
            text = "正在结束运行并回中";
        } else if (pendingStart || connecting) {
            text = "正在检查主控连接";
        } else if (tail.manualRunning) {
            int level = tail.manualLevel;

            if (level == 0) {
                text = "直游 · 0挡 · 中心目标90°";
            } else {
                int physicalLevel = config.reverse ? -level : level;
                int center = 90 + physicalLevel * 10;

                text = (level < 0 ? "左转" : "右转")
                        + " · " + Math.abs(level) + "/6挡"
                        + " · 中心目标" + center + "°";
            }
        } else if (active || tail.running) {
            text = "主控正在运行 · 可点击结束运行";
        } else if (tail.ready) {
            text = "已就绪 · 使用已保存参数";
        } else {
            text = connectionText;
        }

        motionStatus.setText(text);
        cameraStatus.setText(visionText);
    }

    private void showSettings() {
        if (active || tail.running || stopping ||
                pendingStart || settingsDialog != null) {
            return;
        }

        ManualConfig draft = config.copy();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView hint = new TextView(this);
        hint.setText(
                "直游中心90°，持续到点击结束运行。\n" +
                "按下左右按钮立即进入1挡，按住按设定间隔逐级增加，最多6挡。\n" +
                "每挡中心偏移10°；左六挡30°，右六挡150°。\n" +
                "松手恢复0挡，继续围绕90°摆动。\n" +
                "完整摆动周期和长按加挡间隔是两个独立参数。\n" +
                "修改参数在下次开始运行时生效。");
        box.addView(hint);

        EditText[] inputs =
                new EditText[ManualConfig.LABELS.length];

        for (int i = 0; i < inputs.length; i++) {
            TextView label = new TextView(this);
            label.setText(ManualConfig.LABELS[i]);
            box.addView(label);

            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setInputType(
                    InputType.TYPE_CLASS_NUMBER |
                            InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setText(RouteConfig.number(draft.values[i]));
            input.setSelectAllOnFocus(true);

            inputs[i] = input;
            box.addView(input);
        }

        CheckBox reverse = new CheckBox(this);
        reverse.setText(
                "交换左右按钮（中心参数仍按舵机角度方向定义）");
        reverse.setChecked(draft.reverse);
        box.addView(reverse);

        CheckBox record = new CheckBox(this);
        record.setText("相机开启且鱼运行时保存视频");
        record.setChecked(draft.recordVideo);
        box.addView(record);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("执行参数")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        settingsDialog = dialog;

        dialog.setOnDismissListener(ignored -> {
            settingsDialog = null;
            refresh();
        });

        dialog.show();
        refresh();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        for (int i = 0; i < inputs.length; i++) {
                            draft.values[i] = Double.parseDouble(
                                    RouteConfig.normalize(
                                            inputs[i].getText().toString()));
                        }

                        draft.reverse = reverse.isChecked();
                        draft.recordVideo = record.isChecked();
                        draft.validate();

                        boolean saved = getSharedPreferences(
                                PREFS, MODE_PRIVATE)
                                .edit()
                                .putString("config", draft.encode())
                                .commit();

                        if (!saved) {
                            throw new IllegalStateException(
                                    "参数保存失败，请重试");
                        }

                        config = draft.copy();
                        dialog.dismiss();
                        toast("参数已保存，下次运行直接沿用");

                    } catch (Exception error) {
                        toast(error.getMessage() == null
                                ? "请输入有效参数"
                                : error.getMessage());
                    }
                });
    }

    private void requestRun() {
        if (active || tail.running || stopping ||
                pendingStart || connecting) {
            return;
        }

        pendingStart = true;

        if (tail.ready) {
            tail.check();
        } else {
            connectUsb(true);
        }

        refresh();
    }

    private void beginRun() {
        if (!foreground || !tail.ready ||
                !tail.manualSupported) {
            toast("请先连接配套的实验主控固件");
            return;
        }

        try {
            config.validate();
            active = true;
            stopping = false;
            tail.startManual(config);
        } catch (Exception error) {
            active = false;
            toast(error.getMessage());
        }

        refresh();
    }

    private void endRun() {
        if (holdTurns != null) holdTurns.release();

        pendingStart = false;

        if ((active || tail.running) && !stopping) {
            stopping = true;
            tail.stop();
        }

        closeCamera("manual_stop");
        refresh();
    }

    private boolean cameraGranted() {
        return checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void toggleCamera() {
        if (vision == null || vision.isClosing()) return;

        if (cameraWanted) {
            closeCamera("camera_button");
            refresh();
            return;
        }

        if (!cameraGranted()) {
            // 系统授权窗口可能让Activity暂停，因此先在静止时授权。
            if (active || tail.running) {
                toast("请先结束运行，再打开相机完成首次授权");
                return;
            }

            cameraWanted = true;
            cameraPermissionPending = true;

            requestPermissions(
                    new String[]{Manifest.permission.CAMERA}, 10);

            refresh();
            return;
        }

        openCamera();
    }

    private void openCamera() {
        if (!foreground || vision == null ||
                vision.isClosing() || !cameraGranted()) {
            return;
        }

        cameraWanted = true;

        if (active && tail.manualRunning && !stopping) {
            startVisionForRun();
        } else {
            // 预览可以在未连接主控、未运行鱼时单独打开。
            vision.prepare(true);
        }

        refresh();
    }

    private void startVisionForRun() {
        if (vision == null || !cameraWanted ||
                !cameraGranted() || vision.isClosing()) {
            return;
        }

        if (!vision.isRunning()) {
            vision.startRun(config.recordVideo);
        }
    }

    private void closeCamera(String reason) {
        cameraWanted = false;

        if (vision != null) {
            vision.stop(reason);
        }
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
                    pendingStart = false;
                    connectionText = "USB未授权";
                    toast("请允许实验版软件访问主控USB");
                }

            } else if (
                    UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (foreground) connectUsb(false);

            } else if (
                    UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {

                UsbDevice device = intent.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE);

                if (device != null && selected != null &&
                        device.getDeviceId() == selected.getDeviceId()) {

                    selected = null;
                    connecting = false;
                    pendingStart = false;
                    active = false;
                    stopping = false;
                    connectionText = "USB已断开";

                    tail.close();
                    closeCamera("usb_detached");

                    toast("USB已断开；主控将按通信超时保护停止");
                }
            }

            refresh();
        }
    };

    private void connectUsb(boolean notifyMissing) {
        if (!foreground || connecting || tail.ready) return;

        selected = null;

        for (UsbDevice device : usb.getDeviceList().values()) {
            if (device.getVendorId() == 0x0483 &&
                    device.getProductId() == 0x5740) {
                selected = device;
                break;
            }
        }

        if (selected == null) {
            pendingStart = false;
            connectionText = "未连接主控 · 可先打开相机";

            if (notifyMissing) {
                toast("未找到主控，请检查OTG和USB数据线");
            }

            refresh();
            return;
        }

        connecting = true;

        try {
            if (usb.hasPermission(selected)) {
                tail.open(selected);
            } else {
                PendingIntent permission = PendingIntent.getBroadcast(
                        this,
                        0,
                        new Intent(USB_PERMISSION)
                                .setPackage(getPackageName()),
                        PendingIntent.FLAG_UPDATE_CURRENT |
                                PendingIntent.FLAG_IMMUTABLE);

                usb.requestPermission(selected, permission);
            }
        } catch (Exception error) {
            connecting = false;
            pendingStart = false;
            connectionText = "USB连接失败";
            toast(String.valueOf(error.getMessage()));
        }

        refresh();
    }

    private void saveVoiceResult(
            String eventId,
            long requestNs,
            long requestWallMs,
            String status,
            String message) {

        final Context context = getApplicationContext();
        final long resultNs = SystemClock.elapsedRealtimeNanos();

        new Thread(() -> {
            try (VisionLog log =
                         new VisionLog(context, eventId + "_voice")) {

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
                                "voice_protocol",
                                "RUN_LEVEL_AND_LEAK_PULSE",
                                "active_level", "LOW",
                                "pulse_low_ms", 100,
                                "min_release_ms", 100,
                                "playback_confirmed", false));

            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && foreground) {
                        toast("语音日志保存失败：" + error.getMessage());
                    }
                });
            }
        }, "VoiceReceipt").start();
    }

    private void toast(String text) {
        if (foreground && text != null) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] results) {

        super.onRequestPermissionsResult(
                requestCode, permissions, results);

        if (requestCode != 10) return;

        cameraPermissionPending = false;

        boolean granted = results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED;

        if (granted && foreground && cameraWanted) {
            openCamera();
        } else {
            cameraWanted = false;

            if (foreground && !granted) {
                toast("未授权相机，仍可进行运动控制");
            }
        }

        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        connectUsb(false);
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (!hasFocus && holdTurns != null) {
            holdTurns.release();
        }
    }

    @Override
    protected void onPause() {
        if (holdTurns != null) holdTurns.release();

        // 离开前台时停止手动运动，避免操作者失去控制界面。
        if (active || tail.running) {
            endRun();
        }

        super.onPause();
    }

    @Override
    protected void onStop() {
        if (holdTurns != null) holdTurns.release();

        foreground = false;
        pendingStart = false;
        connecting = false;
        active = false;
        stopping = false;
        cameraWanted = false;

        if (settingsDialog != null) {
            settingsDialog.dismiss();
        }

        if (vision != null) {
            vision.stop("background");
        }

        tail.close();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        tail.destroy();

        if (vision != null) {
            vision.close();
        }

        super.onDestroy();
    }
}
