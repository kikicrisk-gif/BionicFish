package com.fish.phonetailtest;

import android.hardware.usb.*;
import android.os.*;

import com.hoho.android.usbserial.driver.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class TailUsb {
    public enum Event {
        READY, CHECKED, STARTED, FINISHING, STOPPED, ERROR
    }

    public interface Listener {
        void event(Event event, String message);
    }

    public interface VoiceCallback {
        void complete(String status, String message);
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
                "应答超时；请确认已烧录ROUTE5_V4_2WIRE固件");
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

                expect("HELLO", "ROUTE5_V4_2WIRE");

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

                expect("HELLO", "ROUTE5_V4_2WIRE");

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
                    message = "主控已接收，已安排PB1漏油脉冲";

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
                            "运动已结束，语音结束脉冲已发送完成");
                }

            } catch (Exception e) {
                fail(token, e);
            }
        }, 200);
    }

    public void stop() {
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
