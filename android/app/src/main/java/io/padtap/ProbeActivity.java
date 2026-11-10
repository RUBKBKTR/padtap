package io.padtap;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Test target for CI: logs touches and keys it receives (tag PadTapProbe). */
public class ProbeActivity extends Activity {
    private static final String TAG = "PadTapProbe";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        View v = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                Log.i(TAG, "touch " + MotionEvent.actionToString(e.getAction()) + " n=" + e.getPointerCount()
                        + " x=" + (int) e.getRawX() + " y=" + (int) e.getRawY());
                return true;
            }
        };
        v.setBackgroundColor(Color.rgb(20, 24, 40));
        setContentView(v);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        Log.i(TAG, "key " + KeyEvent.keyCodeToString(code));
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        return true;
    }
}
