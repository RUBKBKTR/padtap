package io.padtap;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Settings and per-game layouts (profiles), persisted as JSON. */
final class Config {
    private final SharedPreferences sp;
    boolean showCursor = true, showMarks = false, grab = true;
    int current = 0;
    float hx = 1f, hy = 0.4f;
    boolean hintBottom = false;
    final List<Model.Profile> profiles = new ArrayList<>();

    Config(Context c) {
        sp = c.getSharedPreferences("padtap", Context.MODE_PRIVATE);
        try {
            fromJson(new JSONObject(sp.getString("cfg", "{}")));
        } catch (Exception ignored) {
        }
        if (profiles.isEmpty()) profiles.add(newProfile());
    }

    Model.Profile p() {
        if (profiles.isEmpty()) profiles.add(newProfile());
        current = Math.max(0, Math.min(current, profiles.size() - 1));
        return profiles.get(current);
    }

    Model.Profile newProfile() {
        int n = profiles.size() + 1;
        String name = "ゲーム" + n;
        boolean dup = true;
        while (dup) {
            dup = false;
            for (Model.Profile q : profiles) if (q.name.equals(name)) dup = true;
            if (dup) name = "ゲーム" + (++n);
        }
        return Model.Profile.defaults(name);
    }

    void save() {
        try {
            sp.edit().putString("cfg", toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Replace everything from JSON (used by the CI importer). */
    void importJson(String json) throws Exception {
        profiles.clear();
        fromJson(new JSONObject(json));
        if (profiles.isEmpty()) profiles.add(newProfile());
        save();
    }

    private void fromJson(JSONObject o) throws Exception {
        showCursor = o.optBoolean("cursor", true);
        showMarks = o.optBoolean("marks", false);
        grab = o.optBoolean("grab", true);
        current = o.optInt("current", 0);
        hx = (float) o.optDouble("hx", 1);
        hy = (float) o.optDouble("hy", 0.4);
        hintBottom = o.optBoolean("hintBottom", false);
        float oldSpeed = (float) o.optDouble("speed", 1);
        JSONArray a = o.optJSONArray("profiles");
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject q = a.getJSONObject(i);
            Model.Profile p = new Model.Profile(q.optString("name", "ゲーム" + (i + 1)));
            JSONObject bs = q.optJSONObject("btns");
            if (bs != null) {
                Iterator<String> it = bs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject j = bs.getJSONObject(k);
                    Model.Btn b = new Model.Btn();
                    b.target = j.optInt("t", Model.T_POS);
                    b.x = (float) j.optDouble("x", 0.5);
                    b.y = (float) j.optDouble("y", 0.5);
                    b.mode = j.optInt("m", Model.M_HOLD);
                    b.interval = j.optInt("i", 100);
                    b.size = (float) j.optDouble("s", 48);
                    p.btns.put(Integer.parseInt(k), b);
                }
            } else {
                // v1 format: "pos" {code:[x,y]} + "cursor" [codes]
                JSONObject ps = q.optJSONObject("pos");
                if (ps != null) {
                    Iterator<String> it = ps.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONArray xy = ps.getJSONArray(k);
                        Model.Btn b = new Model.Btn();
                        b.x = (float) xy.getDouble(0);
                        b.y = (float) xy.getDouble(1);
                        p.btns.put(Integer.parseInt(k), b);
                    }
                }
                JSONArray cs = q.optJSONArray("cursor");
                if (cs != null) for (int j = 0; j < cs.length(); j++) p.btns.put(cs.getInt(j), new Model.Btn(Model.T_CUR_L));
                p.sticks[0].speed = oldSpeed;
            }
            JSONArray ss = q.optJSONArray("sticks");
            if (ss != null) {
                for (int s = 0; s < 2 && s < ss.length(); s++) {
                    JSONObject j = ss.getJSONObject(s);
                    Model.Stick k = p.sticks[s];
                    k.mode = j.optInt("m", k.mode);
                    k.cx = (float) j.optDouble("cx", k.cx);
                    k.cy = (float) j.optDouble("cy", k.cy);
                    k.radius = (float) j.optDouble("r", k.radius);
                    k.unlimited = j.optBoolean("u", k.unlimited);
                    k.dead = (float) j.optDouble("d", k.dead);
                    k.speed = (float) j.optDouble("sp", k.speed);
                }
            }
            profiles.add(p);
        }
    }

    private JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", 2);
        o.put("cursor", showCursor);
        o.put("marks", showMarks);
        o.put("grab", grab);
        o.put("current", current);
        o.put("hx", hx);
        o.put("hy", hy);
        o.put("hintBottom", hintBottom);
        JSONArray a = new JSONArray();
        for (Model.Profile p : profiles) {
            JSONObject q = new JSONObject();
            q.put("name", p.name);
            JSONObject bs = new JSONObject();
            for (Map.Entry<Integer, Model.Btn> e : p.btns.entrySet()) {
                Model.Btn b = e.getValue();
                JSONObject j = new JSONObject();
                j.put("t", b.target);
                j.put("x", (double) b.x);
                j.put("y", (double) b.y);
                j.put("m", b.mode);
                j.put("i", b.interval);
                j.put("s", (double) b.size);
                bs.put(String.valueOf(e.getKey()), j);
            }
            q.put("btns", bs);
            JSONArray ss = new JSONArray();
            for (Model.Stick k : p.sticks) {
                JSONObject j = new JSONObject();
                j.put("m", k.mode);
                j.put("cx", (double) k.cx);
                j.put("cy", (double) k.cy);
                j.put("r", (double) k.radius);
                j.put("u", k.unlimited);
                j.put("d", (double) k.dead);
                j.put("sp", (double) k.speed);
                ss.put(j);
            }
            q.put("sticks", ss);
            a.put(q);
        }
        o.put("profiles", a);
        return o;
    }
}
