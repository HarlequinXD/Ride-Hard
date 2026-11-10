#version 300 es
// Chronicles of Eryndor: Hollowspire
// Pixel-voxel terrain: every screen pixel marches a ray across a layered heightmap (floor, cave
// ceiling, rock top) so hills, cliffs, overhangs and caves all come from one texture read per
// step. Colour comes from the painted ground texture (nearest texels, pixel perfect), steep faces
// and cave rock get procedural stone. The sky is a Mode 7 cloud plane with sun, moon, stars,
// distant mountains and the Hollowspire. Rendered to a low-res buffer, dithered to 15-bit.
precision highp float;
precision highp int;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uGround;   // rgb = ground colour, a = 0 open water, 0.5..1 land (lit fraction: tree shadows)
uniform sampler2D uCloud;
uniform sampler2D uHeight;   // r = floor, g = cave ceiling, b = rock top, a = sky openness
uniform sampler2D uDetail;   // fine relief on the floor: grass, pebbles, shrubs, rocks, walls (0..3 m)
uniform sampler2D uMat;      // material id per texel: 0 grass, 1 earth, 2 stone, 3 wood, 4 water, 5 plant
uniform sampler2D uFerns;    // one optional fern per 4 m cell: offset xz, size, presence + rotation
uniform sampler2D uTrees;    // one optional tree per 16 m cell: offset xz, species * 25, presence + size
uniform sampler2D uTreePrims; // RGBA32F: 3 texels per primitive (branch segment or leaf)
uniform sampler2D uTreeCells; // RG32F: per zone (first index, count)
uniform sampler2D uTreeIdx;   // R32F: primitive ids listed by zone
uniform sampler2D uLeafAtlas; // 8 leaf shapes, rgb detail + alpha outline
uniform vec4 uTreeMeta[60];   // 6 per species: min+gx, max+gy, cellBase gz bark halfWidth, leafA+trunkR, leafB, bark+height
uniform vec4 uPreview;        // Tree Builder: xyz base position, w = species slot (-1 = off)
uniform float uPreviewYaw;
uniform sampler2D uArmCol;    // first-person arms: colour, back view rows 0-351, palm view 352-703
uniform sampler2D uArmNrm;    // same layout: arm-local normals from the normal map
uniform sampler2D uArmHgt;    // r = top (back of hand) height, g = bottom (palm) height
uniform mat3 uArmRot[2];      // arm-local -> camera space (0 right arm, 1 left arm)
uniform vec3 uArmPos[2];      // arm-local origin (the shoulder end) in camera space, metres
uniform vec4 uFing[10];       // per finger (right arm then left, thumb first): knuckle u, z, direction u, z
uniform float uFingK[10];     // per finger curl: curvature (radians per grid unit)
uniform float uArmShow;
uniform vec3 uSwordO;         // sword in right-arm grid space: grip centre,
uniform vec3 uSwordB;         //   blade direction (out of the thumb side),
uniform vec3 uSwordE;         //   edge direction
uniform float uSwordShow;
uniform highp sampler2DArray uSurfCol;  // ground texture sets, one layer each: 0 grass, 1 cobblestone
uniform highp sampler2DArray uSurfNH;   // their normal maps (r, g) and height maps (b)

uniform vec2 uTanHalf;
uniform float uYaw;
uniform float uPitch;
uniform vec3 uCam;           // camera x, eye height, z (x/z wrapped to the 256 m tile)
uniform vec2 uCloudO;        // camera xz + wind drift wrapped to the 2000 m cloud tile

uniform vec3 uSunDir;
uniform vec3 uSkyTop;
uniform vec3 uSkyHor;
uniform vec3 uSunCol;
uniform vec3 uAmbient;
uniform vec3 uFogCol;
uniform vec3 uMtnCol;
uniform float uFog;
uniform float uStars;
uniform float uCover;
uniform float uCloudDark;
uniform float uRain;
uniform float uWet;
uniform float uFlash;
uniform float uTime;
uniform float uSunVis;
uniform float uMist;

const float CLOUD_H = 420.0;
const float HMAX = 48.0;
const float FAR = 700.0;
const float DMAX = 3.0;
const float DFAR = 70.0;      // detail relief fades out by here

float detailFade(float t) { return smoothstep(DFAR, DFAR * 0.4, t); }

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float ridge(float n) { return 1.0 - abs(2.0 * n - 1.0); }

float bayer2(vec2 a) { a = floor(a); return fract(dot(a, vec2(0.5, a.y * 0.75))); }
float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }

float cloudField(vec2 c) {
    return texture(uCloud, c / 2000.0).r * 0.62 + texture(uCloud, c / 666.6667 + 0.37).r * 0.38;
}

float coverage(float n) {
    float thr = 1.0 - uCover * 0.95;
    return smoothstep(thr - 0.06, thr + 0.22, n);
}

// Sky for an upward direction d (d.y >= 0). Also used, mirrored, for water reflections.
vec3 skyColor(vec3 d) {
    float h = d.y;
    vec3 sky = mix(uSkyHor, uSkyTop, pow(h, 0.55));
    float sd = dot(d, uSunDir);
    sky += uSunCol * (pow(max(sd, 0.0), 10.0) * 0.30 + pow(max(sd, 0.0), 180.0) * 0.55) * uSunVis;
    sky = mix(sky, vec3(1.0, 0.97, 0.88) * 1.4, smoothstep(0.99905, 0.99935, sd) * uSunVis);

    // moon and stars
    vec3 md = normalize(-uSunDir + vec3(0.0, 0.12, 0.0));
    float mdot = dot(d, md);
    float moon = smoothstep(0.99935, 0.99955, mdot);
    float crater = 0.85 + 0.15 * vnoise(d.xz * 900.0);
    sky += vec3(0.75, 0.80, 0.92) * pow(max(mdot, 0.0), 60.0) * 0.12 * uStars;
    sky = mix(sky, vec3(0.88, 0.90, 0.96) * crater, moon * uStars);
    vec2 sg = floor(d.xz / (d.y + 0.25) * 140.0);
    float star = step(0.9935, hash(sg)) * smoothstep(0.02, 0.25, d.y) * (1.0 - moon);
    sky += vec3(star * uStars * (0.55 + 0.45 * hash(sg + 7.0)));

    // Mode 7 clouds
    float ct = CLOUD_H / max(d.y, 0.015);
    float n = cloudField(uCloudO + d.xz * ct);
    float cd = coverage(n) * smoothstep(-0.01, 0.05, d.y);
    float thick = clamp((n - (1.0 - uCover * 0.95)) / 0.35, 0.0, 1.0);
    float toward = pow(max(sd, 0.0), 3.0);
    vec3 cLit = uAmbient * 0.92 + uSunCol * (0.18 + 0.45 * toward) * uSunVis;
    vec3 cCore = uAmbient * mix(0.70, 0.35, uCloudDark);
    vec3 ccol = mix(cLit, cCore, thick * (0.45 + 0.55 * uCloudDark));
    ccol = mix(ccol, uFogCol, 0.30 * (1.0 - smoothstep(0.0, 0.35, d.y)));
    sky = mix(sky, ccol, cd * 0.95);

    // ---- horizon: far snow-capped range, the Hollowspire, then forested foothills
    vec2 az = normalize(d.xz + vec2(1e-5));
    float ang = atan(az.x, -az.y);                       // 0 = north
    vec2 lit2 = normalize(uSunDir.xz + vec2(1e-5));
    float face = max(dot(-az, lit2), 0.0);
    float haze = 0.45 + 0.4 * uMist + 0.3 * clamp(uFog * 40.0 - 0.15, 0.0, 0.5);

    float m = 0.50 * vnoise(az * 3.0 + 10.0) + 0.32 * ridge(vnoise(az * 9.0 + 3.0)) + 0.18 * ridge(vnoise(az * 31.0 + 1.0));
    float mh = 0.006 + 0.115 * smoothstep(0.25, 0.92, m);
    if (h < mh) {
        float slope = vnoise(vec2(ang * 140.0, h * 500.0));
        vec3 mc = uMtnCol * (0.60 + 0.50 * face * uSunVis) * (0.85 + 0.25 * slope);
        float snow = step(mh * 0.58 + 0.008 * slope, h) * step(0.045, mh);
        mc = mix(mc, uAmbient * (0.80 + 0.30 * face * uSunVis), snow);
        sky = mix(mc, uFogCol, haze);
    }

    // the Hollowspire: a lone, slightly leaning black tower to the north
    float sx = ang - 0.035 - h * 0.025;
    float H = 0.24;
    float w = 0.004 + 0.020 * pow(max(1.0 - h / H, 0.0), 1.8);
    w += 0.007 * step(0.175, h) * step(h, 0.188);        // balcony ring
    float top = H - 0.02 * step(0.0, sx);                // broken crown
    if (abs(sx) < w && h < top) {
        vec3 tc = vec3(0.09, 0.09, 0.12) * (0.7 + 0.5 * uAmbient) * (0.8 + 0.4 * step(0.0, -sx) * face);
        float glow = smoothstep(0.0045, 0.0015, length(vec2(sx, (h - 0.205) * 0.6))) * (0.25 + uStars);
        tc += vec3(0.35, 0.95, 0.85) * glow;
        sky = mix(tc, uFogCol, haze * 0.6);
        sky += vec3(0.35, 0.95, 0.85) * glow * 0.6;
    }

    float hh = 0.003 + 0.013 * vnoise(az * 7.0 + 20.0) + 0.004 * vnoise(az * 40.0) + 0.002 * hash(floor(az * 400.0));
    if (h < hh) {
        vec3 fc = vec3(0.13, 0.19, 0.11) * uAmbient * (0.75 + 0.35 * face * uSunVis);
        sky = mix(fc, uFogCol, haze * 0.65);
    }

    sky = mix(sky, uFogCol, uMist * 0.75 * (1.0 - smoothstep(0.0, 0.5, h)));
    return sky;
}

