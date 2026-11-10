package io.padtap;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Settings and per-game button layouts (profiles). */
final class Config {
    static final int BTN_A = 0x130;

    static final class Profile {
        String name;
        /** button code -> normalized screen position {x, y} */
        final LinkedHashMap<Integer, float[]> pos = new LinkedHashMap<>();
        /** buttons that tap at the cursor */
        final LinkedHashSet<Integer> cursor = new LinkedHashSet<>();

        Profile(String n) {
            name = n;
        }

        void setPos(int c, float x, float y) {
            cursor.remove(c);
            pos.put(c, new float[]{x, y});
        }

        void setCursor(int c) {
            pos.remove(c);
            cursor.add(c);
        }

        void clear(int c) {
            pos.remove(c);
            cursor.remove(c);
        }

        boolean has(int c) {
            return pos.containsKey(c) || cursor.contains(c);
        }
    }

    private final SharedPreferences sp;
    float speed = 1f;
    boolean showCursor = true, showMarks = false, grab = true;
    int current = 0;
    float hx = 1f, hy = 0.4f;
    final List<Profile> profiles = new ArrayList<>();

    Config(Context c) {
        sp = c.getSharedPreferences("padtap", Context.MODE_PRIVATE);
        load();
    }

    Profile p() {
        if (profiles.isEmpty()) profiles.add(newProfile());
        current = Math.max(0, Math.min(current, profiles.size() - 1));
        return profiles.get(current);
    }

    Profile newProfile() {
        int n = profiles.size() + 1;
        String name = "ゲーム" + n;
        boolean dup = true;
        while (dup) {
            dup = false;
            for (Profile q : profiles) if (q.name.equals(name)) dup = true;
            if (dup) name = "ゲーム" + (++n);
        }
        Profile p = new Profile(name);
        p.cursor.add(BTN_A);
        return p;
    }

    private void load() {
        try {
            JSONObject o = new JSONObject(sp.getString("cfg", "{}"));
            speed = (float) o.optDouble("speed", 1);
            showCursor = o.optBoolean("cursor", true);
            showMarks = o.optBoolean("marks", false);
            grab = o.optBoolean("grab", true);
            current = o.optInt("current", 0);
            hx = (float) o.optDouble("hx", 1);
            hy = (float) o.optDouble("hy", 0.4);
            JSONArray a = o.optJSONArray("profiles");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject q = a.getJSONObject(i);
                    Profile p = new Profile(q.optString("name", "ゲーム" + (i + 1)));
                    JSONObject ps = q.optJSONObject("pos");
                    if (ps != null) {
                        Iterator<String> it = ps.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            JSONArray xy = ps.getJSONArray(k);
                            p.pos.put(Integer.parseInt(k), new float[]{(float) xy.getDouble(0), (float) xy.getDouble(1)});
                        }
                    }
                    JSONArray cs = q.optJSONArray("cursor");
                    if (cs != null) for (int j = 0; j < cs.length(); j++) p.cursor.add(cs.getInt(j));
                    profiles.add(p);
                }
            }
        } catch (Exception ignored) {
        }
        if (profiles.isEmpty()) profiles.add(newProfile());
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("speed", speed);
            o.put("cursor", showCursor);
            o.put("marks", showMarks);
            o.put("grab", grab);
            o.put("current", current);
            o.put("hx", hx);
            o.put("hy", hy);
            JSONArray a = new JSONArray();
            for (Profile p : profiles) {
                JSONObject q = new JSONObject();
                q.put("name", p.name);
                JSONObject ps = new JSONObject();
                for (Map.Entry<Integer, float[]> e : p.pos.entrySet()) {
                    JSONArray xy = new JSONArray();
                    xy.put((double) e.getValue()[0]);
                    xy.put((double) e.getValue()[1]);
                    ps.put(String.valueOf(e.getKey()), xy);
                }
                q.put("pos", ps);
                JSONArray cs = new JSONArray();
                for (int c : p.cursor) cs.put(c);
                q.put("cursor", cs);
                a.put(q);
            }
            o.put("profiles", a);
            sp.edit().putString("cfg", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static boolean isDpad(int c) {
        return c >= 0x220 && c <= 0x223;
    }

    static boolean isSelect(int c) {
        return c == 0x13a || c == 158; // BTN_SELECT / KEY_BACK
    }

    static boolean isStart(int c) {
        return c == 0x13b || c == 139; // BTN_START / KEY_MENU
    }

    static String label(int c) {
        switch (c) {
            case 0x130: return "A";
            case 0x131: return "B";
            case 0x132: return "C";
            case 0x133: return "X";
            case 0x134: return "Y";
            case 0x135: return "Z";
            case 0x136: return "LB";
            case 0x137: return "RB";
            case 0x138: return "LT";
            case 0x139: return "RT";
            case 0x13a: case 158: return "SELECT";
            case 0x13b: case 139: return "START";
            case 0x13c: case 172: return "HOME";
            case 0x13d: return "L3";
            case 0x13e: return "R3";
            case 0x220: return "↑";
            case 0x221: return "↓";
            case 0x222: return "←";
            case 0x223: return "→";
            default:
                if (c >= 0x120 && c < 0x130) return "B" + (c - 0x120 + 1);
                if (c >= 0x2c0 && c < 0x300) return "T" + (c - 0x2c0 + 1);
                return "#" + Integer.toHexString(c);
        }
    }
}
