#!/usr/bin/env python3
"""
Sculpt the player character, a woman explorer, as one whole voxel model rigged to a skeleton.

    python3 tools/build_woman.py app/src/main/assets/woman

She is modelled here from smooth distance-field shapes (head and face, a ponytail, neck, torso,
arms and hands, legs) and dressed: an orange T-shirt with a green alien head printed on the chest,
brown cargo trousers with thigh pockets and a belt, laced army boots. The whole figure is then
sampled on ONE grid (7 mm cells) in her bind pose (an A-pose, facing +z, feet on y = 0), so she is a
single model, not separate pieces. Every cell also records how it is skinned: the two bones that
move it and the weight between them (smoothly shared across the joints). The game poses the
skeleton each frame and, per pixel, maps points back into this bind pose (inverse linear-blend
skinning) to trace her.

Output (woman.vox, gzipped; not .gz, which Android's packager renames): header (nx ny nz, cell, min xyz), distance (1 byte / cell, +-4 cm),
colour + material (4 bytes: rgb, material*50; 0 skin, 1 shirt, 2 leather, 3 hair, 4 brass, 5 cargo), skin (4 bytes: bone a, bone b, weight of a, 0).
rig.txt: the 16 bones (name, parent, joint start and end in the bind pose).
"""
import gzip, os, struct, sys
import numpy as np

CELL = 0.007
RANGE = 0.04

# ---- skeleton (bind pose, metres): name, parent, start, end
S = 0.7071
SH = np.array([0.165, 1.405, -0.005])
BONES = [
    ('Hips', -1, (0, 0.96, 0), (0, 1.08, 0)),
    ('Spine', 0, (0, 1.08, 0), (0, 1.29, 0)),
    ('Chest', 1, (0, 1.29, 0), (0, 1.46, 0)),
    ('Head', 2, (0, 1.46, 0), (0, 1.72, 0)),
]
for side, sx in (('Left', 1), ('Right', -1)):
    d = np.array([S * sx, -S, 0.0])
    sh = SH * np.array([sx, 1, 1])
    el = sh + d * 0.27
    wr = el + d * 0.245
    hd = wr + d * 0.17
    BONES += [(side + 'Arm', 2, tuple(sh), tuple(el)), (side + 'ForeArm', len(BONES), tuple(el), tuple(wr)),
              (side + 'Hand', len(BONES) + 1, tuple(wr), tuple(hd))]
for side, sx in (('Left', 1), ('Right', -1)):
    hip = (0.088 * sx, 0.93, 0.0); knee = (0.09 * sx, 0.505, 0.012); ank = (0.09 * sx, 0.085, -0.012)
    toe = (0.09 * sx, 0.03, 0.15)
    BONES += [(side + 'UpLeg', 0, hip, knee), (side + 'Leg', len(BONES), knee, ank), (side + 'Foot', len(BONES) + 1, ank, toe)]
NAMES = [b[0] for b in BONES]
B = {n: i for i, n in enumerate(NAMES)}
PARENT = [b[1] for b in BONES]
START = np.array([b[2] for b in BONES], float)
END = np.array([b[3] for b in BONES], float)


# ---- distance helpers ---------------------------------------------------------------------------
def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1)
    return b + (a - b) * h - k * h * (1 - h)


def smax(a, b, k):
    return -smin(-a, -b, k)


def sstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def ell(p, c, r):
    q = (p - np.array(c)) / np.array(r)
    k0 = np.linalg.norm(q, axis=-1)
    k1 = np.linalg.norm(q / np.array(r), axis=-1)
    return k0 * (k0 - 1) / np.maximum(k1, 1e-9)


def cap(p, a, b, ra, rb):
    a, b = np.array(a, float), np.array(b, float)
    pa, ba = p - a, b - a
    h = np.clip((pa @ ba) / (ba @ ba), 0, 1)
    return np.linalg.norm(pa - ba * h[..., None], axis=-1) - (ra + (rb - ra) * h), h


def box(p, c, half, r=0.0):
    q = np.abs(p - np.array(c)) - np.array(half) + r
    return np.linalg.norm(np.maximum(q, 0), axis=-1) + np.minimum(q.max(-1), 0) - r


