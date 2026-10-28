package com.fish.phonetailtest;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ManualConfig {

    public static final String[] LABELS = {
            "向低角度侧摆幅（度）",
            "向高角度侧摆幅（度）",
            "完整摆动周期（毫秒）",
            "长按加一挡的间隔（毫秒）"
    };

    public final double[] values = {30, 30, 300, 300};

    public boolean reverse;
    public boolean recordVideo;

    public ManualConfig copy() {
        ManualConfig next = new ManualConfig();
        System.arraycopy(values, 0, next.values, 0, values.length);
        next.reverse = reverse;
        next.recordVideo = recordVideo;
        return next;
    }

    public void validate() {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("请输入有效数字");
            }
        }

        // 六挡时中心为30°或150°，所以每侧最多摆30°。
        if (values[0] < 0 || values[0] > 30 ||
                values[1] < 0 || values[1] > 30) {
            throw new IllegalArgumentException(
                    "两侧摆幅须为0～30度，以保证六挡时不超出0～180度");
        }

        checkTime(2, 100, 10000);
        checkTime(3, 100, 5000);
    }

    private void checkTime(int index, int minimum, int maximum) {
        double value = values[index];

        if (value < minimum || value > maximum ||
                value != Math.rint(value) ||
                ((long) value) % 20 != 0) {
            throw new IllegalArgumentException(
                    LABELS[index] + "须为" + minimum + "～" +
                    maximum + "，且是20毫秒的整数倍");
        }
    }

    public String command() {
        validate();

        StringBuilder result = new StringBuilder("HOLD");

        // 主控只接收摆幅和摆动周期。
        // 长按加挡间隔由手机处理。
        for (int i = 0; i < 3; i++) {
            result.append(' ').append(Math.round(values[i] * 1000));
        }

        return result.toString();
    }

    public String encode() {
        try {
            JSONArray array = new JSONArray();

            for (double value : values) {
                array.put(value);
            }

            return new JSONObject()
                    .put("schema", 2)
                    .put("values", array)
                    .put("reverse", reverse)
                    .put("recordVideo", recordVideo)
                    .toString();

        } catch (Exception error) {
            throw new IllegalArgumentException(error);
        }
    }

    public static ManualConfig decode(String text) {
        if (text == null) return new ManualConfig();

        try {
            JSONObject object = new JSONObject(text);
            JSONArray array = object.getJSONArray("values");
            int schema = object.optInt("schema", 1);

            ManualConfig config = new ManualConfig();

            if (schema == 2 && array.length() == 4) {
                for (int i = 0; i < 4; i++) {
                    config.values[i] = array.getDouble(i);
                }

            } else if (schema == 1 && array.length() == 9) {
                // 从上一版迁移：保留摆动参数，加挡间隔默认300ms。
                config.values[0] = Math.min(30, array.getDouble(0));
                config.values[1] = Math.min(30, array.getDouble(1));
                config.values[2] = array.getDouble(2);

            } else {
                throw new IllegalArgumentException("参数版本不匹配");
            }

            config.reverse = object.optBoolean("reverse", false);
            config.recordVideo = object.optBoolean("recordVideo", false);
            config.validate();

            return config;

        } catch (Exception ignored) {
            return new ManualConfig();
        }
    }
}
