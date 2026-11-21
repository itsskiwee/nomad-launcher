package com.rawal.nomadlauncher;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

final class ConsoleDialog extends Dialog {
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private final MainActivity activity;
    private final LinearLayout panel, options, actions;
    private View defaultFocus;
    private int hatDirection;

    private ConsoleDialog(MainActivity activity, String title, String description) {
        super(activity);
        this.activity = activity;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel = activity.column();
        panel.setBackground(activity.shape(0xff121e30, 0xff4b5d79, 16));
        int inset = activity.dp(activity.getResources().getConfiguration().screenWidthDp >= 620 ? 27 : 19);
        panel.setPadding(inset, inset, inset, inset);
        TextView heading = activity.label(title, 32, MainActivity.TEXT, false);
        heading.setTypeface(MainActivity.LIGHT);
        heading.setAccessibilityHeading(true);
        panel.addView(heading, new LinearLayout.LayoutParams(MATCH, WRAP));

        int maxHeight = activity.dp(Math.max(100, activity.getResources().getConfiguration().screenHeightDp / 2));
        ScrollView scroller = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroller.setFillViewport(false);
        scroller.setFocusable(false);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        options = activity.column();
        if (description != null) {
            TextView text = activity.label(description, 15, MainActivity.MUTED, false);
            text.setLineSpacing(activity.dp(3), 1f);
            options.addView(text, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        scroller.addView(options, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(MATCH, WRAP);
        content.topMargin = activity.dp(16);
        panel.addView(scroller, content);

        actions = activity.row();
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(MATCH, WRAP);
        footer.topMargin = activity.dp(20);
        panel.addView(actions, footer);
        setContentView(panel);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(.72f);
    }

    static void choose(MainActivity activity, String title, String description, String[] choices,
                       int selected, String close, IntConsumer onChoose) {
        ConsoleDialog dialog = new ConsoleDialog(activity, title, description);
        for (int i = 0; i < choices.length; i++) {
            final int index = i;
            LinearLayout option = activity.menuRow(choices[i], null, selected == i ? "✓" : "›",
                    selected == i, () -> {
                        dialog.dismiss();
                        onChoose.accept(index);
                    });
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(MATCH, WRAP);
            if (i > 0 || description != null) space.topMargin = activity.dp(6);
            dialog.options.addView(option, space);
            if (dialog.defaultFocus == null || selected == i) dialog.defaultFocus = option;
        }
        Button cancel = dialog.action(close, false, dialog::cancel);
        if (dialog.defaultFocus == null) dialog.defaultFocus = cancel;
        dialog.show();
    }

    static void notice(MainActivity activity, String title, String description, String confirm,
                       Runnable onConfirm, String close) {
        ConsoleDialog dialog = new ConsoleDialog(activity, title, description);
        if (close != null) dialog.defaultFocus = dialog.action(close, false, dialog::cancel);
        Button button = dialog.action(confirm, true, () -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        if (dialog.defaultFocus == null) dialog.defaultFocus = button;
        dialog.show();
    }

    private Button action(String label, boolean primary, Runnable action) {
        Button button = activity.pill(label, null, primary, action);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(WRAP, activity.dp(48));
        if (actions.getChildCount() > 0) box.setMarginStart(activity.dp(8));
        actions.addView(button, box);
        return button;
    }

    @Override public void show() {
        super.show();
        int width = activity.getResources().getConfiguration().screenWidthDp;
        getWindow().setLayout(activity.dp(Math.min(550, width - 36)), WRAP);
        if (defaultFocus != null && !panel.isInTouchMode()) defaultFocus.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) cancel();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (getCurrentFocus() == null && defaultFocus != null) defaultFocus.requestFocusFromTouch();
            return super.dispatchKeyEvent(MainActivity.confirmKey(event));
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        int direction = MainActivity.hatDirection(event);
        if (direction != 0 && direction != hatDirection) {
            View focused = getCurrentFocus();
            MainActivity.moveFocus(focused == null ? defaultFocus : focused.focusSearch(direction), direction);
        }
        boolean handled = direction != 0 || hatDirection != 0;
        hatDirection = direction;
        return handled || super.dispatchGenericMotionEvent(event);
    }
}
