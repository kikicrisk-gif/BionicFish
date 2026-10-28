#include <Arduino.h>
#include <HardwareTimer.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static HardwareTimer servoTimer(TIM2);

static constexpr float PI_F = 3.14159265358979323846f;
static constexpr float CENTER = 90.0f;
static constexpr uint64_t FRAME_US = 20000;
static constexpr uint64_t PERIOD_US = 300000;
static constexpr uint64_t MOVE_US = 300000;

// 偶数行：左端毫刻度、右端毫刻度、完整往返次数。
// 奇数行：目标毫刻度、停留微秒、回中微秒。
static uint64_t plan[5][3] = {
    {60000, 120000, 16},
    {180000, 2000000, 1000000},
    {60000, 120000, 16},
    {180000, 2000000, 1000000},
    {60000, 120000, 16}
};

enum Mode : uint8_t {
    IDLE = 0,
    ROUTE = 1,
    STOPPING = 3,
    MANUAL = 4
};

static volatile uint8_t mode = IDLE;
static volatile float currentPosition = CENTER;

static uint64_t elapsedUs = 0;
static float stopFrom = CENTER;

static char inputLine[384];
static size_t inputLength = 0;
static bool overflowed = false;

// Experimental six-level hold steering.
static constexpr uint64_t MANUAL_LEASE_US = 1000000;

static uint64_t manualLeaseUs = 0;
static uint64_t manualPhaseUs = 0;
static uint64_t swimPeriodUs = PERIOD_US;

static float manualLeft = 60;
static float manualRight = 120;

// -6～-1：左转；0：直游；1～6：右转。
static volatile int8_t manualLevel = 0;

// ==================================================
// 双线语音控制：
// PB11 -> ASRPRO PA0：运行状态，LOW=运行。
// PB1  -> ASRPRO PB6：漏油事件，LOW 100ms触发一次。
// 两个引脚均为开漏输出，HIGH表示释放。
// ==================================================

static constexpr uint8_t VOICE_LIMIT = 8;

static char voiceEventIds[VOICE_LIMIT][64] = {};

static volatile uint8_t voiceAccepted = 0;
static volatile uint8_t voicePending = 0;

static volatile bool voiceSession = false;
static volatile bool voiceLeakLow = false;

// 20ms一个tick。
// 开始后等待100ms再允许漏油脉冲，
// 让ASRPRO先识别运行状态并完成漏油输入武装。
static volatile uint8_t voiceTicks = 0;

