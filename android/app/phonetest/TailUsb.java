package com.fish.phonetailtest;

import android.hardware.usb.*;
import android.os.*;

import com.hoho.android.usbserial.driver.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TailUsb {
    public enum Event {
        READY, CHECKED, STARTED, MANUAL_UPDATE, FINISHING, STOPPED, ERROR
    }

    public interface Listener {
        void event(Event event, String message);
    }

    public interface VoiceCallback {
        void complete(String status, String message);
    }

    public enum VoiceProtocol {
        UNKNOWN("", "", "UNKNOWN"),
        SINGLE_WIRE("PB11", "", "SESSION_PULSES"),
        TWO_WIRE("PB1", "PB11", "RUN_LEVEL_AND_LEAK_PULSE");

        public final String leakGpio, runGpio, logName;
        VoiceProtocol(String leakGpio, String runGpio, String logName) {
            this.leakGpio = leakGpio;
            this.runGpio = runGpio;
            this.logName = logName;
        }
    }

    public volatile VoiceProtocol voiceProtocol = VoiceProtocol.UNKNOWN;

    private void handshake() throws IOException {
        String firmware = request("HELLO");
        if (!"CENTER_HOLD_V2_2WIRE".equals(firmware)) {
            voiceProtocol = VoiceProtocol.UNKNOWN;
            throw new IOException("请烧录中心转弯实验固件；当前固件：" + firmware);
        }
        voiceProtocol = VoiceProtocol.TWO_WIRE;
    }

    private static final class CommandRejectedException
            extends IOException {
        CommandRejectedException(String message) {
            super(message);
        }
    }

    private final UsbManager manager;
    private final Listener listener;
    private final Handler main =
            new Handler(Looper.getMainLooper());

    private final HandlerThread thread =
            new HandlerThread("TailUsb");

    private final Handler io;
    private final AtomicInteger generation =
            new AtomicInteger();

    private final StringBuilder received =
            new StringBuilder();

    private UsbSerialPort port;
    private UsbDeviceConnection connection;

    public volatile boolean ready;
    public volatile boolean running;
    public volatile boolean manualSupported;
    public volatile boolean strongSupported;
    public volatile boolean manualRunning;
    public volatile boolean manualTurning;
    public volatile String manualState = "STOPPED";

    // 已由主控应答确认的挡位，按操作者左右方向表示。
    public volatile int manualLevel;

    // 最新期望挡位。松手直接覆盖为0，不排队累积旧挡位。
    private volatile int desiredLevel;

    private final AtomicInteger steeringRevision = new AtomicInteger();

    private boolean reverseManual;
    private int manualToken;

    private void readCapabilities() throws IOException {
        strongSupported = false;
        manualSupported =
                "CENTER_HOLD_V2".equals(request("CAPS"));

        if (!manualSupported) {
            throw new IOException("请烧录配套的六挡长按主控固件");
        }
    }

    private void resetManual() {
        manualRunning = false;
        manualTurning = false;
        manualState = "STOPPED";
        manualLevel = 0;
        desiredLevel = 0;

        steeringRevision.incrementAndGet();
        io.removeCallbacks(manualTick);
    }

    private void manualAnswer(int token, String answer)
            throws IOException {

        if (!answer.matches(
                "MANUAL_STATE (STRAIGHT|LEFT_[1-6]|RIGHT_[1-6])")) {
            throw new IOException("主控挡位应答不匹配：" + answer);
        }

        if (token != generation.get() || !manualRunning) return;

        String state = answer.substring("MANUAL_STATE ".length());

        int level = 0;

        if (!"STRAIGHT".equals(state)) {
            level = state.charAt(state.length() - 1) - '0';

            if (state.startsWith("LEFT_")) {
                level = -level;
            }
        }

        if (reverseManual) {
            level = -level;
        }

        manualLevel = level;
        manualTurning = false;

        manualState = level == 0
                ? "STRAIGHT"
                : (level < 0 ? "LEFT_" : "RIGHT_") + Math.abs(level);

        report(token, Event.MANUAL_UPDATE, manualState);
    }

    private String levelCommand(int level) {
        int physical = reverseManual ? -level : level;

        if (physical == 0) {
            return "LEVEL N 0";
        }

        return "LEVEL "
                + (physical < 0 ? "L " : "R ")
                + Math.abs(physical);
    }

    private final Runnable manualTick = new Runnable() {
        @Override
        public void run() {
            int token = manualToken;

            if (token != generation.get() ||
                    !manualRunning || !ready) {
                return;
            }

            int revision = steeringRevision.get();
            int level = desiredLevel;

            try {
                manualAnswer(
                        token,
                        request(levelCommand(level), 300));

                if (token != generation.get() ||
                        !manualRunning || !ready) {
                    return;
                }

                io.removeCallbacks(this);

                // 状态变化立即发送；稳定状态每200ms保活。
                io.postDelayed(
                        this,
                        revision == steeringRevision.get() ? 200 : 0);

            } catch (Exception error) {
                fail(token, error);
            }
        }
    };

    public void setLevel(int level) {
        if (level < -6 || level > 6) {
            throw new IllegalArgumentException("挡位必须是-6～6");
        }

        if (!manualRunning || !ready) return;

        desiredLevel = level;
        steeringRevision.incrementAndGet();

        io.removeCallbacks(manualTick);
        io.post(manualTick);
    }

    public void startManual(ManualConfig source) {
        ManualConfig c = source.copy();
        String command = c.command();
        if (!manualSupported) {
            report(generation.get(), Event.ERROR, "请先烧录配套的中心转弯实验固件。");
            return;
        }
        resetManual();
        final int token = generation.incrementAndGet();
        running = true;
        io.post(() -> {
            if (token != generation.get()) return;
            try {
                expect(command, "HOLD");
                if (token != generation.get()) return;
                reverseManual = c.reverse;
                manualToken = token;
                manualRunning = true;
                manualState = "STRAIGHT";
                report(token, Event.STARTED, "手动操控已开始，默认持续直游");
                io.post(manualTick);
            } catch (Exception e) { fail(token, e); }
        });
    }

    public TailUsb(
            UsbManager manager, Listener listener) {
        this.manager = manager;
        this.listener = listener;

        thread.start();
        io = new Handler(thread.getLooper());
    }

    private void report(
            int token, Event event, String message) {
        main.post(() -> {
            if (token == generation.get()) {
                listener.event(event, message);
            }
        });
    }

    private String request(String command)
            throws IOException {
        return request(command, 1500);
    }

    private String request(String command, int timeoutMs)
            throws IOException {

        if (port == null) {
            throw new IOException("USB未连接");
        }

        String id = UUID.randomUUID()
                .toString().substring(0, 8);

        String prefix = "FISH " + id + " ";

        port.write(
                ("FISH " + command + " " + id + "\n")
                        .getBytes(StandardCharsets.US_ASCII),
                Math.min(300, timeoutMs));

        byte[] buffer = new byte[128];

        long deadline =
                SystemClock.elapsedRealtime() + timeoutMs;

        while (SystemClock.elapsedRealtime() < deadline) {
            int count = port.read(buffer, 50);

            for (int i = 0; i < count; i++) {
                char ch = (char)(buffer[i] & 255);

                if (ch == '\r') continue;

                if (ch == '\n') {
                    String line = received.toString();
                    received.setLength(0);

                    if (!line.startsWith(prefix)) {
                        continue;
                    }

                    String reply =
                            line.substring(prefix.length());

                    if (reply.startsWith("OK ")) {
                        return reply.substring(3);
                    }

                    if (reply.startsWith("ERR ")) {
                        throw new CommandRejectedException(
                                reply.substring(4));
                    }

                    throw new IOException(
                            "无法识别的单片机应答：" + reply);
                }

                received.append(ch);

                if (received.length() > 256) {
                    received.setLength(0);
                    throw new IOException("USB应答过长");
                }
            }
        }

        throw new IOException(
                "应答超时；请检查USB连接和配套主控固件（CENTER_HOLD_V2_2WIRE）");
    }

    private void expect(
            String command, String expected)
            throws IOException {

        if (!expected.equals(request(command))) {
            throw new IOException(
                    "固件应答不匹配：" + command);
        }
    }

    private int readState() throws IOException {
        String state = request("STATUS");

        if (!state.matches("STATUS [0-3]")) {
            throw new IOException(
                    "状态格式错误：" + state);
        }

        return state.charAt(state.length() - 1) - '0';
    }

    private boolean readBusy() throws IOException {
        return readState() != 0;
    }

    public void open(UsbDevice device) {
        resetManual();
        manualSupported = false;
        strongSupported = false;
        voiceProtocol = VoiceProtocol.UNKNOWN;
        int token = generation.incrementAndGet();

        ready = false;
        running = false;

        io.post(() -> {
            if (token != generation.get()) return;

            closeInternal();

            try {
                UsbSerialDriver driver =
                        new CdcAcmSerialDriver(device);

                if (driver.getPorts().isEmpty()) {
                    throw new IOException(
                            "未找到USB CDC串口");
                }

                connection = manager.openDevice(device);

                if (connection == null) {
                    throw new IOException("无法打开USB");
                }

                port = driver.getPorts().get(0);
                port.open(connection);

                port.setParameters(
                        115200, 8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE);

                port.setDTR(true);
                port.setRTS(false);

                handshake();
                readCapabilities();

                boolean busy = readBusy();

                if (token != generation.get()) return;

                ready = true;
                running = busy;

                report(token, Event.READY,
                        "单片机固件检查通过");

                if (busy) monitor(token);

            } catch (Exception e) {
                fail(token, e);
            }
        });
    }

    public void check() {
        int token = generation.incrementAndGet();

        io.post(() -> {
            if (token != generation.get()) return;

            try {
                if (!ready) {
                    throw new IOException("USB未连接");
                }

                handshake();
                readCapabilities();

                boolean busy = readBusy();

                if (token != generation.get()) return;

                running = busy;

                report(token, Event.CHECKED,
                        "单片机通信检查通过");

                if (busy) monitor(token);

            } catch (Exception e) {
                fail(token, e);
            }
        });
    }

    public void triggerVoice(
            String eventId, VoiceCallback callback) {

        final int token = generation.get();

        if (eventId == null ||
                !eventId.matches("[A-Za-z0-9_-]{1,63}")) {
            main.post(() -> callback.complete(
                    "SKIPPED", "视觉事件编号无效"));
            return;
        }

        // 使用原来的 USB 工作线程，不能另开串口读写线程。
        // 不增加 generation，避免中断路线状态监测。
        boolean posted = io.post(() -> {

            if (token != generation.get() ||
                    !ready || !running) {
                main.post(() -> callback.complete(
                        "SKIPPED",
                        "运行已结束、已取消或USB未连接"));
                return;
            }

            String status;
            String message;

            try {
                // 正常通信一般很快返回。
                // 语音请求使用较短超时，避免长期占用USB队列。
                String answer = request(
                        "VOICE " + eventId, 300);

                if ("VOICE_QUEUED".equals(answer)) {
                    status = "QUEUED";
                    message = "主控已接收，已安排" + voiceProtocol.leakGpio + "漏油脉冲";

                } else if ("VOICE_DUPLICATE".equals(answer)) {
                    status = "DUPLICATE";
                    message = "此事件已接收，本次不重复触发";

                } else {
                    status = "UNKNOWN";
                    message = "应答不匹配：" + answer;
                }

            } catch (CommandRejectedException error) {
                status = "REJECTED";
                message = "主控拒绝：" + error.getMessage();

            } catch (Exception error) {
                // 无应答不等于主控一定没收到，不能直接认定失败，
                // 也不自动重发，避免断连/复位情形下重复播报。
                status = "UNKNOWN";
                message = "未确认是否已接收："
                        + String.valueOf(error.getMessage());
            }

            final String resultStatus = status;
            final String resultMessage = message;

            // 回调不按 generation 丢弃，确保停止后仍能记录回执。
            main.post(() -> callback.complete(
                    resultStatus, resultMessage));
        });

        if (!posted) {
            main.post(() -> callback.complete(
                    "SKIPPED", "USB工作线程已关闭"));
        }
    }

    public void startRoute(RouteConfig source) {
        resetManual();
        RouteConfig c = source.copy();
        begin(c.command(), "ROUTE");
    }

    private void begin(
            String command, String expected) {

        int token = generation.incrementAndGet();
        running = true;

        io.post(() -> {
            if (token != generation.get()) return;

            try {
                if (!ready) {
                    throw new IOException("USB未连接");
                }

                expect(command, expected);

                if (token != generation.get()) return;

                report(token, Event.STARTED,
                        "单片机开始执行");

                monitor(token);

            } catch (Exception e) {
                fail(token, e);
            }
        });
    }

    private void monitor(int token) {
        io.postDelayed(() -> {
            if (token != generation.get() || !ready) {
                return;
            }

            try {
                int state = readState();

                if (token != generation.get()) return;

                // 包括语音收尾阶段，暂不允许开启下一轮。
                running = state != 0;

                if (state == 2) {
                    report(token, Event.FINISHING,
                            "运动已结束，正在发送语音结束指令");
                }

                if (state != 0) {
                    monitor(token);
                } else {
                    report(token, Event.STOPPED,
                            "运动及语音控制收尾已完成");
                }

            } catch (Exception e) {
                fail(token, e);
            }
        }, 200);
    }

    public void stop() {
        resetManual();
        int token = generation.incrementAndGet();

        io.post(() -> {
            if (token != generation.get()) return;

            try {
                if (!ready) {
                    throw new IOException(
                            "USB未连接，结束指令无法发送");
                }

                expect("STOP", "STOP");

                if (token != generation.get()) return;

                monitor(token);

            } catch (Exception e) {
                fail(token, e);
            }
        });
    }

    private void bestEffortStop() {
        if (port == null) return;

        try {
            String id = UUID.randomUUID()
                    .toString().substring(0, 8);

            port.write(
                    ("FISH STOP " + id + "\n")
                            .getBytes(StandardCharsets.US_ASCII),
                    100);

        } catch (Exception ignored) { }
    }

    private void fail(int token, Exception error) {
        if (token != generation.get()) return;

        resetManual();

        ready = false;
        running = false;

        bestEffortStop();
        closeInternal();

        report(token, Event.ERROR,
                "USB通信失败：" + error.getMessage()
                        + "。已尝试停止，但未确认；"
                        + "单片机可能仍在执行。");
    }

    private void closeInternal() {
        if (port != null) {
            try {
                port.close();
            } catch (Exception ignored) { }

            port = null;
        }

        if (connection != null) {
            connection.close();
            connection = null;
        }

        received.setLength(0);
    }

    public void close() {
        resetManual();
        generation.incrementAndGet();

        ready = false;
        running = false;

        io.post(() -> {
            bestEffortStop();
            closeInternal();
        });
    }

    public void destroy() {
        close();
        thread.quitSafely();
    }
}
