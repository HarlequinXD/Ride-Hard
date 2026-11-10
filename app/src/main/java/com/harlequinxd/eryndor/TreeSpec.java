package com.harlequinxd.eryndor;

import java.util.Locale;

/**
 * Everything the Tree Builder can edit about one species. Values live in one float array so the
 * builder UI can be generated from the parameter table.
 */
final class TreeSpec {

    /** One editable parameter: key (for saving), label, tab, range, and whether it is a whole number. */
    static final class Param {
        final String key, label, group;
        final float min, max;
        final boolean whole;
        final String[] choices;

        Param(String key, String label, String group, float min, float max, boolean whole, String... choices) {
            this.key = key; this.label = label; this.group = group;
            this.min = min; this.max = max; this.whole = whole; this.choices = choices;
        }
    }

    static final String[] SHAPES = {"Round", "Cone", "Column", "Weeping", "Spreading"};
    static final String[] LEAVES = {"Oval", "Oak", "Maple", "Birch", "Willow", "Needles", "Blossom", "Heart"};
    static final String[] BARKS = {"Rough", "Birch", "Pine plates", "Smooth"};

    static final int SEED = 0, HEIGHT = 1, TRUNK_R = 2, TAPER = 3, BEND = 4, WIGGLE = 5, FLARE = 6, CROWN = 7,
            B1_COUNT = 8, B1_START = 9, B1_ANGLE = 10, B1_LENGTH = 11, B1_DROOP = 12, B1_CURL = 13, B1_RADIUS = 14, SPIRAL = 15,
            B2_COUNT = 16, B2_ANGLE = 17, B2_LENGTH = 18, B2_DROOP = 19,
            TW_COUNT = 20, TW_ANGLE = 21, TW_LENGTH = 22,
            LEAF_SHAPE = 23, LEAF_SIZE = 24, LEAF_COUNT = 25, LEAF_SPREAD = 26, LEAF_HANG = 27,
            LEAF_R = 28, LEAF_G = 29, LEAF_B = 30, LEAF2_R = 31, LEAF2_G = 32, LEAF2_B = 33, LEAF_MIX = 34,
            BARK_TYPE = 35, BARK_R = 36, BARK_G = 37, BARK_B = 38;

    static final Param[] PARAMS = {
            new Param("seed", "Random seed", "Trunk", 0, 9999, true),
            new Param("height", "Height (m)", "Trunk", 2, 24, false),
            new Param("trunkRadius", "Trunk radius (m)", "Trunk", 0.05f, 1.0f, false),
            new Param("taper", "Taper (top / base)", "Trunk", 0.05f, 1.0f, false),
            new Param("bend", "Bend", "Trunk", 0, 1, false),
            new Param("wiggle", "Wiggle", "Trunk", 0, 1, false),
            new Param("flare", "Root flare", "Trunk", 0, 1.5f, false),
            new Param("crown", "Crown shape", "Trunk", 0, 4, true, SHAPES),

            new Param("b1Count", "Branches", "Branches", 0, 40, true),
            new Param("b1Start", "Start height", "Branches", 0, 0.9f, false),
            new Param("b1Angle", "Angle from trunk", "Branches", 5, 120, false),
            new Param("b1Length", "Length", "Branches", 0.05f, 1.2f, false),
            new Param("b1Droop", "Droop", "Branches", -1, 1, false),
            new Param("b1Curl", "Curl", "Branches", 0, 1, false),
            new Param("b1Radius", "Thickness", "Branches", 0.1f, 0.9f, false),
            new Param("spiral", "Spiral (degrees)", "Branches", 0, 180, false),

            new Param("b2Count", "Sub-branches", "Sub-branches", 0, 12, true),
            new Param("b2Angle", "Angle", "Sub-branches", 5, 100, false),
            new Param("b2Length", "Length", "Sub-branches", 0.1f, 1, false),
            new Param("b2Droop", "Droop", "Sub-branches", -1, 1, false),

            new Param("twCount", "Twigs", "Twigs", 0, 12, true),
            new Param("twAngle", "Angle", "Twigs", 5, 100, false),
            new Param("twLength", "Length", "Twigs", 0.05f, 0.8f, false),

            new Param("leafShape", "Leaf shape", "Leaves", 0, 7, true, LEAVES),
            new Param("leafSize", "Leaf size (m)", "Leaves", 0.03f, 0.5f, false),
            new Param("leafCount", "Leaves per twig", "Leaves", 0, 20, true),
            new Param("leafSpread", "Leafy part of twig", "Leaves", 0, 1, false),
            new Param("leafHang", "Leaf hang", "Leaves", -1, 1, false),
            new Param("leafR", "Leaf red", "Leaves", 0, 1, false),
            new Param("leafG", "Leaf green", "Leaves", 0, 1, false),
            new Param("leafB", "Leaf blue", "Leaves", 0, 1, false),
            new Param("leaf2R", "Second colour red", "Leaves", 0, 1, false),
            new Param("leaf2G", "Second colour green", "Leaves", 0, 1, false),
            new Param("leaf2B", "Second colour blue", "Leaves", 0, 1, false),
            new Param("leafMix", "Second colour amount", "Leaves", 0, 1, false),

            new Param("barkType", "Bark", "Bark", 0, 3, true, BARKS),
            new Param("barkR", "Bark red", "Bark", 0, 1, false),
            new Param("barkG", "Bark green", "Bark", 0, 1, false),
            new Param("barkB", "Bark blue", "Bark", 0, 1, false),
    };