static bool validVoiceEventId(const char *text)
{
    if (!text) return false;

    size_t length = strlen(text);

    if (length == 0 ||
        length >= sizeof(voiceEventIds[0])) {
        return false;
    }

    for (size_t i = 0; i < length; ++i) {
        char c = text[i];

        bool valid =
            (c >= 'a' && c <= 'z') ||
            (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') ||
            c == '_' || c == '-';

        if (!valid) return false;
    }

    return true;
}

// 在中断内部，或主循环关闭中断后调用。
static bool voiceBusy()
{
    return voiceSession ||
           voiceLeakLow ||
           voicePending != 0 ||
           voiceTicks != 0;
}

// 调用者必须关闭中断。
static void voiceBeginSession()
{
    voiceAccepted = 0;
    voicePending = 0;
    voiceLeakLow = false;

    digitalWrite(PB1, HIGH);

    voiceSession = true;
    voiceTicks = 5; // 等待100ms再发送漏油事件。

    // 运行期间持续拉低，不再发送400ms开始脉冲。
    digitalWrite(PB11, LOW);
}

// 在中断内部，或主循环关闭中断后调用。
static void voiceEndSession()
{
    if (!voiceSession) return;

    voiceSession = false;
    voiceAccepted = 0;
    voicePending = 0;
    voiceLeakLow = false;

    // 结束时取消漏油输出，并释放运行状态线。
    digitalWrite(PB1, HIGH);
    digitalWrite(PB11, HIGH);

    // 留100ms结束间隔，让ASRPRO识别HIGH并清理状态。
    voiceTicks = 5;
}

// 由现有20ms中断调用。
static void voiceOnFrame()
{
    if (voiceTicks > 0) {
        --voiceTicks;

        if (voiceTicks > 0) {
            return;
        }
    }

    if (voiceLeakLow) {
        digitalWrite(PB1, HIGH);

        voiceLeakLow = false;
        voiceTicks = 5; // 释放100ms。
        return;
    }

    if (voiceSession && voicePending > 0) {
        --voicePending;

        digitalWrite(PB1, LOW);

        voiceLeakLow = true;
        voiceTicks = 5; // LOW 100ms。
    }
}

static float blend(
    float from,
    float to,
    uint64_t t,
    uint64_t duration)
{
    float u = duration
        ? (float)t / (float)duration
        : 1.0f;

    if (u > 1.0f) u = 1.0f;

    return from + (to - from)
        * (0.5f - 0.5f * cosf(PI_F * u));
}

static void outputPosition(float position)
{
    if (!isfinite(position)) return;

    if (position < 0.0f) position = 0.0f;
    if (position > 180.0f) position = 180.0f;

    currentPosition = position;

    uint32_t pulse = (uint32_t)lroundf(
        1500.0f
        + (position - CENTER) * (1000.0f / 90.0f));

    servoTimer.setCaptureCompare(
        3, pulse, MICROSEC_COMPARE_FORMAT);
}

static float swimPosition(
    uint64_t phase,
    float left,
    float right,
    uint64_t period = PERIOD_US)
{
    float wave = sinf(
        2.0f * PI_F * (float)phase / (float)period);

    float magnitude = fabsf(wave);
    float common = fminf(CENTER - left, right - CENTER);
    float side = wave >= 0.0f
        ? right - CENTER
        : CENTER - left;

    float smooth =
        magnitude * magnitude * (3.0f - 2.0f * magnitude);

    float offset =
        common * magnitude + (side - common) * smooth;

    return CENTER + (wave >= 0.0f ? offset : -offset);
}

// 复摆暂时停用，保留独立函数便于以后恢复。
// 恢复时还需同步增加路线参数、协议和阶段时长。
#if 0
static float repeatPosition(uint64_t t, float target)
{
    const uint64_t retreatUs = 1000000;
    uint64_t phase = t % (retreatUs + MOVE_US);

    if (phase < retreatUs) {
        return blend(target, 150.0f, phase, retreatUs);
    }

    return blend(
        150.0f, target,
        phase - retreatUs, MOVE_US);
}
#endif

static float routePosition(uint64_t t)
{
    for (unsigned i = 0; i < 5; ++i) {
        if ((i % 2) == 0) {
            uint64_t duration = plan[i][2] * PERIOD_US;

            if (t < duration) {
                return swimPosition(
                    t % PERIOD_US,
                    (float)plan[i][0] / 1000.0f,
                    (float)plan[i][1] / 1000.0f);
            }

            // 次数0：duration为0，直接检查下一段。
            t -= duration;

        } else {
            float target = (float)plan[i][0] / 1000.0f;
            uint64_t holdUs = plan[i][1];
            uint64_t returnUs = plan[i][2];

            uint64_t duration =
                MOVE_US + holdUs + returnUs;

            if (t < duration) {
                if (t < MOVE_US) {
                    return blend(
                        CENTER, target, t, MOVE_US);
                }

                t -= MOVE_US;

                if (t < holdUs) {
                    return target;
                }

                return blend(
                    target, CENTER,
                    t - holdUs, returnUs);
            }

            t -= duration;
        }
    }

    mode = IDLE;
    return CENTER;
}

static void stopMotion() {
    // 立即结束语音会话，并平滑回到舵机中心。
    voiceEndSession();

    if (mode == ROUTE || mode == MANUAL) {
        stopFrom = currentPosition;
        elapsedUs = 0;
        mode = STOPPING;
        manualLevel = 0;
    }
}

static const char *manualState()
{
    static char state[32];

    int level = manualLevel;

    if (level == 0) {
        return "MANUAL_STATE STRAIGHT";
    }

    snprintf(
        state,
        sizeof(state),
        "MANUAL_STATE %s_%d",
        level < 0 ? "LEFT" : "RIGHT",
        level < 0 ? -level : level);

    return state;
}

static float manualPosition()
{
    // 换挡时不重置摆动周期，尾鳍始终连续摆动。
    manualPhaseUs = (manualPhaseUs + FRAME_US) % swimPeriodUs;

    // 0挡90°，左六挡30°，右六挡150°。
    float center = CENTER + 10.0f * manualLevel;

    return center
        + swimPosition(
            manualPhaseUs,
            manualLeft,
            manualRight,
            swimPeriodUs)
        - CENTER;
}

static void onFrame()
{
    if (mode == MANUAL) {
        manualLeaseUs += FRAME_US;
        if (manualLeaseUs >= MANUAL_LEASE_US) stopMotion();
    }
    if (mode != IDLE) {
        elapsedUs += FRAME_US;

        float position;

        if (mode == ROUTE) {
            position = routePosition(elapsedUs);
        } else if (mode == MANUAL) {
            position = manualPosition();
        } else {
            position = blend(
                stopFrom, CENTER, elapsedUs, MOVE_US);

            if (elapsedUs >= MOVE_US) {
                mode = IDLE;
            }
        }

        outputPosition(position);
    }

    // 自然结束也由主控自动关闭语音会话，
    // 不依赖手机是否及时收到状态。
    if (mode == IDLE) {
        voiceEndSession();
    }

    // 舵机停止后，仍继续完成语音释放间隔。
    voiceOnFrame();
}

static bool unsignedNumber(
    const char *s,
    uint64_t &value)
{
    if (!s || !*s) return false;

    value = 0;

    for (; *s; ++s) {
        if (*s < '0' || *s > '9') return false;

        uint64_t digit = (uint64_t)(*s - '0');

        if (value > (UINT64_MAX - digit) / 10) {
            return false;
        }

        value = value * 10 + digit;
    }

    return true;
}

static void reply(
    const char *id,
    bool ok,
    const char *body)
{
    SerialUSB.print("FISH ");
    SerialUSB.print(id);
    SerialUSB.print(ok ? " OK " : " ERR ");
    SerialUSB.println(body);
}

static void processLine(char *text)
{
    char *parts[18];
    unsigned count = 0;
    char *context = nullptr;

    for (char *p = strtok_r(text, " ", &context);
         p;
         p = strtok_r(nullptr, " ", &context)) {
        if (count == 18) return;
        parts[count++] = p;
    }

    if (count < 3 ||
        strcmp(parts[0], "FISH") != 0) {
        return;
    }

    const char *id = parts[count - 1];
    if (strlen(id) > 16) return;

    if (count == 3 &&
        strcmp(parts[1], "HELLO") == 0) {
        reply(id, true, "CENTER_HOLD_V2_2WIRE");
        return;
    }

    if (count == 3 && strcmp(parts[1], "CAPS") == 0) {
        reply(id, true, "CENTER_HOLD_V2");
        return;
    }

    // FISH HOLD 左侧摆幅毫度 右侧摆幅毫度 周期微秒 请求ID
    if (count == 6 && strcmp(parts[1], "HOLD") == 0) {
        uint64_t p[3] = {};

        for (unsigned i = 0; i < 3; ++i) {
            if (!unsignedNumber(parts[i + 2], p[i])) {
                reply(id, false, "PARAMETERS");
                return;
            }
        }

        if (p[0] > 30000 ||
            p[1] > 30000 ||
            p[2] < 100000 ||
            p[2] > 10000000 ||
            p[2] % FRAME_US != 0) {
            reply(id, false, "RANGE");
            return;
        }

        noInterrupts();

        if (mode != IDLE || voiceBusy()) {
            interrupts();
            reply(id, false, "BUSY");
            return;
        }

        manualLeft = CENTER - p[0] / 1000.0f;
        manualRight = CENTER + p[1] / 1000.0f;
        swimPeriodUs = p[2];

        manualLeaseUs = 0;
        manualPhaseUs = 0;
        elapsedUs = 0;
        manualLevel = 0;

        voiceBeginSession();
        outputPosition(CENTER);
        mode = MANUAL;

        interrupts();

        reply(id, true, "HOLD");
        return;
    }

    // FISH LEVEL L/R/N 挡位 请求ID
    // L/R对应1～6挡，N必须配0挡。
    // 此命令同时作为运行心跳。
    if (count == 5 && strcmp(parts[1], "LEVEL") == 0) {
        uint64_t level = 0;

        bool left = strcmp(parts[2], "L") == 0;
        bool right = strcmp(parts[2], "R") == 0;
        bool neutral = strcmp(parts[2], "N") == 0;

        if (!unsignedNumber(parts[3], level) ||
            (!left && !right && !neutral) ||
            level > 6 ||
            (neutral && level != 0) ||
            (!neutral && level == 0)) {
            reply(id, false, "PARAMETERS");
            return;
        }

        noInterrupts();

        if (mode != MANUAL) {
            interrupts();
            reply(id, false, "NOT_MANUAL");
            return;
        }

        manualLeaseUs = 0;

        manualLevel = neutral
            ? 0
            : (left ? -(int8_t)level : (int8_t)level);

        const char *state = manualState();

        interrupts();

        reply(id, true, state);
        return;
    }

    // 协议：
    // FISH VOICE <视觉事件ID> <本次请求ID>
    if (count == 4 &&
        strcmp(parts[1], "VOICE") == 0) {

        const char *eventId = parts[2];

        if (!validVoiceEventId(eventId)) {
            reply(id, false, "VOICE_BAD_ID");
            return;
        }

        bool ok = false;
        const char *answer = "VOICE_NOT_RUNNING";

        noInterrupts();

        if ((mode == ROUTE || mode == MANUAL) && voiceSession) {
            bool duplicate = false;

            for (uint8_t i = 0; i < voiceAccepted; ++i) {
                if (strcmp(
                        voiceEventIds[i], eventId) == 0) {
                    duplicate = true;
                    break;
                }
            }

            if (duplicate) {
                ok = true;
                answer = "VOICE_DUPLICATE";

            } else if (voiceAccepted >= VOICE_LIMIT) {
                answer = "VOICE_LIMIT_8";

            } else {
                strcpy(
                    voiceEventIds[voiceAccepted], eventId);

                ++voiceAccepted;
                ++voicePending;

                ok = true;
                answer = "VOICE_QUEUED";
            }
        }

        interrupts();

        reply(id, ok, answer);
        return;
    }

    if (count == 3 &&
        strcmp(parts[1], "STATUS") == 0) {

        uint8_t stateValue;

        noInterrupts();

        stateValue = mode;
        if (stateValue == MANUAL) stateValue = ROUTE;

        // 2：运动已经结束，正在等待语音释放间隔。
        // 此时暂不允许下一轮开始，避免两轮运行状态混淆。
        if (stateValue == IDLE && voiceBusy()) {
            stateValue = 2;
        }

        interrupts();

        char state[16];

        snprintf(
            state, sizeof(state),
            "STATUS %u", (unsigned)stateValue);

        reply(id, true, state);
        return;
    }

    if (count == 3 &&
        strcmp(parts[1], "STOP") == 0) {

        noInterrupts();

        // 立即关闭语音事件接收，并释放运行状态和漏油输出。
        stopMotion();

        interrupts();

        reply(id, true, "STOP");
        return;
    }

    reply(id, false, "EXPERIMENT_COMMAND_REQUIRED");
}

void setup()
{
    // PB11：运行状态输出。
    digitalWrite(PB11, HIGH);
    pinMode(PB11, OUTPUT_OPEN_DRAIN);
    digitalWrite(PB11, HIGH);

    // PB1：漏油事件输出。
    digitalWrite(PB1, HIGH);
    pinMode(PB1, OUTPUT_OPEN_DRAIN);
    digitalWrite(PB1, HIGH);

    servoTimer.pause();

    servoTimer.setMode(
        3, TIMER_OUTPUT_COMPARE_PWM1, PB10);

    servoTimer.setOverflow(
        (uint32_t)FRAME_US, MICROSEC_FORMAT);

    // 上电等待确认执行，不立即驱动舵机位置。
    servoTimer.setCaptureCompare(
        3, 0, MICROSEC_COMPARE_FORMAT);

    servoTimer.attachInterrupt(onFrame);
    servoTimer.setInterruptPriority(1, 0);
    servoTimer.resume();

    pinMode(PA12, OUTPUT);
    digitalWrite(PA12, LOW);
    delay(100);
    pinMode(PA12, INPUT);

    SerialUSB.begin(115200);
}

void loop()
{
    for (unsigned n = 0;
         n < 160 && SerialUSB.available() > 0;
         ++n) {

        int value = SerialUSB.read();
        if (value < 0) break;

        char ch = (char)value;
        if (ch == '\r') continue;

        if (ch == '\n') {
            inputLine[inputLength] = '\0';

            if (!overflowed && inputLength) {
                processLine(inputLine);
            }

            inputLength = 0;
            overflowed = false;

        } else if (!overflowed) {
            if (inputLength < sizeof(inputLine) - 1) {
                inputLine[inputLength++] = ch;
            } else {
                overflowed = true;
            }
        }
    }
}
