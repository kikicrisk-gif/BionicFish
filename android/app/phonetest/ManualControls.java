package com.fish.phonetailtest;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A hold owns one pointer. Cancellation or leaving the button restores straight. */
public final class ManualControls extends LinearLayout {
    public interface Actions {
        void steer(char direction);
        void turn(char direction);
    }
    private final Actions actions;
    private final TextView status;
    private final Button[] buttons = new Button[6];
    private View held;
    private int pointer = -1;

    public ManualControls(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setOrientation(VERTICAL);
        status = new TextView(context);
        status.setTextSize(16);
        addView(status);
        LinearLayout bias = new LinearLayout(context);
        buttons[0] = button(bias, "左偏 · 按住");
        buttons[1] = button(bias, "右偏 · 按住");
        bindHold(buttons[0], 'L');
        bindHold(buttons[1], 'R');
        addView(bias);
        LinearLayout strong = new LinearLayout(context);
        buttons[4] = button(strong, "大幅左偏 · 按住");
        buttons[5] = button(strong, "大幅右偏 · 按住");
        bindHold(buttons[4], 'l');
        bindHold(buttons[5], 'r');
        addView(strong);
        LinearLayout turn = new LinearLayout(context);
        buttons[2] = button(turn, "左转 · 点按");
        buttons[3] = button(turn, "右转 · 点按");
        buttons[2].setOnClickListener(v -> { release(); actions.turn('L'); });
        buttons[3].setOnClickListener(v -> { release(); actions.turn('R'); });
        addView(turn);
        TextView hint = new TextView(context);
        hint.setText("偏移松手恢复直游；大幅偏移可中断转弯。转弯完成后自动直游。");
        hint.setTextSize(12);
        addView(hint);
        update(false, false, false, "等待启动");
    }

    private Button button(LinearLayout row, String label) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(Math.round(60 * getResources().getDisplayMetrics().density));
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    private void bindHold(Button button, char direction) {
        button.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    release();
                    held = view;
                    pointer = event.getPointerId(0);
                    view.setPressed(true);
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    actions.steer(direction);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (held == view) {
                        int index = event.findPointerIndex(pointer);
                        if (index < 0 || event.getX(index) < 0 || event.getY(index) < 0 ||
                                event.getX(index) >= view.getWidth() || event.getY(index) >= view.getHeight())
                            release();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (held == view && event.getPointerId(event.getActionIndex()) == pointer) release();
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

    public void release() {
        if (held == null) return;
        View old = held;
        held = null;
        pointer = -1;
        old.setPressed(false);
        old.getParent().requestDisallowInterceptTouchEvent(false);
        actions.steer('N');
    }

    public void update(boolean running, boolean turning, boolean strongSupported, String text) {
        boolean enabled = running && !turning;
        boolean strongEnabled = running && strongSupported;
        boolean holdingStrong = held == buttons[4] || held == buttons[5];
        if (holdingStrong ? !strongEnabled : !enabled) release();
        for (int i = 0; i < buttons.length; i++) buttons[i].setEnabled(i >= 4 ? strongEnabled : enabled);
        if (running && !strongSupported) text += "\n大幅偏移需更新主控固件";
        status.setText(text);
    }
}
