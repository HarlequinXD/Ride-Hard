package com.harlequinxd.eryndor;

import java.util.Random;

/**
 * Time of day + weather. Updated on the GL thread; a few fields are read by the HUD.
 * All colours are linear-ish 0..1 RGB fed straight into the Mode 7 shader.
 */
final class SkyState {

    static final String[] WEATHER_NAMES = {"Clear", "Cloudy", "Overcast", "Rain", "Thunderstorm", "Mist"};

    //                         cover  dark   fog     rain  grey  mist  wind
    private static final float[][] WEATHER = {
            {0.26f, 0.00f, 0.0035f, 0.0f, 0.00f, 0.0f, 6f},   // clear
            {0.48f, 0.10f, 0.0045f, 0.0f, 0.12f, 0.0f, 9f},   // cloudy
            {0.86f, 0.45f, 0.0070f, 0.0f, 0.55f, 0.0f, 11f},  // overcast
            {0.96f, 0.62f, 0.0130f, 0.7f, 0.70f, 0.0f, 14f},  // rain
            {1.00f, 0.82f, 0.0180f, 1.0f, 0.85f, 0.0f, 22f},  // thunderstorm
            {0.70f, 0.30f, 0.0450f, 0.0f, 0.45f, 1.0f, 3f},   // mist
    };

    /** Real seconds for a full 24h cycle (x20 when fast time is on). */
    private static final float DAY_SECONDS = 480f;

    // ---- read by HUD (UI thread)
    volatile float hour = 9.5f;
    volatile int weather = 0;
    volatile boolean fastTime = false;
    volatile boolean worldReady = false;
    private volatile int weatherRequests = 0;
    private int weatherRequestsSeen = 0;

    // ---- current blended weather params
    private final float[] cur = WEATHER[0].clone();
    private float weatherTimer = 90f;
    private float wet = 0f;
    private float flash = 0f, flashTimer = 6f;
    private final Random rnd = new Random();

    // ---- shader outputs
    final float[] sunDir = new float[3];
    final float[] skyTop = new float[3];
    final float[] skyHor = new float[3];
    final float[] sunCol = new float[3];
    final float[] ambient = new float[3];
    final float[] fogCol = new float[3];
    final float[] mtnCol = new float[3];
    float fogDensity, stars, cover, cloudDark, rain, sunVis, mist, windSpeed;
    /** How grey the weather makes the light, 0..1 (used by the colour grade). */
    float greyness;
    float wetness, lightning;

    void requestNextWeather() {
        weatherRequests++;
    }