def vnoise(p, s):
    q = p / s
    i = np.floor(q).astype(np.int64); f = q - i; f = f * f * (3 - 2 * f)

    def h(x, y, z):
        n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791)
        n = (n ^ (n >> 13)) * 1274126177
        return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0
    o = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (f[..., 0] if dx else 1 - f[..., 0]) * (f[..., 1] if dy else 1 - f[..., 1]) * (f[..., 2] if dz else 1 - f[..., 2])
                o = o + w * h(i[..., 0] + dx, i[..., 1] + dy, i[..., 2] + dz)
    return o


# ---- the sculpt: each shape belongs to a bone and a surface (for colour) -------------------------
SKIN, SHIRT, PANTS, BOOT, HAIR, BELT = range(6)


def shapes(P):
    """Returns a list of (distance, bone, surface)."""
    out = []
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    # head: skull, jaw and cheeks, nose, ears
    head = ell(P, (0, 1.597, 0.0), (0.073, 0.097, 0.087))
    head = smin(head, ell(P, (0, 1.548, 0.024), (0.058, 0.062, 0.068)), 0.025)
    head = smin(head, ell(P, (0, 1.574, 0.086), (0.011, 0.019, 0.014)), 0.010)
    for sx in (1, -1):
        head = smin(head, ell(P, (0.074 * sx, 1.585, -0.002), (0.011, 0.024, 0.017)), 0.006)
        head = smax(head, -ell(P, (0.030 * sx, 1.590, 0.083), (0.016, 0.009, 0.010)), 0.006)   # eye sockets
    out.append((head, B['Head'], SKIN))
    # hair: a cap over the skull, cut back from the face, pulled into a ponytail tied high at the back
    hair = ell(P, (0, 1.607, -0.006), (0.079, 0.103, 0.093))
    face = box(P, (0, 1.50, 0.075), (0.09, 0.145, 0.075), 0.02)
    face = smax(face, 1.648 - y, 0.01)                              # hairline
    hair = smax(hair, -face, 0.012)
    hair = smax(hair, 1.52 - y + 0.06 * sstep(0.0, -0.08, z), 0.01)  # off the neck, longer at the back
    hair = hair + 0.0015 * np.sin(x * 900 + z * 300)                  # combed back
    tail = cap(P, (0, 1.648, -0.088), (0, 1.60, -0.135), 0.024, 0.031)[0]
    tail = smin(tail, cap(P, (0, 1.60, -0.135), (0, 1.50, -0.150), 0.031, 0.027)[0], 0.01)
    tail = smin(tail, cap(P, (0, 1.50, -0.150), (0, 1.405, -0.128), 0.027, 0.008)[0], 0.01)
    tail = tail + 0.002 * np.sin(np.arctan2(x, z + 0.14) * 14)        # strands
    out.append((smin(hair, tail, 0.012), B['Head'], HAIR))
    tie = cap(P, (0, 1.655, -0.082), (0, 1.638, -0.104), 0.020, 0.020)[0]
    out.append((tie, B['Head'], BELT))
    # neck
    out.append((cap(P, (0, 1.43, -0.008), (0, 1.535, 0.0), 0.043, 0.039)[0], B['Head'], SKIN))
    # torso in the shirt: chest with bust, shoulders, waist; the shirt hangs a little loose
    chest = ell(P, (0, 1.31, -0.004), (0.142, 0.13, 0.092))
    chest = smin(chest, cap(P, (-0.15, 1.395, -0.012), (0.15, 1.395, -0.012), 0.056, 0.056)[0], 0.05)
    for sx in (1, -1):
        chest = smin(chest, ell(P, (0.056 * sx, 1.288, 0.062), (0.066, 0.060, 0.050)), 0.03)
    out.append((chest - 0.004, B['Chest'], SHIRT))
    waist = ell(P, (0, 1.14, -0.004), (0.122, 0.135, 0.083))
    out.append((waist - 0.004, B['Spine'], SHIRT))
    # hips in the trousers, belt
    hips = ell(P, (0, 0.975, -0.008), (0.148, 0.115, 0.098))
    out.append((hips, B['Hips'], PANTS))
    belt = smax(ell(P, (0, 1.012, -0.006), (0.137, 0.08, 0.092)) - 0.004, np.abs(y - 1.012) - 0.017, 0.004)
    out.append((belt, B['Hips'], BELT))
    # arms: sleeves to mid upper arm, bare forearms, hands with fingers
    for side, sx in (('Left', 1), ('Right', -1)):
        a, fa, hb = B[side + 'Arm'], B[side + 'ForeArm'], B[side + 'Hand']
        sh, el, wr, hd = START[a], START[fa], START[hb], END[hb]
        upper, hh = cap(P, sh, el, 0.046, 0.037)
        out.append((upper, a, SKIN))
        sleeve = smax(cap(P, sh, el, 0.053, 0.046)[0], np.where(hh > 0.55, 1.0, -1.0) * 0.0 + (hh - 0.55) * 0.27, 0.004)
        out.append((sleeve, a, SHIRT))
        out.append((cap(P, el, wr, 0.036, 0.027)[0], fa, SKIN))
        d = (hd - wr) / np.linalg.norm(hd - wr)
        wdir = np.array([0.0, 0.0, 1.0])                     # across the palm (thumb forward)
        tdir = np.cross(d, wdir)                              # through the palm
        palm = box(np.stack([(P - wr) @ d, (P - wr) @ tdir, (P - wr) @ wdir], -1), (0.050, 0.0, -0.002), (0.046, 0.013, 0.037), 0.011)
        hand = palm
        for k, off in enumerate((-0.026, -0.009, 0.008, 0.024)):
            L = (0.070, 0.080, 0.076, 0.062)[k]
            base = wr + d * 0.092 + wdir * off
            hand = smin(hand, cap(P, base, base + d * L + tdir * 0.008 * sx * 0, 0.0085, 0.0068)[0], 0.006)
        tb = wr + d * 0.025 + wdir * 0.032
        hand = smin(hand, cap(P, tb, tb + d * 0.045 + wdir * 0.040, 0.011, 0.0075)[0], 0.008)
        out.append((hand, hb, SKIN))
    # legs: loose cargo trousers with thigh pockets, tucked into army boots
    for side, sx in (('Left', 1), ('Right', -1)):
        ul, lg, ft = B[side + 'UpLeg'], B[side + 'Leg'], B[side + 'Foot']
        hip, knee, ank = START[ul], START[lg], START[ft]
        thigh = cap(P, hip, knee, 0.082, 0.058)[0] - 0.006
        pocket = box(P, (hip[0] + 0.075 * sx, 0.70, 0.004), (0.014, 0.062, 0.052), 0.008)
        out.append((smin(thigh, pocket, 0.012), ul, PANTS))
        shin = cap(P, knee, ank + np.array([0, 0.19, 0]), 0.058, 0.050)[0] - 0.004
        out.append((shin, lg, PANTS))
        shaft = cap(P, ank + np.array([0, 0.205, 0.005]), ank + np.array([0, 0.03, 0.004]), 0.054, 0.050)[0]
        out.append((shaft, lg, BOOT))
        fx = ank[0]
        foot = box(P, (fx, 0.058, 0.062), (0.050, 0.050, 0.118), 0.035)
        foot = smin(foot, ell(P, (fx, 0.055, 0.15), (0.050, 0.045, 0.060)), 0.02)
        sole = box(P, (fx, 0.014, 0.062), (0.054, 0.014, 0.128), 0.010)
        out.append((smin(smin(foot, sole, 0.006), shaft, 0.02), ft, BOOT))
    return out