// ---- per-pixel grass: individual blades computed at the exact point each ray reaches
const float GFAR = 12.0;
// ---- ground texture sets (picked by the terrain mask), tiling and point sampled.
//      Grass: two placements (the second turned 90 degrees and larger) picked per pixel by a
//      dithered noise so the tile doesn't repeat visibly. Cobblestones: one placement, so the rows
//      of stones stay straight.
const float GRASS_TILE = 1.25, GRASS_TILE2 = 1.7, COBBLE_TILE = 1.6;
const float LINES = 240.0;              // screen lines rendered (PS1 resolution)
const float SURF_FAR = 14.0;            // texture height is traced this far
const float GRASS_DEPTH = 0.035;        // height map white = this many metres of relief
const float COBBLE_DEPTH = 0.06;

vec2 surfUV(int layer, vec2 xz, out float turned) {
    turned = 0.0;
    if (layer == 1) return xz / COBBLE_TILE;
    float k = vnoise(xz * 0.31 + 11.0) + (bayer4(gl_FragCoord.xy) - 0.5) * 0.22;
    turned = step(0.5, k);
    return turned > 0.5 ? vec2(-xz.y, xz.x) / GRASS_TILE2 + vec2(0.37, 0.61) : xz / GRASS_TILE;
}

float surfLod(float t, float tile) {   // texture level for one screen pixel at distance t
    return log2(max(1.0, t * 2.0 * uTanHalf.y / LINES / (tile / 512.0)));
}

// per-pixel relief from the texture set's height map (cobbles bulge out of the mortar, grass clumps
// rise), only where the terrain mask picked a textured surface
float surfHeight(vec2 xz, float t) {
    float mid = floor(texture(uMat, xz / 256.0).r * 255.0 / 40.0 + 0.5);
    int layer = mid < 0.5 ? 0 : (mid > 1.5 && mid < 2.5 ? 1 : -1);
    if (layer < 0) return 0.0;
    float turned;
    vec2 uv = surfUV(layer, xz, turned);
    float tile = layer == 1 ? COBBLE_TILE : GRASS_TILE;
    float h = textureLod(uSurfNH, vec3(uv, float(layer)), surfLod(t, tile)).b;
    return h * (layer == 1 ? COBBLE_DEPTH : GRASS_DEPTH) * smoothstep(SURF_FAR, SURF_FAR * 0.5, t);
}


// ---- 3D ferns, evaluated per pixel as an implicit shape (no geometry, no voxel grid)
const float FERN_CELL = 4.0;
const float FERN_FAR = 38.0;
bool fernAt(vec3 p, out vec3 col, out float near) {
    col = vec3(0.0);
    near = 0.0;
    vec2 cell = floor(p.xz / FERN_CELL);
    vec4 f = texture(uFerns, (cell + 0.5) / 64.0);
    if (f.a < 0.5) return false;
    vec2 c = (cell + 0.25 + f.rg * 0.5) * FERN_CELL;
    float L = 0.8 + 0.7 * f.b;
    vec2 q = p.xz - c;
    float r = length(q);
    if (r > L + 0.3) return false;
    float base = texture(uHeight, c / 256.0).r * HMAX + texture(uDetail, c / 256.0).r * DMAX - 0.04;
    float H = 0.45 + 0.35 * f.b;
    float y = p.y - base;
    if (y < -0.1 || y > H + 0.25) return false;
    near = 1.0;                                    // inside the fern's bounds: march finely here
    if (r > L) return false;
    const float N = 9.0;
    float sector = 6.2831853 / N;
    float ang = atan(q.y, q.x) + (f.a - 0.5) * 12.566;
    float k = floor(ang / sector + 0.5);
    float kk = mod(k, N);
    float jit = (hash(vec2(kk, f.r * 97.0 + f.g * 31.0)) - 0.5) * 0.45;
    float da = ang - (k + jit) * sector;
    float lat = r * sin(da);
    float lenK = 0.70 + 0.30 * hash(vec2(kk + 3.0, f.g * 53.0));
    float s = r / (L * lenK);
    if (s > 1.0) return false;
    float al = abs(lat);
    float w = 0.03 + 0.17 * sin(3.14159 * min(s * 1.1, 1.0));
    if (al > w) return false;
    float pin = fract(s * L * lenK * 10.0);
    if (al > 0.025 && pin < 0.28) return false;    // gaps between the leaflets
    float spine = H * (3.0 * s - 2.6 * s * s) * (0.85 + 0.3 * hash(vec2(kk, 9.0)));
    float ly = spine - al * 0.7;                   // leaflets droop to the sides
    if (abs(y - ly) > 0.035 + 0.025 * s) return false;
    float leaf = hash(vec2(floor(s * L * lenK * 10.0), kk + f.r * 17.0));
    col = vec3(0.19, 0.39, 0.11) * (0.70 + 0.45 * s) * (0.85 + 0.30 * leaf);
    if (al < 0.022) col = vec3(0.30, 0.34, 0.13) * (0.75 + 0.40 * s);   // midrib
    col *= 0.65 + 0.35 * clamp(y / H, 0.0, 1.0);
    return true;
}

// ---- per-pixel surface texture: albedo multiplier (rgb) and a bump (xz) for each material
vec3 microAlbedo(vec2 wp, float mid, out vec2 bump) {
    vec3 m = vec3(1.0);
    bump = vec2(0.0);
    if (mid < 0.5 || mid > 4.5) {                  // grass and leafy plants
        float b1 = vnoise(wp * vec2(34.0, 30.0)), b2 = hash(floor(wp * 48.0)), b3 = vnoise(wp * 7.0);
        float l = 0.80 + 0.30 * (b1 - 0.5) * 2.0 * 0.6 + 0.20 * (b2 - 0.5) + 0.10 * (b3 - 0.5);
        m = vec3(l * (1.0 + 0.14 * (b2 - 0.5)), l, l * (1.0 - 0.18 * (b2 - 0.5)));
        bump = (vec2(vnoise(wp * 30.0 + 3.1), vnoise(wp * 30.0 + 7.7)) - 0.5) * 1.1;
    } else if (mid < 1.5) {                        // earth: grain and tiny grit
        float n1 = vnoise(wp * 22.0), sp = hash(floor(wp * 40.0));
        float l = 0.88 + 0.22 * (n1 - 0.5) + (sp > 0.93 ? 0.22 : (sp < 0.05 ? -0.22 : 0.0));
        m = vec3(l);
        bump = (vec2(vnoise(wp * 25.0 + 1.3), vnoise(wp * 25.0 + 5.9)) - 0.5) * 0.7;
    } else if (mid < 2.5) {                        // stone: fine grain and speckle
        float n1 = vnoise(wp * 40.0), sp = hash(floor(wp * 70.0));
        float l = 0.90 + 0.16 * (n1 - 0.5) + 0.10 * (sp - 0.5);
        m = vec3(l * 0.99, l, l * 1.02);
        bump = (vec2(vnoise(wp * 36.0 + 2.2), vnoise(wp * 36.0 + 8.8)) - 0.5) * 0.4;
    } else if (mid < 3.5) {                        // wood: grain
        float g1 = vnoise(wp * vec2(10.0, 70.0)) * 0.5 + vnoise(wp * vec2(70.0, 10.0)) * 0.5;
        float l = 0.80 + 0.30 * (g1 - 0.5) * 2.0 * 0.6;
        m = vec3(l);
        bump = (vec2(vnoise(wp * 20.0), vnoise(wp * 20.0 + 4.0)) - 0.5) * 0.5;
    }
    return m;
}

