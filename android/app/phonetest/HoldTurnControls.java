package com.fish.phonetailtest;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public final class HoldTurnControls {

    private final Button left;
    private final Button right;

    private final IntConsumer sendLevel;
    private final IntSupplier intervalMs;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private View held;
    private int pointer = -1;
    private int direction;
    private int level;

    public HoldTurnControls(
            Button left,
            Button right,
            IntConsumer sendLevel,
            IntSupplier intervalMs) {

        this.left = left;
        this.right = right;
        this.sendLevel = sendLevel;
        this.intervalMs = intervalMs;

        bind(left, -1);
        bind(right, 1);
        updateLabels();
    }

    private final Runnable increaseLevel = new Runnable() {
        @Override
        public void run() {
            if (held == null) return;

            if (!held.isEnabled()) {
                release();
                return;
            }

            if (level < 6) {
                level++;
                sendLevel.accept(direction * level);
                updateLabels();
            }

            if (level < 6) {
                handler.postDelayed(this, intervalMs.getAsInt());
            }
        }
    };

    private void bind(Button button, int buttonDirection) {
        button.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 同时按两边时只接受先按下的一侧。
                    if (held != null) return true;

                    held = view;
                    pointer = event.getPointerId(0);
                    direction = buttonDirection;
                    level = 1;

                    view.setPressed(true);

                    if (view.getParent() != null) {
                        view.getParent()
                                .requestDisallowInterceptTouchEvent(true);
                    }

                    sendLevel.accept(direction * level);
                    updateLabels();

                    handler.removeCallbacks(increaseLevel);
                    handler.postDelayed(
                            increaseLevel, intervalMs.getAsInt());
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (held == view) {
                        int index = event.findPointerIndex(pointer);

                        if (index < 0 ||
                                event.getX(index) < 0 ||
                                event.getY(index) < 0 ||
                                event.getX(index) >= view.getWidth() ||
                                event.getY(index) >= view.getHeight()) {
                            release();
                        }
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    if (held == view &&
                            event.getPointerId(event.getActionIndex())
                                    == pointer) {
                        release();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (held == view) release();
                    view.performClick();
                    break;

                case MotionEvent.ACTION_CANCEL:
                    if (held == view) release();
                    break;
            }

            return true;
        });
    }

    private void updateLabels() {
        left.setText(
                direction < 0 && held != null
                        ? "左转 · " + level + "/6挡"
                        : "左转 · 按住加挡");

        right.setText(
                direction > 0 && held != null
                        ? "右转 · " + level + "/6挡"
                        : "右转 · 按住加挡");
    }

    public void release() {
        handler.removeCallbacks(increaseLevel);

        if (held == null) return;

        View old = held;

        held = null;
        pointer = -1;
        direction = 0;
        level = 0;

        old.setPressed(false);

        if (old.getParent() != null) {
            old.getParent().requestDisallowInterceptTouchEvent(false);
        }

        sendLevel.accept(0);
        updateLabels();
    }

    public void setEnabled(boolean enabled) {
        if (!enabled) release();

        left.setEnabled(enabled);
        right.setEnabled(enabled);
    }
}