def colour(P, surf, d):
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    n = vnoise(P, 0.02) * 0.6 + vnoise(P, 0.006) * 0.4
    col = np.zeros(P.shape[:-1] + (3,)); mat = np.zeros(P.shape[:-1], np.int64)
    # skin, with a face
    skin = np.array([0.80, 0.60, 0.48]) * (0.93 + 0.12 * n[..., None])
    c = skin.copy()
    for sx in (1, -1):
        e = ((x - 0.030 * sx) / 0.011) ** 2 + ((y - 1.589) / 0.0065) ** 2
        c = np.where(((e < 1) & (z > 0.07))[..., None], np.array([0.92, 0.90, 0.88]), c)
        ir = ((x - 0.030 * sx) / 0.0055) ** 2 + ((y - 1.589) / 0.0058) ** 2
        c = np.where(((ir < 1) & (z > 0.07))[..., None], np.array([0.22, 0.32, 0.20]), c)
        pu = ((x - 0.030 * sx) / 0.0025) ** 2 + ((y - 1.589) / 0.0028) ** 2
        c = np.where(((pu < 1) & (z > 0.07))[..., None], np.array([0.03, 0.03, 0.03]), c)
        br = (np.abs(x - 0.031 * sx) < 0.016) & (np.abs(y - 1.606 - 0.004 * (1 - np.abs(x - 0.031 * sx) / 0.016)) < 0.0028) & (z > 0.07)
        c = np.where(br[..., None], np.array([0.30, 0.18, 0.10]), c)
        blush = np.exp(-(((x - 0.045 * sx) / 0.02) ** 2 + ((y - 1.560) / 0.015) ** 2)) * (z > 0.05)
        c = c * (1 - 0.10 * blush[..., None]) + np.array([0.85, 0.45, 0.42]) * 0.10 * blush[..., None]
    lips = (np.abs(x) < 0.021) & (np.abs(y - 1.521) < 0.0055 * (1 - (x / 0.022) ** 2)) & (z > 0.07)
    c = np.where(lips[..., None], np.array([0.72, 0.36, 0.34]), c)
    col = np.where((surf == SKIN)[..., None], c, col)
    # orange T-shirt with a green alien head on the chest
    shirt = np.array([0.93, 0.47, 0.10]) * (0.88 + 0.16 * n[..., None])
    sy = (y - 1.245) / 0.058
    hw = 0.046 * np.where(sy > 0, np.sqrt(np.clip(1 - sy * sy, 0, 1)), np.clip(1 + sy * 0.95, 0, 1) ** 0.8)
    alien = (np.abs(x) < hw) & (np.abs(sy) < 1) & (z > 0.06)
    shirt = np.where(alien[..., None], np.array([0.30, 0.92, 0.42]), shirt)
    for sx in (1, -1):
        ex, ey = x - 0.019 * sx, y - 1.244
        a = 0.45 * sx
        u = ex * np.cos(a) + ey * np.sin(a); v = -ex * np.sin(a) + ey * np.cos(a)
        eye = (u / 0.017) ** 2 + (v / 0.008) ** 2 < 1
        shirt = np.where((eye & alien)[..., None], np.array([0.04, 0.05, 0.05]), shirt)
    hem = (np.abs(y - 1.035) < 0.005)
    shirt = shirt * (1 - 0.18 * hem[..., None])
    col = np.where((surf == SHIRT)[..., None], shirt, col); mat = np.where(surf == SHIRT, 1, mat)
    # brown cargo trousers: seams, pocket flaps
    pants = np.array([0.42, 0.30, 0.17]) * (0.86 + 0.2 * n[..., None])
    seam = (np.abs(np.abs(x) - 0.088 - 0.073) < 0.003) & (y < 0.92)
    flap = (np.abs(y - 0.752) < 0.006) & (np.abs(np.abs(x) - 0.163) < 0.02)
    pants = pants * (1 - 0.22 * (seam | flap)[..., None])
    col = np.where((surf == PANTS)[..., None], pants, col); mat = np.where(surf == PANTS, 5, mat)
    # army boots: dark leather, thick black soles, pale laces up the front
    boot = np.array([0.20, 0.15, 0.10]) * (0.8 + 0.3 * n[..., None])
    boot = np.where((y < 0.03)[..., None], np.array([0.06, 0.055, 0.05]), boot)
    lace = (np.abs(x - np.sign(x) * 0.09) < 0.016) & (z > 0.03) & (y > 0.07) & (y < 0.27) & (np.abs(((y * 70) % 1.0) - 0.5) < 0.18)
    boot = np.where(lace[..., None], np.array([0.42, 0.38, 0.30]), boot)
    col = np.where((surf == BOOT)[..., None], boot, col); mat = np.where(surf == BOOT, 2, mat)
    # hair, a warm dark brown with strands; tie and belt dark; brass buckle
    hair = np.array([0.30, 0.17, 0.08]) * (0.75 + 0.45 * vnoise(P * np.array([4, 1, 4]), 0.004)[..., None])
    col = np.where((surf == HAIR)[..., None], hair, col); mat = np.where(surf == HAIR, 3, mat)
    belt = np.array([0.13, 0.09, 0.06]) * (0.85 + 0.25 * n[..., None])
    buckle = (np.abs(x) < 0.02) & (z > 0.05) & (y > 0.99) & (y < 1.035)
    belt = np.where(buckle[..., None], np.array([0.70, 0.58, 0.30]), belt)
    col = np.where((surf == BELT)[..., None], belt, col)
    mat = np.where(surf == BELT, np.where(buckle, 4, 2), mat)
    return col, mat