// ---- per-pixel normal mapping: a tiny height field (metres) for each material
float microHeight(vec2 wp, float mid) {
    if (mid < 0.5) {                               // grass: streaky blades leaning with a slow swirl
        float a = vnoise(wp * 0.7) * 6.2831;
        vec2 r = vec2(cos(a) * wp.x + sin(a) * wp.y, -sin(a) * wp.x + cos(a) * wp.y);
        return 0.012 * vnoise(r * vec2(70.0, 12.0)) + 0.008 * vnoise(wp * 35.0);
    } else if (mid < 1.5) {                        // earth: grains and little pebbles
        vec2 c = floor(wp * 22.0);
        vec2 f = fract(wp * 22.0) - 0.5;
        float peb = step(0.80, hash(c)) * max(0.0, 0.16 - dot(f, f)) * 0.08;
        return 0.006 * vnoise(wp * 28.0) + 0.003 * vnoise(wp * 90.0) + peb;
    } else if (mid < 2.5) {                        // stone: pitting and hairline cracks
        float crack = smoothstep(0.03, 0.0, abs(vnoise(wp * 6.0) - 0.5)) * 0.004;
        return 0.0025 * vnoise(wp * 60.0) + 0.0015 * vnoise(wp * 160.0) - crack;
    } else if (mid < 3.5) {                        // wood: grain ridges
        return 0.003 * sin(wp.x * 90.0 + vnoise(wp * 8.0) * 6.0) + 0.002 * vnoise(wp * 50.0);
    } else if (mid < 4.5) {                        // water: wind ripples
        return 0.006 * vnoise(wp * 7.0 + uTime * vec2(0.6, 0.3)) + 0.003 * vnoise(wp * 19.0 - uTime * vec2(0.4, 0.7));
    }
    return 0.010 * vnoise(wp * 40.0) + 0.006 * vnoise(wp * 13.0);   // leafy plants
}

// ---- spline trees from the Tree Builder: tapered branch segments and textured leaves, traced
//      per pixel through each species' 3D zone grid (no meshes)
const float TC = 16.0;          // world tree cell size; every tree fits inside its own cell
const float TREE_FAR = 260.0;
const float PIX = 0.0085;       // radians per low-res pixel (for leaf texture detail level)

vec4 tfetch(sampler2D s, int i) { return texelFetch(s, ivec2(i % 1024, i / 1024), 0); }

float capsuleHit(vec3 ro0, vec3 rd, vec3 pa, vec3 pb, float ra) {
    // start the test just in front of the segment: keeps the maths precise for thin, distant branches
    float ts = max(0.0, dot(0.5 * (pa + pb) - ro0, rd) - (0.5 * length(pb - pa) + ra + 0.05));
    vec3 ro = ro0 + rd * ts;
    vec3 ba = pb - pa, oa = ro - pa;
    float baba = dot(ba, ba), bard = dot(ba, rd), baoa = dot(ba, oa), rdoa = dot(rd, oa), oaoa = dot(oa, oa);
    float a = baba - bard * bard, b = baba * rdoa - baoa * bard, c = baba * oaoa - baoa * baoa - ra * ra * baba;
    float h = b * b - a * c;
    if (h >= 0.0) {
        float t = (-b - sqrt(h)) / a;
        float y = baoa + t * bard;
        if (y > 0.0 && y < baba) return t + ts;
        vec3 oc = (y <= 0.0) ? oa : ro - pb;
        b = dot(rd, oc);
        c = dot(oc, oc) - ra * ra;
        h = b * b - c;
        if (h > 0.0) return -b - sqrt(h) + ts;
    }
    return -1.0;
}

float leafAspect(float shape) {        // leaf cards (sprigs) are near-square; willow and needles narrower
    if (shape > 3.5 && shape < 4.5) return 0.55;
    if (shape > 4.5 && shape < 5.5) return 0.6;
    return 0.85;
}

// trace one species in its local space (rd unit length); returns local t of the nearest hit or -1
float traceSpecies(int sp, vec3 ro, vec3 rd, float tmax, float tScale, out int hitPrim, out vec2 leafUv) {
    hitPrim = -1;
    leafUv = vec2(0.0);
    vec4 m0 = uTreeMeta[sp * 6], m1 = uTreeMeta[sp * 6 + 1], m2 = uTreeMeta[sp * 6 + 2];
    vec3 bmin = m0.xyz, bmax = m1.xyz;
    vec3 inv = 1.0 / (abs(rd) + 1e-6) * sign(rd + 1e-9);
    vec3 t0s = (bmin - ro) * inv, t1s = (bmax - ro) * inv;
    vec3 tsm = min(t0s, t1s), tbg = max(t0s, t1s);
    float tin = max(max(tsm.x, tsm.y), max(tsm.z, 0.0)), tout = min(min(tbg.x, tbg.y), min(tbg.z, tmax));
    if (tin >= tout) return -1.0;
    ivec3 dims = ivec3(int(m0.w), int(m1.w), int(m2.y));
    vec3 cs = (bmax - bmin) / vec3(dims);
    vec3 p = ro + rd * (tin + 1e-4);
    ivec3 cell = clamp(ivec3(floor((p - bmin) / cs)), ivec3(0), dims - 1);
    ivec3 stp = ivec3(sign(rd));
    vec3 nextB = bmin + (vec3(cell) + max(vec3(stp), 0.0)) * cs;
    vec3 tMax = (nextB - ro) * inv;
    vec3 tDel = cs * abs(inv);
    float best = tout + 1e-3;
    int cellBase = int(m2.x);
    for (int i = 0; i < 72; i++) {
        vec2 ce = texelFetch(uTreeCells, ivec2((cellBase + (cell.z * dims.y + cell.y) * dims.x + cell.x) % 1024,
                                               (cellBase + (cell.z * dims.y + cell.y) * dims.x + cell.x) / 1024), 0).xy;
        int start = int(ce.x), count = min(int(ce.y), 200);
        float cellExit = min(min(tMax.x, tMax.y), tMax.z);
        for (int k = 0; k < 200; k++) {
            if (k >= count) break;
            int pid = int(texelFetch(uTreeIdx, ivec2((start + k) % 1024, (start + k) / 1024), 0).r);
            vec4 A = tfetch(uTreePrims, pid * 3), B = tfetch(uTreePrims, pid * 3 + 1), C = tfetch(uTreePrims, pid * 3 + 2);
            if (C.x < 0.5) {
                float t = capsuleHit(ro, rd, A.xyz, B.xyz, 0.5 * (A.w + B.w));
                if (t > 0.0 && t < best) { best = t; hitPrim = pid; }
            } else {
                vec3 n = vec3(C.y, (C.x < 1.5 ? 1.0 : -1.0) * sqrt(max(0.0, 1.0 - C.y * C.y - C.z * C.z)), C.z);
                float den = dot(rd, n);
                if (abs(den) < 1e-4) continue;
                float t = dot(A.xyz - ro, n) / den;
                if (t <= 0.0 || t >= best) continue;
                vec3 q = ro + rd * t - A.xyz;
                float along = dot(q, B.xyz) / A.w;
                if (along < 0.0 || along > 1.0) continue;
                float asp = leafAspect(B.w);
                float across = dot(q, normalize(cross(n, B.xyz))) / (A.w * asp);
                if (abs(across) > 0.5) continue;
                vec2 uv = vec2((B.w + along) / 8.0, across + 0.5);
                float lod = log2(max(1.0, t * tScale * PIX * 128.0 / A.w));
                if (textureLod(uLeafAtlas, uv, lod).a < 0.5) continue;
                best = t; hitPrim = pid; leafUv = uv;
            }
        }
        if (best <= cellExit) break;
        if (cellExit > tout) break;
        if (tMax.x < tMax.y && tMax.x < tMax.z) { cell.x += stp.x; tMax.x += tDel.x; if (cell.x < 0 || cell.x >= dims.x) break; }
        else if (tMax.y < tMax.z) { cell.y += stp.y; tMax.y += tDel.y; if (cell.y < 0 || cell.y >= dims.y) break; }
        else { cell.z += stp.z; tMax.z += tDel.z; if (cell.z < 0 || cell.z >= dims.z) break; }
    }
    return hitPrim >= 0 ? best : -1.0;
}

