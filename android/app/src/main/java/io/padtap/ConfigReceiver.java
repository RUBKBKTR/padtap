package io.padtap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * Imports a whole config as base64 JSON. Protected by android.permission.DUMP,
 * so only the shell (adb / CI) can send it.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String b64 = i.getStringExtra("b64");
        if (b64 == null) return;
        try {
            String json = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
            Pad pad = Pad.get(c);
            pad.cfg.importJson(json);
            pad.changed();
            Log.i("PadTapProbe", "config imported");
        } catch (Exception e) {
            Log.e("PadTapProbe", "config import failed", e);
        }
    }
}