def skin_weights(P, bone):
    """Two bones per cell and the weight of the first, shared smoothly across each joint."""
    b0 = bone.copy(); b1 = bone.copy(); w = np.ones(bone.shape)
    for b in range(len(BONES)):
        sel = bone == b
        if not sel.any(): continue
        p = P[sel]
        ax = END[b] - START[b]; L = np.linalg.norm(ax); ax = ax / L
        t = (p - START[b]) @ ax                          # along this bone from its joint
        par = PARENT[b]
        wb = np.ones(len(p)); other = np.full(len(p), b)
        if par >= 0 and NAMES[b] not in ('Head',):
            near = t < 0.07
            ws = 0.5 + 0.5 * sstep(0.0, 0.07, t)
            other = np.where(near, par, other); wb = np.where(near, ws, wb)
        for c in range(len(BONES)):
            if PARENT[c] != b: continue
            axc = END[c] - START[c]; axc = axc / np.linalg.norm(axc)
            tc = (p - START[c]) @ axc
            dist = np.linalg.norm((p - START[c]) - np.outer(tc, axc), axis=1)
            near = (tc > -0.07) & (dist < 0.15) & (wb >= 0.999)
            wc = 0.5 * sstep(-0.07, 0.0, tc)
            other = np.where(near, c, other); wb = np.where(near, 1 - wc, wb)
        b1[sel] = other; w[sel] = wb
    return b0, b1, w