float barkTex(float type, float ang, float v) {
    if (type < 0.5) {                               // rough: deep vertical furrows
        float f = vnoise(vec2(ang * 5.0, v * 1.2)) * 0.6 + vnoise(vec2(ang * 13.0, v * 4.0)) * 0.4;
        return 0.55 + 0.6 * smoothstep(0.25, 0.75, f);
    } else if (type < 1.5) {                        // birch: white with dark lenticels and patches
        float mark = step(0.82, hash(floor(vec2(ang * 3.0, v * 7.0)))) * step(0.5, fract(v * 7.0));
        float blot = smoothstep(0.62, 0.7, vnoise(vec2(ang * 2.0, v * 0.8)));
        return mix(1.0, 0.15, max(mark, blot * 0.8));
    } else if (type < 2.5) {                        // pine: plates with dark cracks
        vec2 g = vec2(ang * 2.4, v * 2.2 + floor(ang * 2.4) * 0.37);
        vec2 f = fract(g);
        float crack = step(f.x, 0.08) + step(f.y, 0.1);
        return mix(0.85 + 0.3 * hash(floor(g)), 0.35, min(crack, 1.0));
    }
    return 0.9 + 0.1 * vnoise(vec2(ang * 6.0, v * 3.0));    // smooth
}

// shade a tree hit: albedo and normal in world space
void shadeTree(int sp, int pid, vec2 leafUv, vec3 hl, vec3 rdl, float yaw, out vec3 alb, out vec3 nrm, out float leaf) {
    vec4 A = tfetch(uTreePrims, pid * 3), B = tfetch(uTreePrims, pid * 3 + 1), C = tfetch(uTreePrims, pid * 3 + 2);
    vec4 m2 = uTreeMeta[sp * 6 + 2], ma = uTreeMeta[sp * 6 + 3], mb = uTreeMeta[sp * 6 + 4], mk = uTreeMeta[sp * 6 + 5];
    vec3 nl;
    if (C.x < 0.5) {
        vec3 ba = B.xyz - A.xyz;
        float h = clamp(dot(hl - A.xyz, ba) / dot(ba, ba), 0.0, 1.0);
        vec3 axis = normalize(ba);
        nl = normalize(hl - (A.xyz + ba * h));
        vec3 b1 = normalize(cross(axis, abs(axis.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 b2 = cross(axis, b1);
        float ang = atan(dot(nl, b2), dot(nl, b1));
        float v = mix(C.z, C.w, h);
        // bark texels: 32 around the trunk, 6 cm along it
        float bt = barkTex(m2.z, (floor(ang * 5.093) + 0.5) / 5.093, (floor(v * 16.0) + 0.5) / 16.0);
        alb = mk.rgb * bt;
        if (C.y > 1.5) alb = mix(alb, mk.rgb * 0.8, 0.5);         // young twigs are smoother
        leaf = 0.0;
    } else {
        nl = vec3(C.y, (C.x < 1.5 ? 1.0 : -1.0) * sqrt(max(0.0, 1.0 - C.y * C.y - C.z * C.z)), C.z);
        if (dot(nl, rdl) > 0.0) nl = -nl;                          // two-sided
        vec3 tex = textureLod(uLeafAtlas, leafUv, 0.0).rgb;
        float tint = C.w;
        vec3 base = tint >= 10.0 ? mb.rgb : ma.rgb;
        alb = base * tex * (0.82 + 0.36 * fract(tint));
        leaf = 1.0;
    }
    // back to world space (undo the tree's yaw)
    float c = cos(yaw), s = sin(yaw);
    nrm = vec3(nl.x * c + nl.z * s, nl.y, -nl.x * s + nl.z * c);
}


// ---------------------------------------------------------------------------------------------
// First-person arms, per pixel. Each arm is a solid between a top height (back of hand) and a
// bottom height (palm) over a 544 x 176 grid. Every finger has its own knuckle and curls on its
// own: a sample point is unbent around each finger's knuckle axis and tested against that finger.
const float ARM_S = 0.00125;      // metres per grid unit
const float ARM_X0 = 150.0;       // nearest part of the arm drawn (the rest is behind the camera)
const float ARM_HMAX = 56.0;
const float ARM_FINGERS_U = 400.0; // nothing finger-like before this along the arm

vec2 armH(vec3 q, float row) {
    return textureLod(uArmHgt, vec2((q.x + 0.5) / 544.0, (q.z + 88.0 + row) / 352.0), 0.0).rg * ARM_HMAX;
}

int armId(vec3 q, float row) {      // 0 arm/palm, 1..5 finger (thumb first)
    ivec2 c = ivec2(int(q.x + 0.5), int(q.z + 88.5 + row));
    return int(texelFetch(uArmHgt, c, 0).b * 255.0 / 40.0 + 0.5);
}

bool armSolid(vec3 q, float row) {
    if (q.x < ARM_X0 || q.x > 543.0 || abs(q.z) > 87.0) return false;
    vec2 h = armH(q, row);
    return h.x + h.y > 0.4 && q.y <= h.x && q.y >= -h.y;
}

// Unbend p around finger f of arm i; returns the rest-pose point, th = bend angle there.
vec3 fingerRest(vec3 p, int i, int f, out float th) {
    vec4 F = uFing[i * 5 + f];
    float k = uFingK[i * 5 + f];
    vec2 rel = p.xz - F.xy;
    vec2 perp = vec2(-F.w, F.z);
    float a = dot(rel, F.zw), c = dot(rel, perp), y = p.y;
    th = 0.0;
    if (a > 0.0 && k > 1e-5) {
        vec2 v = vec2(a, y + 1.0 / k);
        th = atan(v.x, v.y);
        a = th / k;
        y = length(v) - 1.0 / k;
    }
    vec2 xz = F.xy + F.zw * a + perp * c;
    return vec3(xz.x, y, xz.y);
}

// which part holds point p: -1 nothing, 0 arm and palm, 1..5 a finger; q = rest-pose point
int armPart(vec3 p, int i, out vec3 q, out float th) {
    float row = float(i) * 176.0;
    th = 0.0;
    q = p;
    if (armSolid(p, row) && armId(p, row) == 0) return 0;
    if (p.x < ARM_FINGERS_U) return -1;
    for (int f = 0; f < 5; f++) {
        float t2;
        vec3 r = fingerRest(p, i, f, t2);
        if (r.x < ARM_FINGERS_U - 20.0 || r.x > 543.0 || abs(r.z) > 87.0) continue;
        if (armId(r, row) == f + 1 && armSolid(r, row)) { q = r; th = t2; return f + 1; }
    }
    return -1;
}

// returns distance in metres along the camera ray (or -1), the part hit, its rest point and bend
float traceArm(int i, vec3 dc, out int part, out vec3 hq, out float hth) {
    mat3 Rt = transpose(uArmRot[i]);
    vec3 ro = Rt * (-uArmPos[i]) / ARM_S;
    vec3 rd = Rt * dc / ARM_S;
    vec3 bmin = vec3(ARM_X0, -130.0, -88.0), bmax = vec3(544.0, 58.0, 88.0);
    vec3 inv = 1.0 / rd;
    vec3 ta = (bmin - ro) * inv, tb = (bmax - ro) * inv;
    vec3 tn = min(ta, tb), tf = max(ta, tb);
    float t0 = max(max(tn.x, tn.y), max(tn.z, 0.02)), t1 = min(min(tf.x, tf.y), tf.z);
    part = -1;
    if (t0 >= t1) return -1.0;
    float dt = (t1 - t0) / 120.0;
    float t = t0;
    vec3 q;
    float th;
    for (int s = 0; s < 120; s++) {
        float tt = t + dt;
        if (armPart(ro + rd * tt, i, q, th) >= 0) {
            float a = t, b = tt;
            for (int j = 0; j < 7; j++) {
                float m = 0.5 * (a + b);
                if (armPart(ro + rd * m, i, q, th) >= 0) b = m; else a = m;
            }
            part = armPart(ro + rd * b, i, hq, hth);
            return b;
        }
        t = tt;
    }
    return -1.0;
}

void shadeArm(int i, int part, vec3 q, float th, float t, out vec3 alb, out vec3 nc) {
    float row = float(i) * 176.0;
    vec2 h = armH(q, row);
    bool back = q.y >= 0.5 * (h.x - h.y);
    vec2 uv = vec2((q.x + 0.5) / 544.0, (q.z + 88.0 + row + (back ? 0.0 : 352.0)) / 704.0);
    // texel footprint of one screen pixel (160 lines) at this distance
    float lod = log2(max(1.0, t * 2.0 * uTanHalf.y / LINES / ARM_S));
    alb = textureLod(uArmCol, uv, lod).rgb;
    vec3 n = textureLod(uArmNrm, uv, max(lod - 0.5, 0.0)).rgb * 2.0 - 1.0;
    if (part > 0) {             // turn the normal with the finger's bend
        vec4 F = uFing[i * 5 + part - 1];
        vec2 perp = vec2(-F.w, F.z);
        float al = dot(n.xz, F.zw), ac = dot(n.xz, perp);
        float c = cos(th), s = sin(th);
        float al2 = al * c + n.y * s;
        n.y = -al * s + n.y * c;
        n.xz = F.zw * al2 + perp * ac;
    }
    nc = normalize(uArmRot[i] * n);
}

// ---------------------------------------------------------------------------------------------
// The sword in the right hand, per pixel: distance-field shapes in sword space
// (x across the edge, y across the flat, z along the blade), all in arm-grid units (1.25 mm).
const float SW_GRIP_R = 13.5;     // grip radius
const float SW_GRIP_H = 62.0;     // half the grip length
const float SW_GUARD_S = 71.0;    // crossguard centre
const float SW_BLADE_S0 = 78.0;   // blade starts
const float SW_BLADE_L = 600.0;   // blade length (75 cm)
const float SW_POMMEL_S = -82.0;

float ndot2(vec2 a, vec2 b) { return a.x * b.x - a.y * b.y; }

float sdRhombus(vec2 p, vec2 b) {
    p = abs(p);
    float h = clamp(ndot2(b - 2.0 * p, b) / dot(b, b), -1.0, 1.0);
    float d = length(p - 0.5 * b * vec2(1.0 - h, 1.0 + h));
    return d * sign(p.x * b.y + p.y * b.x - b.x * b.y);
}

void bladeSize(float s, out float w, out float th) {
    float u = clamp(s / SW_BLADE_L, 0.0, 1.0);
    float tip = clamp((1.0 - u) / 0.15, 0.0, 1.0);
    w = (23.0 - 6.0 * u) * sqrt(tip);
    th = 4.8 * (1.0 - 0.35 * u) * mix(0.55, 1.0, tip);
}

// x = distance, y = material (0 steel blade, 1 iron guard and pommel, 2 leather grip)
vec2 swordSdf(vec3 q) {
    float grip = max(length(q.xy) - SW_GRIP_R - 0.8 * sin(q.z * 0.45), abs(q.z) - SW_GRIP_H);
    vec3 g = q - vec3(0.0, 0.0, SW_GUARD_S);
    g.z -= 0.00045 * g.x * g.x;                                  // tips sweep toward the blade
    vec3 dq = abs(g) - vec3(86.0 - 0.06 * abs(g.z), 9.0, 6.5);
    float guard = length(max(dq, 0.0)) + min(max(dq.x, max(dq.y, dq.z)), 0.0) - 3.0;
    guard = min(guard, length(g * vec3(1.0, 1.0, 1.4)) - 16.0);   // a boss at the centre
    vec3 pp = q - vec3(0.0, 0.0, SW_POMMEL_S);
    float pommel = (length(pp * vec3(1.0, 1.0, 1.35)) - 21.0) / 1.35;
    float s = q.z - SW_BLADE_S0, w, th;
    bladeSize(s, w, th);
    float blade = sdRhombus(q.xy, vec2(max(w, 0.2), max(th, 0.2))) * 0.85;
    blade = max(blade, max(-s, s - SW_BLADE_L));
    vec2 r = vec2(grip, 2.0);
    if (guard < r.x) r = vec2(guard, 1.0);
    if (pommel < r.x) r = vec2(pommel, 1.0);
    if (blade < r.x) r = vec2(blade, 0.0);
    return r;
}

// ray in right-arm grid space (t in metres, as traceArm); returns t or -1, hit in sword space
float traceSword(vec3 ro, vec3 rd, out vec3 hq, out float mat) {
    vec3 F = cross(uSwordB, uSwordE);
    mat3 W = mat3(uSwordE.x, F.x, uSwordB.x, uSwordE.y, F.y, uSwordB.y, uSwordE.z, F.z, uSwordB.z);
    vec3 o = W * (ro - uSwordO), v = W * rd;
    vec3 bmin = vec3(-95.0, -30.0, -110.0), bmax = vec3(95.0, 30.0, SW_BLADE_S0 + SW_BLADE_L + 4.0);
    vec3 inv = 1.0 / v;
    vec3 ta = (bmin - o) * inv, tb = (bmax - o) * inv;
    vec3 tn = min(ta, tb), tf = max(ta, tb);
    float t = max(max(tn.x, tn.y), max(tn.z, 0.02)), t1 = min(min(tf.x, tf.y), tf.z);
    if (t >= t1) return -1.0;
    float vl = length(v);
    for (int i = 0; i < 80; i++) {
        vec3 q = o + v * t;
        vec2 d = swordSdf(q);
        if (d.x < 0.25) { hq = q; mat = d.y; return t; }
        t += max(d.x, 0.2) / vl;
        if (t > t1) break;
    }
    return -1.0;
}

vec3 swordNormal(vec3 q) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.35;
    return normalize(k.xyy * swordSdf(q + k.xyy * e).x + k.yyx * swordSdf(q + k.yyx * e).x
                   + k.yxy * swordSdf(q + k.yxy * e).x + k.xxx * swordSdf(q + k.xxx * e).x);
}

float rockTex(vec3 p) {
    float n = vnoise(vec2(p.x + p.z, p.y) * 2.2) * 0.55 + vnoise(vec2(p.x - p.z, p.y * 3.0) * 5.0) * 0.30
            + hash(floor(vec2(p.x * 7.0 + p.z * 5.0, p.y * 8.0))) * 0.15;
    float strata = 0.92 + 0.08 * step(0.5, fract(p.y * 1.3 + vnoise(p.xz * 0.5) * 0.6));
    return (0.62 + 0.45 * n) * strata;
}

void main() {
    // ---- view ray
    vec2 ndc = vUv * 2.0 - 1.0;
    vec3 d = normalize(vec3(ndc.x * uTanHalf.x, ndc.y * uTanHalf.y, -1.0));
    vec3 dCam = d;
    float cp = cos(uPitch), sp = sin(uPitch);
    d = vec3(d.x, d.y * cp - d.z * sp, d.y * sp + d.z * cp);
    float cy = cos(uYaw), sy = sin(uYaw);
    d = vec3(d.x * cy - d.z * sy, d.y, d.x * sy + d.z * cy);

    // ---- march the layered heightmap: solid if below the floor, or inside the rock over a cave
    vec3 ro = uCam;
    float t = 0.05, prevT = 0.0, hitT = -1.0;
    float isFern = 0.0;
    vec3 fcol = vec3(0.0);
    for (int i = 0; i < 260; i++) {
        vec3 p = ro + d * t;
        vec4 hm = texture(uHeight, p.xz / 256.0) * HMAX;
        float det = 0.0;
        if (t < DFAR) det = texture(uDetail, p.xz / 256.0).r * DMAX * detailFade(t);
        if (t < SURF_FAR) det += surfHeight(p.xz, t);
        float near = 0.0;
        vec3 fc;
        if (t < FERN_FAR && fernAt(p, fc, near)) { hitT = t; isFern = 1.0; fcol = fc; break; }
        if (p.y < hm.r + det || (p.y > hm.g && p.y < hm.b)) { hitT = t; break; }
        if (d.y > 0.0 && p.y > HMAX + DMAX) break;
        if (t > FAR) break;
        prevT = t;
        float st = 0.035 + t * 0.026;
        if (t < 4.0) st = min(st, 0.012 + t * 0.012);       // fine steps near: small texture relief
        if (near > 0.5) st = min(st, 0.015 + t * 0.004);   // thin fronds need fine steps
        t += st;
    }
    float kind = 0.0;                         // 0 ground/top surface, 1 cave ceiling, 2 rock face
    if (hitT > 0.0) {
        float a = prevT, b = hitT;
        for (int k = 0; k < 6; k++) {
            float m = 0.5 * (a + b);
            vec3 p = ro + d * m;
            bool solid;
            if (isFern > 0.5) {
                float nr;
                vec3 fc;
                solid = fernAt(p, fc, nr);
                if (solid) fcol = fc;
            } else {
                vec4 hm = texture(uHeight, p.xz / 256.0) * HMAX;
                float det = texture(uDetail, p.xz / 256.0).r * DMAX * detailFade(m);
                if (m < SURF_FAR) det += surfHeight(p.xz, m);
                solid = p.y < hm.r + det || (p.y > hm.g && p.y < hm.b);
            }
            if (solid) b = m; else a = m;
        }
        hitT = b;
        vec3 pb = ro + d * b;                 // blocked by the underside of the rock over a cave?
        vec4 hb = texture(uHeight, pb.xz / 256.0) * HMAX;
        float detb = texture(uDetail, pb.xz / 256.0).r * DMAX * detailFade(b);
        if (isFern < 0.5 && pb.y >= hb.r + detb && pb.y > hb.g && pb.y < hb.b && (pb.y - hb.g) < (hb.b - pb.y)) kind = 1.0;
    }
    float hit = step(0.0, hitT);
    float T = hit > 0.5 ? hitT : FAR;
    vec3 hp = ro + d * T;
    vec2 wp = hp.xz;

    // ---- surface data (sampled outside the loop so texture LOD stays well defined)
    vec4 gt = texture(uGround, wp / 256.0);
    vec4 hc = texture(uHeight, wp / 256.0);
    float e = 0.5;
    float useTop = step(0.4, (hc.b - hc.g) * HMAX) * step(hc.g * HMAX + 0.3, hp.y);  // on the rock top
    vec2 ex = vec2(e, 0.0), ez = vec2(0.0, e);
    vec4 h1 = texture(uHeight, (wp + ex) / 256.0), h2 = texture(uHeight, (wp - ex) / 256.0);
    vec4 h3 = texture(uHeight, (wp + ez) / 256.0), h4 = texture(uHeight, (wp - ez) / 256.0);
    float gx = mix(h1.r - h2.r, h1.b - h2.b, useTop) * HMAX;
    float gz = mix(h3.r - h4.r, h3.b - h4.b, useTop) * HMAX;
    vec3 nMacro = normalize(vec3(-gx, 2.0 * e, -gz));
    // fine relief normal (0.125 m texels), faded with distance and ignored on the rock-top layer
    float e2 = 0.125;
    vec2 dx2 = vec2(e2, 0.0), dz2 = vec2(0.0, e2);
    float dfx = (texture(uDetail, (wp + dx2) / 256.0).r - texture(uDetail, (wp - dx2) / 256.0).r) * DMAX;
    float dfz = (texture(uDetail, (wp + dz2) / 256.0).r - texture(uDetail, (wp - dz2) / 256.0).r) * DMAX;
    float dk = detailFade(T) * (1.0 - useTop);
    vec3 n = normalize(vec3(-gx / (2.0 * e) - dfx / (2.0 * e2) * dk, 1.0, -gz / (2.0 * e) - dfz / (2.0 * e2) * dk));
    if (kind > 0.5) {                          // underside of the vault: normal from the ceiling layer
        float cx = (h1.g - h2.g) * HMAX, cz = (h3.g - h4.g) * HMAX;
        n = normalize(vec3(cx, -2.0 * e, cz));
    }
    float steep = smoothstep(0.75, 0.45, nMacro.y);          // rock only on big slopes, not on shrubs
    if (kind > 0.5) steep = 1.0;

    // per-pixel material texture and bump
    float mid = floor(texture(uMat, wp / 256.0).r * 255.0 / 40.0 + 0.5);
    vec2 bump;
    // point-sampled look: the fine surface detail is snapped to 3 cm texels like a texture would be
    vec2 wpT = (floor(wp * 32.0) + 0.5) / 32.0;
    vec3 micro = microAlbedo(wpT, mid, bump);
    float mf = smoothstep(30.0, 5.0, T) * (1.0 - useTop * 0.5);
    micro = mix(vec3(1.0), micro, mf);
    // per-pixel normal map: finite differences sized to this pixel's footprint on the ground
    float eps = clamp(T * 0.0085, 0.006, 0.08);
    float m0 = microHeight(wp, mid);
    float msx = (microHeight(wp + vec2(eps, 0.0), mid) - m0) / eps;
    float msz = (microHeight(wp + vec2(0.0, eps), mid) - m0) / eps;
    float mk = mf * (1.0 - steep * 0.7);
    // textured ground (the terrain mask picked grass or cobblestone here): the texture set's own
    // colour and normal map replace the made-up detail
    int layer = mid < 0.5 ? 0 : (mid > 1.5 && mid < 2.5 ? 1 : -1);
    float isGrass = (layer >= 0 ? 1.0 : 0.0) * (1.0 - step(0.5, kind)) * (1.0 - useTop) * (1.0 - steep);
    vec3 grassAlb = vec3(0.0);
    if (isGrass > 0.5) {
        float turned, glod = surfLod(T, layer == 1 ? COBBLE_TILE : GRASS_TILE);
        vec2 guv = surfUV(layer, wp, turned);
        vec3 gc = textureLod(uSurfCol, vec3(guv, float(layer)), glod).rgb;
        vec2 tn = textureLod(uSurfNH, vec3(guv, float(layer)), glod).rg * 2.0 - 1.0;
        // the photo's own colour (the terrain mask picked grass here); tree shadows from the ground alpha
        float lit = clamp(gt.a * 2.0 - 1.0, 0.45, 1.0);
        grassAlb = gc * 0.92 * lit;
        vec2 wxz = turned > 0.5 ? vec2(-tn.y, -tn.x) : vec2(tn.x, -tn.y);
        float nz = sqrt(max(0.0, 1.0 - dot(tn, tn)));
        float gk = 1.0 - steep * 0.7;
        n = normalize(vec3(n.x * nz + wxz.x * gk, n.y * nz, n.z * nz + wxz.y * gk));
        mk = 0.0;                                   // no procedural bumps on top
    }
    if (kind < 0.5) n = normalize(n + vec3(-msx, 0.0, -msz) * mk * 1.6);
    else n = normalize(n + vec3(-msx, 0.0, -msz) * mk * 0.8);
    if (isFern > 0.5) {
        n = normalize(vec3(0.0, 1.0, 0.0) + vec3(hash(floor(wp * 30.0)) - 0.5, 0.0, hash(floor(wp * 30.0) + 3.0) - 0.5) * 0.6);
        steep = 0.0;
    }

    // sky openness: test a point nudged off the surface; if it sits under rock, use that cell's value
    vec3 q = hp + n * 0.35;
    vec4 hq = texture(uHeight, q.xz / 256.0);
    float under = step(q.y, hq.b * HMAX - 0.5);   // below the top of the rock here: cave, wall or cutting
    float open = mix(1.0, hq.a, under * hit);

    float water = (1.0 - clamp(gt.a * 2.0, 0.0, 1.0)) * hit * (1.0 - steep) * (1.0 - isFern);

    // ---- one sky evaluation: the sky above, its mirror image in water below
    vec2 rip = vec2(vnoise(wp * 1.6 + uTime * 0.7), vnoise(wp * 1.6 - uTime * 0.55 + 7.0)) - 0.5;
    rip *= 1.0 + 2.5 * uRain;
    vec3 dd = vec3(d.x, abs(d.y), d.z);
    dd.xz += rip * 0.045 * water;
    dd.y = max(dd.y, 0.0005);
    vec3 sky = skyColor(normalize(dd));

    // ---- lighting: ambient + sun relative to flat ground (the texture already has baked light),
    //      cloud shadows, darkness under rock, and a lantern near the player
    vec2 sOff = uSunDir.xz / max(uSunDir.y, 0.2) * CLOUD_H;
    float shadow = coverage(cloudField(uCloudO + (wp - ro.xz) + sOff));
    float sunRel = clamp(0.78 + 1.15 * (dot(n, uSunDir) - max(uSunDir.y, 0.05)), 0.25, 1.45);
    vec3 light = uAmbient * mix(1.0, sunRel, uSunVis) * (1.0 - 0.42 * shadow * uSunVis);
    light *= mix(0.05, 1.0, open);
    float dark = clamp(1.0 - open * (1.0 - uStars * 0.85), 0.0, 1.0);   // caves, and night outside
    vec3 lantern = vec3(1.0, 0.70, 0.40) * 1.6 / (1.0 + T * T * 0.09) * max(dot(n, -d), 0.25) * dark;
    light += lantern;

    vec3 albedo = isFern > 0.5 ? fcol * (0.85 + 0.3 * hash(floor(wp * 60.0))) : (isGrass > 0.5 ? grassAlb : gt.rgb * micro);
    vec3 hpT = (floor(hp * 24.0) + 0.5) / 24.0;                       // rock texels, 4 cm
    vec3 rock = vec3(0.47, 0.45, 0.42) * rockTex(hpT);
    rock = mix(rock, vec3(0.30, 0.36, 0.20) * rockTex(hpT * 1.7), smoothstep(0.55, 0.85, n.y) * 0.5 * (1.0 - kind));
    albedo = mix(albedo, rock, steep);

    vec3 land = albedo * light * mix(1.0, 0.62, uWet * open);
    // soft specular glints: stone and wet ground catch the sun along the normal-mapped surface
    vec3 hv = normalize(uSunDir - d);
    float specK = (mid > 1.5 && mid < 2.5 ? 0.10 : 0.02) + 0.45 * uWet * open * (1.0 - isFern);
    float spec = pow(max(dot(n, hv), 0.0), mid > 1.5 && mid < 2.5 ? 30.0 : 60.0) * specK;
    land += uSunCol * spec * uSunVis * (1.0 - 0.42 * shadow) * open;
    land += uSkyHor * uAmbient * 0.08 * uWet * open * pow(1.0 - min(-d.y * 4.0, 1.0), 3.0) * (1.0 - steep);
    float fres = 0.25 + 0.75 * pow(1.0 - min(max(-d.y, 0.0), 1.0), 5.0);
    vec3 wcol = mix(gt.rgb * light * 0.8, sky * 0.92 * mix(0.05, 1.0, open), fres);
    vec3 g = mix(land, wcol, water);
    float camOpen = texture(uHeight, ro.xz / 256.0).a;
    float fog = 1.0 - exp(-T * uFog * mix(0.4, 1.0, camOpen));
    vec3 fogc = mix(vec3(0.0), uFogCol, max(open, camOpen));   // fog only goes black when you are in the dark
    vec3 ground = mix(g, fogc, fog);

    vec3 missCol = d.y > 0.0 ? sky : uFogCol;
    vec3 col = mix(missCol, ground, hit);
    col += uFlash * vec3(0.55, 0.60, 0.75) * (0.4 + 0.6 * (1.0 - hit)) * open;

    // ---- trees: walk the 16 m cells the ray crosses (nearest first), trace each cell's tree
    {
        float bestT = -1.0, bestYaw = 0.0, bestScale = 1.0;
        int bestSp = 0, bestPid = -1;
        vec2 bestUv = vec2(0.0);
        vec3 bestHl = vec3(0.0), bestRdl = vec3(0.0);
        float tLimit = min(hit > 0.5 ? T : FAR, TREE_FAR);
        if (uPreview.w >= 0.0) {                     // Tree Builder: a single tree, no world trees
            int sp = int(uPreview.w);
            float yaw = uPreviewYaw, cyw = cos(yaw), syw = sin(yaw);
            vec3 o = ro - uPreview.xyz;
            vec3 rol = vec3(o.x * cyw - o.z * syw, o.y, o.x * syw + o.z * cyw);
            vec3 rdl = vec3(d.x * cyw - d.z * syw, d.y, d.x * syw + d.z * cyw);
            int pid;
            vec2 uv;
            float th = traceSpecies(sp, rol, rdl, tLimit, 1.0, pid, uv);
            if (th > 0.0) { bestT = th; bestSp = sp; bestPid = pid; bestUv = uv; bestHl = rol + rdl * th; bestRdl = rdl; bestYaw = yaw; }
        } else {
            vec2 dir = d.xz;
            vec2 cell = floor(ro.xz / TC);
            vec2 sgn = vec2(dir.x >= 0.0 ? 1.0 : -1.0, dir.y >= 0.0 ? 1.0 : -1.0);
            vec2 inv = 1.0 / max(abs(dir), vec2(1e-5));
            vec2 tMax = ((cell + max(sgn, 0.0)) * TC - ro.xz) * sgn * inv;
            vec2 tDel = TC * inv;
            float tCell = 0.0;
            for (int i = 0; i < 26; i++) {
                if (tCell > tLimit) break;
                vec4 f = texture(uTrees, (mod(cell, 16.0) + 0.5) / 16.0);
                if (f.a > 0.5) {
                    vec2 c = (cell + 0.5) * TC + (f.rg - 0.5) * 3.0;
                    float bv = floor(f.b * 255.0 + 0.5);
                    int sp = int(floor(bv / 25.0));
                    float halfW = uTreeMeta[sp * 6 + 2].w;
                    float sz = (0.85 + (f.a - 0.5) * 0.7);
                    sz = min(sz, 6.3 / max(halfW, 0.1));
                    vec4 hm = texture(uHeight, c / 256.0) * HMAX;
                    float baseY = (hm.b - hm.g > 0.4) ? hm.b : hm.r;
                    float yaw = (bv - float(sp) * 25.0) / 25.0 * 6.2831853;
                    float cyw = cos(yaw), syw = sin(yaw);
                    vec3 o = (ro - vec3(c.x, baseY - 0.1, c.y)) / sz;
                    vec3 rol = vec3(o.x * cyw - o.z * syw, o.y, o.x * syw + o.z * cyw);
                    vec3 rdl = vec3(d.x * cyw - d.z * syw, d.y, d.x * syw + d.z * cyw);
                    int pid;
                    vec2 uv;
                    float th = traceSpecies(sp, rol, rdl, tLimit / sz, sz, pid, uv);
                    if (th > 0.0) {
                        bestT = th * sz; bestSp = sp; bestPid = pid; bestUv = uv;
                        bestHl = rol + rdl * th; bestRdl = rdl; bestYaw = yaw; bestScale = sz;
                        break;
                    }
                }
                if (tMax.x < tMax.y) { tCell = tMax.x; tMax.x += tDel.x; cell.x += sgn.x; }
                else { tCell = tMax.y; tMax.y += tDel.y; cell.y += sgn.y; }
            }
        }
        if (bestT > 0.0) {
            vec3 alb, tn;
            float isLeaf;
            shadeTree(bestSp, bestPid, bestUv, bestHl, bestRdl, bestYaw, alb, tn, isLeaf);
            float sr = clamp(0.78 + 1.15 * (dot(tn, uSunDir) - max(uSunDir.y, 0.05)), 0.25, 1.45);
            vec3 tl = uAmbient * mix(1.0, sr, uSunVis);
            // sunlight glowing through leaves seen from the shaded side
            tl += uSunCol * isLeaf * 0.45 * uSunVis * max(0.0, dot(d, uSunDir)) * step(dot(tn, uSunDir), 0.0);
            tl *= 0.78 + 0.22 * clamp(bestHl.y / max(uTreeMeta[bestSp * 6 + 5].w, 1.0), 0.0, 1.0);   // a little darker low down
            float tdark = clamp(uStars * 0.85, 0.0, 1.0);
            tl += vec3(1.0, 0.70, 0.40) * 1.6 / (1.0 + bestT * bestT * 0.09) * max(dot(tn, -d), 0.25) * tdark;
            vec3 tc = alb * tl;
            tc = mix(tc, uFogCol, 1.0 - exp(-bestT * uFog));
            col = tc + uFlash * vec3(0.55, 0.60, 0.75) * 0.4;
        }
    }

    // ---- first-person arms (in front of everything)
    if (uArmShow > 0.5) {
        vec3 aq0, aq1, aq;
        float ath0, ath1, ath;
        int ap0, ap1, ap;
        float at0 = traceArm(0, dCam, ap0, aq0, ath0);
        float at1 = traceArm(1, dCam, ap1, aq1, ath1);
        int ai = -1;
        float at = 1e9;
        if (at0 > 0.0) { ai = 0; at = at0; ap = ap0; aq = aq0; ath = ath0; }
        if (at1 > 0.0 && at1 < at) { ai = 1; at = at1; ap = ap1; aq = aq1; ath = ath1; }
        // the sword, in the right arm's space
        vec3 sq;
        float smat = 0.0, st = -1.0;
        if (uSwordShow > 0.5) {
            mat3 Rt0 = transpose(uArmRot[0]);
            st = traceSword(Rt0 * (-uArmPos[0]) / ARM_S, Rt0 * dCam / ARM_S, sq, smat);
        }
        float swordOpen = texture(uHeight, uCam.xz / 256.0).a;
        if (st > 0.0 && st < at) {
            ai = -1;
            vec3 F = cross(uSwordB, uSwordE);
            vec3 ns = swordNormal(sq);
            vec3 nc = normalize(uArmRot[0] * (uSwordE * ns.x + F * ns.y + uSwordB * ns.z));
            vec3 nw = vec3(nc.x, nc.y * cp - nc.z * sp, nc.y * sp + nc.z * cp);
            nw = vec3(nw.x * cy - nw.z * sy, nw.y, nw.x * sy + nw.z * cy);
            float sdark = clamp(1.0 - swordOpen * (1.0 - uStars * 0.85), 0.0, 1.0);
            vec3 amb = uAmbient * mix(0.12, 1.0, swordOpen);
            float ndl = max(dot(nw, uSunDir), 0.0) * uSunVis * swordOpen;
            vec3 lamp = vec3(1.0, 0.70, 0.40) * 1.1 * max(dot(nw, -d), 0.2) * sdark;
            vec3 hv = normalize(uSunDir - d);
            float nh = max(dot(nw, hv), 0.0);
            // point-sampled wear: scratches and pits on 2.5 mm texels
            vec2 tq = floor(vec2(sq.x * 0.5 + sq.y, sq.z * 0.5));
            float wear = hash(tq) * 0.5 + hash(floor(tq / 6.0)) * 0.5;
            vec3 sc;
            if (smat < 0.5) {                       // steel: mirror of the sky, fuller, bright bevels
                float w, th;
                bladeSize(sq.z - SW_BLADE_S0, w, th);
                float across = abs(sq.x) / max(w, 0.5);
                float fuller = step(across, 0.20) * step(sq.z - SW_BLADE_S0, SW_BLADE_L * 0.72);
                vec3 env = skyColor(reflect(d, nw)) * mix(0.10, 1.0, swordOpen);
                vec3 steel = vec3(0.60, 0.62, 0.66) * (0.86 + 0.18 * wear);
                sc = steel * (env * 0.75 + amb * 0.35 + uSunCol * ndl * 0.25 + lamp * 0.6);
                sc *= mix(1.0, 0.55, fuller);
                sc += mix(0.0, 0.10, smoothstep(0.80, 0.98, across)) * (amb + lamp);   // honed edge
                sc += uSunCol * pow(nh, 70.0) * 1.6 * uSunVis * swordOpen + lamp * pow(max(dot(nw, -d), 0.0), 30.0) * 0.6;
            } else if (smat < 1.5) {                // blackened iron with a brass rim
                float brass = step(abs(abs(sq.x) - 60.0), 4.0);                 // brass bands near the tips
                vec3 iron = mix(vec3(0.20, 0.19, 0.18), vec3(0.55, 0.42, 0.20), brass) * (0.8 + 0.4 * wear);
                vec3 env = skyColor(reflect(d, nw)) * mix(0.10, 1.0, swordOpen);
                sc = iron * (amb + uSunCol * ndl * 0.6 + lamp) + env * 0.12 + uSunCol * pow(nh, 40.0) * 0.5 * uSunVis * swordOpen;
            } else {                                // leather wrap, spiralling
                float ang = atan(sq.y, sq.x) / 6.2832;
                float band = fract(floor(sq.z * 0.5) / 9.0 + ang);
                vec3 lea = vec3(0.30, 0.17, 0.09) * (0.75 + 0.35 * wear) * mix(0.55, 1.0, step(0.16, band));
                sc = lea * (amb + uSunCol * ndl * 0.7 + lamp) + uSunCol * pow(nh, 18.0) * 0.06 * uSunVis * swordOpen;
            }
            col = sc;
        }
        if (ai >= 0) {
            vec3 alb, nc;
            shadeArm(ai, ap, aq, ath, at, alb, nc);
            // camera -> world, same rotations as the view ray
            vec3 nw = vec3(nc.x, nc.y * cp - nc.z * sp, nc.y * sp + nc.z * cp);
            nw = vec3(nw.x * cy - nw.z * sy, nw.y, nw.x * sy + nw.z * cy);
            float armOpen = texture(uHeight, uCam.xz / 256.0).a;
            float sr = clamp(0.78 + 1.15 * (dot(nw, uSunDir) - max(uSunDir.y, 0.05)), 0.30, 1.35);
            vec3 al = uAmbient * mix(1.0, sr, uSunVis) * mix(0.10, 1.0, armOpen);
            al *= 0.85 + 0.25 * clamp(nw.y * 0.5 + 0.5, 0.0, 1.0);          // sky above, ground below
            float adark = clamp(1.0 - armOpen * (1.0 - uStars * 0.85), 0.0, 1.0);
            vec3 lamp = vec3(1.0, 0.70, 0.40) * 1.1 * max(dot(nw, -d), 0.2) * adark;
            // a small glint on the metal plates and buckles (bright, unsaturated texels)
            vec3 hv = normalize(uSunDir - d);
            float lum = dot(alb, vec3(0.3, 0.59, 0.11));
            float sat = max(alb.r, max(alb.g, alb.b)) - min(alb.r, min(alb.g, alb.b));
            float metal = clamp(1.0 - 5.0 * sat, 0.0, 1.0) * smoothstep(0.18, 0.40, lum);
            float spec = pow(max(dot(nw, hv), 0.0), 40.0) * (0.02 + 0.30 * metal) * uSunVis * armOpen;
            col = alb * (al + lamp) + uSunCol * spec;
        }
    }

    // ---- rain streaks (screen space, slanted by wind); none inside caves
    if (uRain > 0.001) {
        vec2 fc = gl_FragCoord.xy;
        float lane = floor(fc.x + fc.y * 0.18);
        float r1 = hash(vec2(lane, 3.1));
        float yy = fc.y + uTime * (190.0 + 140.0 * r1) + r1 * 977.0;
        float cell = floor(yy / 17.0);
        float drop = step(0.82, fract(yy / 17.0)) * step(1.0 - uRain * 0.09, hash(vec2(lane, cell)));
        col = mix(col, uSkyHor * 0.9 + 0.06, drop * 0.30 * camOpen);
    }

    // ---- 15-bit ordered dither
    col = floor(clamp(col, 0.0, 1.0) * 31.0 + bayer4(gl_FragCoord.xy)) / 31.0;
    fragColor = vec4(col, 1.0);
}