    static final String[] GROUPS = {"Trunk", "Branches", "Sub-branches", "Twigs", "Leaves", "Bark"};

    String name;
    final float[] v = new float[PARAMS.length];

    TreeSpec(String name, float... values) {
        this.name = name;
        System.arraycopy(values, 0, v, 0, Math.min(values.length, v.length));
    }

    TreeSpec copy() {
        TreeSpec t = new TreeSpec(name);
        System.arraycopy(v, 0, t.v, 0, v.length);
        return t;
    }

    float get(int i) {
        return v[i];
    }

    int whole(int i) {
        return Math.round(v[i]);
    }

    void set(int i, float value) {
        Param p = PARAMS[i];
        value = Math.max(p.min, Math.min(p.max, value));
        v[i] = p.whole ? Math.round(value) : value;
    }

    /** Plain "key=value" text, one per line: easy to read, diff, paste and commit. */
    String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(name).append('\n');
        for (int i = 0; i < PARAMS.length; i++)
            sb.append(PARAMS[i].key).append('=').append(String.format(Locale.US, "%.4f", v[i])).append('\n');
        return sb.toString();
    }

    static TreeSpec fromText(String text, TreeSpec fallback) {
        TreeSpec t = fallback.copy();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim(), val = line.substring(eq + 1).trim();
            if (k.equals("name")) { t.name = val; continue; }
            for (int i = 0; i < PARAMS.length; i++)
                if (PARAMS[i].key.equals(k)) {
                    try { t.set(i, Float.parseFloat(val)); } catch (NumberFormatException ignored) { }
                }
        }
        return t;
    }

    // ------------------------------------------------------------------ presets
    // order: seed height trunkR taper bend wiggle flare crown | b1 count start angle length droop curl radius spiral |
    //        b2 count angle length droop | tw count angle length | leaf shape size count spread hang r g b r2 g2 b2 mix | bark type r g b
    static TreeSpec[] presets() {
        return new TreeSpec[]{
                new TreeSpec("Oak", 11, 11, 0.42f, 0.25f, 0.25f, 0.35f, 0.7f, 4,
                        9, 0.28f, 62, 0.55f, 0.05f, 0.35f, 0.5f, 137.5f,
                        5, 48, 0.55f, 0.1f, 4, 45, 0.35f,
                        1, 0.13f, 11, 0.7f, 0.2f, 0.19f, 0.31f, 0.11f, 0.30f, 0.40f, 0.14f, 0.3f, 0, 0.30f, 0.25f, 0.20f),
                new TreeSpec("Silver birch", 23, 12, 0.17f, 0.2f, 0.15f, 0.25f, 0.3f, 2,
                        14, 0.4f, 38, 0.28f, 0.25f, 0.2f, 0.4f, 137.5f,
                        4, 40, 0.55f, 0.5f, 4, 40, 0.4f,
                        3, 0.10f, 12, 0.8f, 0.6f, 0.42f, 0.52f, 0.20f, 0.55f, 0.58f, 0.22f, 0.4f, 1, 0.86f, 0.85f, 0.80f),
                new TreeSpec("Scots pine", 37, 13, 0.27f, 0.15f, 0.2f, 0.25f, 0.4f, 1,
                        16, 0.45f, 78, 0.28f, 0.2f, 0.15f, 0.4f, 137.5f,
                        3, 50, 0.45f, 0.1f, 3, 40, 0.35f,
                        5, 0.16f, 8, 0.9f, 0.0f, 0.11f, 0.23f, 0.13f, 0.16f, 0.28f, 0.14f, 0.3f, 2, 0.42f, 0.27f, 0.17f),
                new TreeSpec("Spruce", 41, 14, 0.24f, 0.08f, 0.05f, 0.1f, 0.3f, 1,
                        26, 0.08f, 95, 0.32f, 0.45f, 0.05f, 0.35f, 137.5f,
                        4, 60, 0.45f, 0.4f, 3, 50, 0.4f,
                        5, 0.15f, 8, 1.0f, 0.3f, 0.09f, 0.19f, 0.14f, 0.12f, 0.24f, 0.17f, 0.3f, 2, 0.30f, 0.22f, 0.17f),
                new TreeSpec("Willow", 53, 9, 0.38f, 0.3f, 0.2f, 0.3f, 0.6f, 3,
                        10, 0.35f, 55, 0.5f, 0.6f, 0.2f, 0.45f, 137.5f,
                        5, 30, 0.7f, 0.9f, 3, 20, 0.5f,
                        4, 0.12f, 14, 0.95f, 0.9f, 0.40f, 0.52f, 0.20f, 0.50f, 0.58f, 0.25f, 0.4f, 0, 0.32f, 0.27f, 0.21f),
                new TreeSpec("Dead tree", 67, 9, 0.28f, 0.2f, 0.4f, 0.6f, 0.5f, 0,
                        7, 0.35f, 50, 0.45f, -0.1f, 0.5f, 0.45f, 137.5f,
                        3, 45, 0.5f, 0.0f, 2, 40, 0.35f,
                        0, 0.1f, 0, 0.5f, 0.0f, 0.3f, 0.3f, 0.2f, 0.3f, 0.3f, 0.2f, 0.0f, 3, 0.44f, 0.41f, 0.38f),
                new TreeSpec("Autumn maple", 71, 9, 0.30f, 0.25f, 0.15f, 0.3f, 0.5f, 0,
                        10, 0.35f, 55, 0.5f, 0.0f, 0.3f, 0.45f, 137.5f,
                        5, 45, 0.55f, 0.1f, 4, 45, 0.35f,
                        2, 0.14f, 10, 0.7f, 0.2f, 0.72f, 0.30f, 0.08f, 0.85f, 0.58f, 0.12f, 0.35f, 0, 0.28f, 0.22f, 0.18f),
                new TreeSpec("Blossom", 83, 7, 0.22f, 0.3f, 0.3f, 0.35f, 0.5f, 4,
                        8, 0.35f, 60, 0.55f, 0.0f, 0.35f, 0.5f, 137.5f,
                        5, 50, 0.55f, 0.1f, 4, 50, 0.35f,
                        6, 0.09f, 13, 0.8f, 0.0f, 0.93f, 0.63f, 0.72f, 0.98f, 0.92f, 0.95f, 0.3f, 3, 0.24f, 0.17f, 0.15f),
                new TreeSpec("Ancient yew", 97, 6, 0.65f, 0.3f, 0.3f, 0.5f, 1.0f, 4,
                        14, 0.1f, 70, 0.75f, 0.1f, 0.3f, 0.5f, 137.5f,
                        5, 55, 0.5f, 0.2f, 4, 50, 0.35f,
                        5, 0.13f, 11, 0.9f, 0.1f, 0.09f, 0.17f, 0.10f, 0.12f, 0.20f, 0.12f, 0.3f, 0, 0.36f, 0.25f, 0.20f),
                new TreeSpec("Poplar", 101, 15, 0.22f, 0.15f, 0.05f, 0.15f, 0.3f, 2,
                        22, 0.12f, 22, 0.2f, -0.2f, 0.1f, 0.35f, 137.5f,
                        3, 25, 0.5f, -0.2f, 3, 30, 0.4f,
                        7, 0.11f, 12, 0.85f, 0.1f, 0.24f, 0.35f, 0.14f, 0.32f, 0.42f, 0.16f, 0.3f, 0, 0.35f, 0.30f, 0.24f),
        };
    }
}
