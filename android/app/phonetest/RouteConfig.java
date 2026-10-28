package com.fish.phonetailtest;

import java.math.BigDecimal;
import org.json.JSONArray;

public final class RouteConfig {
    public static final String[] NAMES = {
        "第一段直行", "第一弯", "第二段直行", "第二弯", "第三段直行"
    };

    // 直行：左端、右端、次数。
    // 转弯：目标、停留秒数、回中秒数。
    public final double[][] values = {
        {60, 120, 16},
        {180, 2, 1},
        {60, 120, 16},
        {180, 2, 1},
        {60, 120, 16}
    };

    public static boolean straight(int i) {
        return i % 2 == 0;
    }

    public RouteConfig copy() {
        RouteConfig c = new RouteConfig();
        for (int i = 0; i < 5; i++) {
            System.arraycopy(values[i], 0, c.values[i], 0, 3);
        }
        return c;
    }

    public static String number(double value) {
        return BigDecimal.valueOf(value)
                .stripTrailingZeros().toPlainString();
    }

    public static String seconds(long microseconds) {
        return BigDecimal.valueOf(microseconds, 6)
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

    public static String[] labels(int i) {
        return straight(i)
                ? new String[]{
                    "左端位置", "右端位置", "完整往返次数"
                }
                : new String[]{
                    "右转目标位置", "端点停留（秒）", "回中时间（秒）"
                };
    }

    public static String hint(int i) {
        return straight(i)
                ? "左端0～90；右端90～180。"
                    + "次数为0～1,000,000,000的整数，0跳过。"
                    + "\n中心90，周期固定300毫秒。"
                : "目标90～270，默认180；停留0～3600秒；"
                    + "回中0.02～3600秒。"
                    + "\n转向时间固定0.3秒，复摆停用。";
    }

    public void validateStage(int i) {
        double[] v = values[i];

        for (double x : v) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("请输入有限数字");
            }
        }

        if (straight(i)) {
            if (v[0] < 0 || v[0] > 90
                    || v[1] < 90 || v[1] > 180) {
                throw new IllegalArgumentException(
                        "左端须在0～90，右端须在90～180");
            }

            if (v[2] < 0 || v[2] > 1000000000
                    || v[2] != Math.rint(v[2])) {
                throw new IllegalArgumentException(
                        "次数须为0～1,000,000,000的整数");
            }
        } else {
            if (v[0] < 90 || v[0] > 270) {
                throw new IllegalArgumentException("目标须在90～270");
            }

            if (v[1] < 0 || v[1] > 3600
                    || v[2] < 0.02 || v[2] > 3600) {
                throw new IllegalArgumentException(
                        "停留须在0～3600秒，回中须在0.02～3600秒");
            }
        }
    }

    public void validate() {
        for (int i = 0; i < 5; i++) {
            validateStage(i);
        }
    }

    public long durationUs(int i) {
        double[] v = values[i];

        return straight(i)
                ? (long) v[2] * 300000L
                : 300000L
                    + Math.round(v[1] * 1000000.0)
                    + Math.round(v[2] * 1000000.0);
    }

    public String row(int i) {
        double[] v = values[i];

        if (straight(i)) {
            return NAMES[i]
                    + "\n左端" + number(v[0])
                    + " / 右端" + number(v[1])
                    + " / " + number(v[2]) + "次"
                    + (v[2] == 0
                        ? "（跳过）"
                        : " / " + seconds(durationUs(i)) + "秒");
        }

        return NAMES[i]
                + "\n目标" + number(v[0])
                + " / 停留" + number(v[1]) + "秒"
                + " / 回中" + number(v[2]) + "秒";
    }

    public String totalText() {
        long total = 0;
        for (int i = 0; i < 5; i++) {
            total += durationUs(i);
        }

        return "计划总时间约" + seconds(total)
                + "秒。点击行可编辑；确认执行后才开始运动。";
    }

    public String command() {
        validate();

        StringBuilder out = new StringBuilder("ROUTE");

        for (int i = 0; i < 5; i++) {
            double[] v = values[i];

            out.append(' ').append(Math.round(v[0] * 1000.0));
            out.append(' ').append(Math.round(
                    v[1] * (straight(i) ? 1000.0 : 1000000.0)));
            out.append(' ').append(
                    straight(i)
                            ? (long) v[2]
                            : Math.round(v[2] * 1000000.0));
        }

        return out.toString();
    }

    public String encode() {
        JSONArray all = new JSONArray();

        for (double[] row : values) {
            JSONArray a = new JSONArray();
            for (double x : row) {
                a.put(Double.toString(x));
            }
            all.put(a);
        }

        return all.toString();
    }

    public static RouteConfig decode(String text) {
        if (text == null) return new RouteConfig();

        try {
            JSONArray all = new JSONArray(text);
            if (all.length() != 5) {
                throw new IllegalArgumentException();
            }

            RouteConfig c = new RouteConfig();

            for (int i = 0; i < 5; i++) {
                JSONArray a = all.getJSONArray(i);
                if (a.length() != 3) {
                    throw new IllegalArgumentException();
                }

                for (int j = 0; j < 3; j++) {
                    c.values[i][j] = a.getDouble(j);
                }
            }

            c.validate();
            return c;
        } catch (Exception e) {
            return new RouteConfig();
        }
    }
}
