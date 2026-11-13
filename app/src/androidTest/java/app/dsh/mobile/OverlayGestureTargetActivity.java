package app.dsh.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 独立运行的无副作用触摸靶场，不依赖被测 APK 的 Kotlin 运行库。 */
public final class OverlayGestureTargetActivity extends Activity {
    private TextView label;
    private int touches;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        label = new TextView(this);
        label.setTextSize(18f);
        label.setTextColor(Color.BLACK);
        label.setText("Overlay gesture target\nTouches: 0");
        label.setPadding(24, 60, 24, 24);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF4F8FC);
        root.addView(label);
        setContentView(root);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            touches++;
            label.setText("Overlay gesture target\nTouches: " + touches
                + "\nHeld: " + (event.getEventTime() - event.getDownTime()) + " ms");
        }
        return true;
    }
}
