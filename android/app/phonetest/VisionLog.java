package com.fish.phonetailtest;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class VisionLog implements AutoCloseable {
    private final Context context;
    private final Uri uri;
    private final BufferedWriter writer;

    private final SimpleDateFormat clock =
            new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);

    private long lastFlush;
    private boolean closed;

    VisionLog(Context context, String sessionId) throws Exception {
        this.context = context;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                sessionId + ".jsonl");
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                "application/x-ndjson");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                "Download/FishTail");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri created = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

        if (created == null) {
            throw new IllegalStateException("无法创建检测日志");
        }

        uri = created;

        try {
            OutputStream output =
                    context.getContentResolver().openOutputStream(uri, "w");

            if (output == null) {
                throw new IllegalStateException("无法打开检测日志");
            }

            writer = new BufferedWriter(new OutputStreamWriter(
                    output, StandardCharsets.UTF_8));
        } catch (Exception error) {
            context.getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    static JSONObject data(Object... pairs) {
        JSONObject object = new JSONObject();

        try {
            for (int i = 0; i < pairs.length; i += 2) {
                object.put((String) pairs[i], pairs[i + 1]);
            }
            return object;
        } catch (Exception error) {
            throw new IllegalArgumentException(error);
        }
    }

    void write(String event, JSONObject details) throws Exception {
        if (closed) return;

        long now = System.currentTimeMillis();

        details.put("event", event);
        details.put("time", clock.format(new Date(now)));
        details.put("wall_time_ms", now);
        details.put("write_elapsed_ns",
                SystemClock.elapsedRealtimeNanos());

        writer.write(details.toString());
        writer.newLine();

        long elapsed = SystemClock.elapsedRealtime();

        if (!"DETECTION".equals(event) ||
                elapsed - lastFlush >= 1000) {
            writer.flush();
            lastFlush = elapsed;
        }
    }

    static String saveKeyFrame(
            Context context,
            String name,
            Bitmap bitmap) throws Exception {

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/FishTail");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri == null) {
            throw new IllegalStateException("无法创建触发关键帧");
        }

        try {
            try (OutputStream output =
                         context.getContentResolver().openOutputStream(uri)) {
                if (output == null ||
                        !bitmap.compress(
                                Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw new IllegalStateException("关键帧保存失败");
                }
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);

            return uri.toString();

        } catch (Exception error) {
            context.getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;

        try {
            writer.close();
        } finally {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
        }
    }
}