    void update(float dt) {
        // ---- time
        float h = hour + dt * 24f / DAY_SECONDS * (fastTime ? 20f : 1f);
        if (h >= 24f) h -= 24f;
        hour = h;

        // ---- weather target
        int req = weatherRequests;
        if (req != weatherRequestsSeen) {
            weatherRequestsSeen = req;
            weather = (weather + 1) % WEATHER.length;
            weatherTimer = 120f;
        }
        weatherTimer -= dt;
        if (weatherTimer <= 0f) {
            int next;
            do { next = pickWeather(); } while (next == weather);
            weather = next;
            weatherTimer = 70f + rnd.nextFloat() * 90f;
        }
        float[] tgt = WEATHER[weather];
        float k = 1f - (float) Math.exp(-dt / 6f);
        for (int i = 0; i < cur.length; i++) cur[i] += (tgt[i] - cur[i]) * k;
        cover = cur[0];
        cloudDark = cur[1];
        rain = cur[3];
        float grey = cur[4];
        greyness = grey;
        mist = cur[5];
        windSpeed = cur[6];

        // ground gets wet in rain, dries slowly afterwards
        if (rain > 0.25f) wet = Math.min(1f, wet + dt * 0.08f * rain);
        else wet = Math.max(0f, wet - dt * 0.012f);
        wetness = wet;

        // lightning
        flash *= (float) Math.exp(-dt * 9f);
        if (weather == 4 && cur[3] > 0.8f) {
            flashTimer -= dt;
            if (flashTimer <= 0f) {
                flash = 1f;
                flashTimer = rnd.nextFloat() < 0.3f ? 0.15f : 3f + rnd.nextFloat() * 9f; // occasional double strike
            }
        }
        lightning = flash;

        // ---- sun: rises east (+x) at 06:00, sets west at 18:00
        double a = (h - 6.0) / 24.0 * Math.PI * 2.0;
        float sx = (float) Math.cos(a), sy = (float) Math.sin(a), sz = -0.35f;
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sunDir[0] = sx / len;
        sunDir[1] = sy / len;
        sunDir[2] = sz / len;
        float e = sunDir[1];

        float tn = smooth(-0.28f, 0.02f, e);   // night -> twilight
        float td = smooth(0.00f, 0.32f, e);    // twilight -> day

        mix3(skyTop, NIGHT_TOP, TWI_TOP, DAY_TOP, tn, td);
        mix3(skyHor, NIGHT_HOR, TWI_HOR, DAY_HOR, tn, td);
        mix3(sunCol, NIGHT_SUN, TWI_SUN, DAY_SUN, tn, td);
        mix3(ambient, NIGHT_AMB, TWI_AMB, DAY_AMB, tn, td);

        // overcast: desaturate + darken
        float bright = 0.25f + 0.75f * td;
        desat(skyTop, grey, 0.62f * bright + 0.05f);
        desat(skyHor, grey, 0.54f * bright + 0.05f);
        for (int i = 0; i < 3; i++) ambient[i] *= 1f - 0.38f * grey;

        // mist: everything fades to soft grey
        for (int i = 0; i < 3; i++) {
            float d = MIST[i] * (0.15f + 0.85f * bright);
            skyHor[i] += (d - skyHor[i]) * mist * 0.9f;
            skyTop[i] += (d * 0.92f - skyTop[i]) * mist * 0.7f;
            ambient[i] *= 1f - 0.15f * mist;
            ambient[i] += flash * 0.9f;
            fogCol[i] = skyHor[i];
            mtnCol[i] = ROCK[i] * ambient[i];
        }

        sunVis = smooth(-0.06f, 0.04f, e) * (1f - grey * 0.92f) * (1f - mist * 0.75f);
        stars = (1f - smooth(-0.22f, -0.04f, e)) * (1f - grey) * (1f - mist);
        fogDensity = cur[2];
    }

    private int pickWeather() {
        float r = rnd.nextFloat();
        if (r < 0.34f) return 0;
        if (r < 0.58f) return 1;
        if (r < 0.72f) return 2;
        if (r < 0.84f) return 3;
        if (r < 0.92f) return 4;
        return 5;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    private static void mix3(float[] out, float[] n, float[] t, float[] d, float tn, float td) {
        for (int i = 0; i < 3; i++) {
            float a = n[i] + (t[i] - n[i]) * tn;
            out[i] = a + (d[i] - a) * td;
        }
    }

    private static void desat(float[] c, float amount, float targetLum) {
        float l = targetLum;
        c[0] += (l * 0.97f - c[0]) * amount;
        c[1] += (l - c[1]) * amount;
        c[2] += (l * 1.06f - c[2]) * amount;
    }

    private static final float[] NIGHT_TOP = {0.015f, 0.025f, 0.07f};
    private static final float[] NIGHT_HOR = {0.05f, 0.07f, 0.14f};
    private static final float[] NIGHT_SUN = {0.0f, 0.0f, 0.0f};
    private static final float[] NIGHT_AMB = {0.20f, 0.24f, 0.38f};

    private static final float[] TWI_TOP = {0.20f, 0.22f, 0.45f};
    private static final float[] TWI_HOR = {0.98f, 0.55f, 0.30f};
    private static final float[] TWI_SUN = {1.00f, 0.50f, 0.22f};
    private static final float[] TWI_AMB = {0.72f, 0.52f, 0.46f};

    private static final float[] DAY_TOP = {0.20f, 0.44f, 0.86f};
    private static final float[] DAY_HOR = {0.80f, 0.85f, 0.90f};
    private static final float[] DAY_SUN = {1.00f, 0.95f, 0.82f};
    private static final float[] DAY_AMB = {1.05f, 1.00f, 0.92f};

    private static final float[] MIST = {0.72f, 0.75f, 0.78f};
    private static final float[] ROCK = {0.44f, 0.47f, 0.55f};
}
