package com.fish.phonetailtest;

import java.math.BigDecimal;
import java.util.Locale;

public final class MotionConfig {
    public static final int CENTER = 90;
    public static final int PARAM_COUNT = 4;

    public long cycles = 16;
    public double leftPosition = 60.0;
    public double rightPosition = 120.0;
    public double periodMs = 300.0;

    public MotionConfig copy() {
        MotionConfig c = new MotionConfig();
        c.cycles = cycles;
        c.leftPosition = leftPosition;
        c.rightPosition = rightPosition;
        c.periodMs = periodMs;
        return c;
    }

    public static String number(double value) {
        return BigDecimal.valueOf(value)
                .stripTrailingZeros().toPlainString();
    }

    public static String normalize(String text) {
        StringBuilder out = new StringBuilder();

        for (char ch : text.trim().toCharArray()) {
            if (ch >= '０' && ch <= '９') {
                ch = (char) ('0' + ch - '０');
            }
            if (ch == ',' || ch == '，'
                    || ch == '．' || ch == '。') {
                ch = '.';
            }
            out.append(ch);
        }

        return out.toString();
    }

    public static String label(int i) {
        switch (i) {
            case 0: return "完整往返次数";
            case 1: return "左端位置";
            case 2: return "右端位置";
            case 3: return "一次往返时间（毫秒）";
            default: throw new IllegalArgumentException("未知参数");
        }
    }

    public static String hint(int i) {
        switch (i) {
            case 0:
                return "0～1,000,000,000；0表示运行到手动停止。";
            case 1:
                return "0～90，默认60，支持小数。"
                        + "数字越小，左侧摆幅越大；90表示该侧不摆动。";
            case 2:
                return "90～180，默认120，支持小数。"
                        + "数字越大，右侧摆幅越大；90表示该侧不摆动。";
            case 3:
                return "80～86,400,000毫秒，默认300，支持小数。"
                        + "300毫秒约3.33Hz。";
            default:
                throw new IllegalArgumentException("未知参数");
        }
    }

    public String value(int i) {
        switch (i) {
            case 0: return Long.toString(cycles);
            case 1: return number(leftPosition);
            case 2: return number(rightPosition);
            case 3: return number(periodMs);
            default: throw new IllegalArgumentException("未知参数");
        }
    }

    public void set(int i, String input) {
        String text = normalize(input);
        if (text.isEmpty()) return;

        MotionConfig c = copy();

        try {
            switch (i) {
                case 0:
                    c.cycles = Long.parseLong(text);
                    break;
                case 1:
                    c.leftPosition = Double.parseDouble(text);
                    break;
                case 2:
                    c.rightPosition = Double.parseDouble(text);
                    break;
                case 3:
                    c.periodMs = Double.parseDouble(text);
                    break;
                default:
                    throw new IllegalArgumentException("未知参数");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "请输入有效数字；次数必须为整数");
        }

        c.validate();

        cycles = c.cycles;
        leftPosition = c.leftPosition;
        rightPosition = c.rightPosition;
        periodMs = c.periodMs;
    }

    public void validate() {
        if (cycles < 0 || cycles > 1000000000L) {
            throw new IllegalArgumentException(
                    "次数须在0～1,000,000,000内");
        }

        if (!Double.isFinite(leftPosition)
                || leftPosition < 0 || leftPosition > 90) {
            throw new IllegalArgumentException("左端位置须在0～90内");
        }

        if (!Double.isFinite(rightPosition)
                || rightPosition < 90 || rightPosition > 180) {
            throw new IllegalArgumentException("右端位置须在90～180内");
        }

        if (!Double.isFinite(periodMs)
                || periodMs < 80 || periodMs > 86400000) {
            throw new IllegalArgumentException(
                    "往返时间须在80～86,400,000毫秒内");
        }
    }

    public String summary() {
        return "完整往返次数：" + (cycles == 0 ? "不限" : cycles)
                + "\n左端：" + number(leftPosition)
                + "，左侧摆幅：" + number(CENTER - leftPosition)
                + "\n右端：" + number(rightPosition)
                + "，右侧摆幅：" + number(rightPosition - CENTER)
                + "\n一次往返：" + number(periodMs) + "毫秒"
                + String.format(Locale.US,
                        "\n目标频率：%.3fHz", 1000 / periodMs)
                + "\n运动顺序：90 → "
                + number(rightPosition) + " → 90 → "
                + number(leftPosition) + " → 90"
                + "\n中心固定90；实际角度需标定。"
                + "\n由单片机执行；录像独立控制。";
    }
}