def main(out):
    os.makedirs(out, exist_ok=True)
    lo = np.array([-0.62, -0.01, -0.22]); hi = np.array([0.62, 1.76, 0.26])
    dims = np.ceil((hi - lo) / CELL).astype(int) + 1
    nx, ny, nz = dims
    print('grid %d x %d x %d = %.2f M cells' % (nx, ny, nz, nx * ny * nz / 1e6))
    D = np.zeros((nz, ny, nx), np.uint8); C = np.zeros((nz, ny, nx, 4), np.uint8); W = np.zeros((nz, ny, nx, 4), np.uint8)
    xs = lo[0] + np.arange(nx) * CELL; ys = lo[1] + np.arange(ny) * CELL
    for k in range(nz):
        zz = lo[2] + k * CELL
        X, Y = np.meshgrid(xs, ys)
        P = np.stack([X, Y, np.full(X.shape, zz)], -1)
        sh = shapes(P)
        ds = np.stack([s[0] for s in sh], 0)
        i = ds.argmin(0)
        dist = ds[0]
        for s in sh[1:]: dist = smin(dist, s[0], 0.006)
        bone = np.array([s[1] for s in sh])[i]; surf = np.array([s[2] for s in sh])[i]
        col, mat = colour(P, surf, dist)
        b0, b1, w = skin_weights(P, bone)
        D[k] = np.clip(np.round((np.clip(dist, -RANGE, RANGE) + RANGE) / (2 * RANGE) * 255), 0, 255)
        C[k, ..., :3] = np.clip(np.round(col * 255), 0, 255); C[k, ..., 3] = mat * 50
        W[k, ..., 0] = b0; W[k, ..., 1] = b1; W[k, ..., 2] = np.round(w * 255)
    with gzip.open(os.path.join(out, 'woman.vox'), 'wb', 9) as f:
        f.write(struct.pack('<iiiffff', nx, ny, nz, CELL, *lo))
        f.write(D.tobytes()); f.write(C.tobytes()); f.write(W.tobytes())
    with open(os.path.join(out, 'rig.txt'), 'w') as f:
        f.write('# bone parent start(x y z) end(x y z), bind pose, metres\n')
        for i, (n, p, s, e) in enumerate(BONES):
            f.write('%s %d %.4f %.4f %.4f %.4f %.4f %.4f\n' % (n, p, *s, *e))
    print('wrote', out, os.path.getsize(os.path.join(out, 'woman.vox')) // 1024, 'KB')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/woman')
