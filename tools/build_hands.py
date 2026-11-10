#!/usr/bin/env python3
"""
Sculpt the first-person hand and forearm and bake them into voxels the game traces per pixel.

    python3 tools/build_hands.py app/src/main/assets/hands

The hand is sculpted here from distance-field shapes (a padded palm with thenar and hypothenar pads,
knuckles, tendons and veins on the back, palm lines, jointed fingers with pads, joint creases and
wrinkles, fingernails with cuticles; a leather bracer with straps, buckles and stitching; a wound
linen wrist wrap), then sampled on a fine grid (1.5 arm units = 1.9 mm). It is not drawn as cubes:
the grid holds signed distances that the shader blends trilinearly and sphere-traces per pixel.

Everything is in the game's arm-grid units (1.25 mm): x along the arm toward the fingertips, y out
of the back of the hand, z across it (thumb toward -z on the right hand). The fingers lie straight,
from the knuckle pivots in ArmFingers.java; the shader curls them by unbending each sample point
around its finger's knuckle before looking it up. So every finger has its own distance channel:
  hand_a: R palm and forearm, G thumb, B index, A middle        (signed distance, +-16 units)
  hand_b: R ring, G little
  hand_c: RGB colour, A material (0 skin, 1 leather, 2 linen, 3 nail, 4 iron)
Output: hands.bin.gz = header (nx, ny, nz, cell, x0, y0, z0) then hand_a, hand_b, hand_c.
The left hand is the same data mirrored.
"""
import gzip, os, struct, sys
import numpy as np

CELL = 1.5
X0, X1 = 140.0, 556.0
Y0, Y1 = -48.0, 48.0
Z0, Z1 = -96.0, 96.0
RANGE = 16.0

# right hand, from ArmFingers.java: knuckle pivot (x, z), direction (x, z), length
FING = [
    ((427.59, -54.84), (0.7899, -0.6132), 41.0),
    ((463.02, -27.26), (0.9874, -0.1584), 67.8),
    ((471.42, -0.32), (0.9969, 0.0785), 67.8),
    ((465.61, 23.75), (0.9649, 0.2626), 65.7),
    ((450.10, 42.44), (0.8323, 0.5543), 55.1),
]
RADIUS = [9.6, 8.3, 8.6, 8.1, 6.9]       # at the knuckle; fingers taper toward the tip


def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1)
    return b + (a - b) * h - k * h * (1 - h)


def smax(a, b, k):
    return -smin(-a, -b, k)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def ellipsoid(p, c, r):
    q = (p - c) / r
    k0 = np.linalg.norm(q, axis=-1)
    k1 = np.linalg.norm(q / r, axis=-1)
    return k0 * (k0 - 1) / np.maximum(k1, 1e-6)


def cone(p, a, b, ra, rb, scale=(1, 1, 1)):
    """Tapered capsule from a to b (radius ra -> rb); returns (distance, h along it)."""
    p = p * np.array(scale)
    a = np.array(a) * np.array(scale)
    b = np.array(b) * np.array(scale)
    pa, ba = p - a, b - a
    h = np.clip((pa @ ba) / (ba @ ba), 0, 1)
    d = np.linalg.norm(pa - ba * h[..., None], axis=-1) - (ra + (rb - ra) * h)
    return d / max(scale), h


def vnoise3(p, s):
    """Smooth value noise (for skin and leather variation)."""
    q = p / s
    i = np.floor(q).astype(np.int64)
    f = q - i
    f = f * f * (3 - 2 * f)

    def h(x, y, z):
        n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791)
        n = (n ^ (n >> 13)) * 1274126177
        return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (f[..., 0] if dx else 1 - f[..., 0]) * (f[..., 1] if dy else 1 - f[..., 1]) * (f[..., 2] if dz else 1 - f[..., 2])
                out = out + w * h(i[..., 0] + dx, i[..., 1] + dy, i[..., 2] + dz)
    return out


def line_dist(px, pz, pts):
    """Distance in the x-z plane from points to a polyline."""
    best = np.full(px.shape, 1e9)
    for (ax, az), (bx, bz) in zip(pts[:-1], pts[1:]):
        dx, dz = bx - ax, bz - az
        t = np.clip(((px - ax) * dx + (pz - az) * dz) / (dx * dx + dz * dz), 0, 1)
        best = np.minimum(best, np.hypot(px - ax - dx * t, pz - az - dz * t))
    return best


# ---------------------------------------------------------------------------------------------
def palm_and_arm(P):
    """Palm, back of the hand, thumb root, wrist wrap and bracer. Returns distance and region ids."""
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    # forearm under the bracer (a little flattened)
    arm, ha = cone(P, (130, 0, 0), (352, 0, 0), 41, 33, (1, 1.18, 1))
    # bracer relief: two straps standing proud, a rolled edge at the wrist end, stitched seams
    strap = np.maximum(smoothstep(10, 7, np.abs(x - 205)), smoothstep(10, 7, np.abs(x - 300)))
    roll = smoothstep(6, 1, np.abs(x - 349)) * 1.2
    ang = np.arctan2(y * 1.18, z)
    seam = smoothstep(1.6, 0.4, np.abs(np.abs(z) - 30.5)) * (y > 0) * (strap < 0.5)
    stitch = seam * (np.abs(((x * 0.25) % 1.0) - 0.5) < 0.22) * 0.45
    arm = arm - 1.4 * strap - roll + stitch
    # iron buckles on top of the straps
    for bx in (205.0, 300.0):
        buckle = np.maximum(np.abs(x - bx) - 6.5, np.abs(z) - 8.5)
        buckle = np.maximum(buckle, y * 1.18 - 37.5)
        hole = np.maximum(np.abs(x - bx) - 3.5, np.abs(z) - 5.5)
        buckle = np.maximum(buckle, -hole)
        arm = np.minimum(arm, np.maximum(buckle, 30.0 - y * 1.18))
    # linen wrap, wound in overlapping strips
    wrap, _ = cone(P, (344, 0, 0), (386, -1, 0), 32, 27.5, (1, 1.18, 1))
    wrap = wrap - 0.9 * np.sin(x * 0.55 + ang * 2.0) - 0.5
    # the hand: palm block widening to the knuckles, padded underneath
    t = np.clip((x - 372) / 86, 0, 1)
    zc = 3 + 5 * t
    hw = 25 + 15 * t
    top = 11.5 - 1.5 * t
    bot = 15.0 + 1.0 * t
    yc = (top - bot) / 2
    hh = (top + bot) / 2
    q = np.stack([np.abs(x - 415) - 44, np.abs(y - yc) - hh + 8, np.abs(z - zc) - hw + 8], -1)
    palm = np.linalg.norm(np.maximum(q, 0), axis=-1) + np.minimum(q.max(-1), 0) - 8
    # the knuckle end follows the knuckle line, rounded
    kx = np.interp(z, [-35, -27, 0, 24, 42, 52], [440, 456, 463, 458, 444, 430])
    palm = smax(palm, x - kx, 8)
    # pads: thenar (the thumb's muscle), hypothenar, and the cushions under the knuckles
    palm = smin(palm, ellipsoid(P, np.array([404, -8, -26]), np.array([30, 12, 17])), 7)
    palm = smin(palm, ellipsoid(P, np.array([408, -8, 32]), np.array([34, 10, 12])), 7)
    for f in range(1, 5):
        (px, pz), (dx, dz), L = FING[f]
        palm = smin(palm, ellipsoid(P, np.array([px - 7, -6, pz]), np.array([10, 7, 9])), 5)
    # thumb metacarpal, running out to the thumb's knuckle
    tm, _ = cone(P, (384, -4, -12), (FING[0][0][0], 0, FING[0][0][1]), 13, 10.5)
    palm = smin(palm, tm, 9)
    # knuckles on the back
    for f in range(1, 5):
        (px, pz), _, _ = FING[f]
        palm = smin(palm, np.linalg.norm(P - np.array([px - 3, 2.5, pz]), axis=-1) - 9.6, 5)
    # wrist into the hand
    wr, _ = cone(P, (362, 0, 0), (392, 0, 3), 26, 25, (1, 1.18, 1))
    hand = smin(palm, wr, 10)
    # back of the hand: tendons fanning to the knuckles, and veins
    back = smoothstep(4, 9, y) * smoothstep(372, 395, x) * smoothstep(462, 445, x)
    ten = 0
    for f in range(1, 5):
        (px, pz), _, _ = FING[f]
        d = line_dist(x, z, [(380, 2 + f * 2.5), (px - 4, pz)])
        ten = np.maximum(ten, np.exp(-(d / 2.3) ** 2))
    vein = 0
    for path in ([(372, -8), (395, -14), (418, -6), (440, -16)], [(375, 10), (398, 16), (420, 9), (444, 20)],
                 [(395, -14), (410, 2), (430, 4)]):
        d = line_dist(x, z, path)
        vein = np.maximum(vein, np.exp(-(d / 1.6) ** 2))
    hand = hand - back * (0.7 * ten + 0.55 * vein)
    # palm side: the three big lines
    under = smoothstep(-6, -12, y) * smoothstep(380, 392, x)
    lines = 0
    for path in ([(452, 40), (440, 20), (436, 0), (440, -18)],      # heart line
                 [(446, -30), (432, -10), (425, 10), (418, 30)],    # head line
                 [(440, -32), (418, -28), (398, -20), (384, -8)]):  # life line
        d = line_dist(x, z, path)
        lines = np.maximum(lines, np.exp(-(d / 1.1) ** 2))
    hand = hand + under * lines * 0.9
    dist = np.minimum(np.minimum(arm, wrap), hand)
    region = np.where(arm <= np.minimum(wrap, hand), 1, np.where(wrap <= hand, 2, 0))
    region = np.where((region == 1) & (np.abs(x - 205) < 7) & (y > 29) | (region == 1) & (np.abs(x - 300) < 7) & (y > 29), 4, region)
    return dist, region, dict(back=back, ten=ten, vein=vein, lines=lines * under, strap=strap, stitch=stitch, ang=ang)


def finger(P, f):
    """One straight finger (rest pose) with pads, joint creases, knuckle wrinkles and a nail."""
    (px, pz), (dx, dz), L = FING[f]
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    rx, rz = x - px, z - pz
    a = rx * dx + rz * dz                 # along the finger
    c = -rx * dz + rz * dx                # across it
    r0 = RADIUS[f]
    r1 = r0 * (0.74 if f else 0.80)
    end = L + (4.0 if f else 6.0) - r1
    s = np.clip(a, -12, end)
    u = (s + 12) / (end + 12)
    r = r0 + (r1 - r0) * u
    yy = y * 1.12 - 0.6                   # a little flatter than round
    d = np.sqrt((a - s) ** 2 + c ** 2 + yy ** 2) - r
    # joints: the finger narrows a touch between the phalanges and swells at the knuckles
    j1, j2 = (0.50, 0.78) if f == 0 else (0.45, 0.74)
    for jt in (j1, j2):
        ja = jt * L
        g = np.exp(-((a - ja) / 4.5) ** 2)
        d = d - 0.55 * g * (y > 0)                          # knuckle bump on the back
        d = d + 0.9 * np.exp(-((a - ja) / 1.2) ** 2) * (y < -2) # crease on the palm side
        wr = (np.abs(np.sin((a - ja) * 1.6)) < 0.35) * np.exp(-((a - ja) / 3.0) ** 2) * (y > 2)
        d = d + 0.35 * wr                                   # wrinkles over the joint
    # pads on the palm side of each phalanx
    for lo, hi in ((0.05 if f else 0.0, j1), (j1, j2), (j2, 1.0)):
        mid = (lo + hi) / 2 * L
        d = d - 0.7 * np.exp(-((a - mid) / ((hi - lo) * L * 0.35)) ** 2) * smoothstep(0, -6, y)
    # nail on the back of the last phalanx: a slightly raised plate with a groove round it
    na0, na1 = (j2 + 0.06) * L, L + 2.0
    nw = r1 * 0.72
    inside = (a > na0) & (a < na1) & (np.abs(c) < nw) & (y > 0)
    edge = np.minimum(np.minimum(a - na0, na1 - a), nw - np.abs(c))
    d = d - 0.35 * inside * smoothstep(0.0, 1.2, edge) + 0.35 * inside * (edge < 0.7)
    nail = inside & (y > r * 0.35)
    return d, dict(a=a, c=c, L=L, j=(j1, j2), nail=nail, tip=np.clip((a - j2 * L) / ((1 - j2) * L), 0, 1))


def colours(P, dmin, part, region, pa, fi):
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    n = vnoise3(P, 9.0) * 0.6 + vnoise3(P, 3.0) * 0.4
    fine = vnoise3(P, 1.5)
    col = np.zeros(P.shape[:-1] + (3,))
    mat = np.zeros(P.shape[:-1], np.int64)
    # skin
    skin = np.array([0.66, 0.45, 0.34])
    palmskin = np.array([0.74, 0.52, 0.42])
    sk = skin * (0.92 + 0.14 * n[..., None]) * (0.97 + 0.06 * fine[..., None])
    under = smoothstep(2, -6, y)[..., None]
    sk = sk * (1 - under) + palmskin * (0.92 + 0.12 * n[..., None]) * under
    col[:] = sk
    # back of the hand: veins a little blue, tendons lighter; palm lines darker
    col = col * (1 - 0.18 * pa['vein'][..., None]) + np.array([0.06, 0.06, 0.14]) * pa['vein'][..., None] * pa['back'][..., None]
    col = col * (1 - 0.30 * pa['lines'][..., None])
    # fingers: redder over the knuckles and at the tips, nails
    for f in range(5):
        sel = part == f + 1
        if not sel.any(): continue
        info = fi[f]
        a = info['a'][sel]; L = info['L']
        red = np.zeros(a.shape)
        for jt in info['j']:
            red = np.maximum(red, np.exp(-((a - jt * L) / 4.0) ** 2))
        red = np.maximum(red, info['tip'][sel] * 0.7)
        c = col[sel]
        c = c * (1 - 0.18 * red[:, None]) + np.array([0.72, 0.36, 0.30]) * 0.18 * red[:, None]
        crease = np.zeros(a.shape)
        for jt in info['j']:
            crease = np.maximum(crease, np.exp(-((a - jt * L) / 1.3) ** 2))
        c = c * (1 - 0.35 * crease[:, None] * (y[sel] < 0)[:, None])
        nl = info['nail'][sel]
        free = (a > L - 1.5)
        nailc = np.where(free[:, None], np.array([0.88, 0.84, 0.78]), np.array([0.84, 0.66, 0.62]))
        c = np.where(nl[:, None], nailc * (0.95 + 0.08 * fine[sel][:, None]), c)
        col[sel] = c
        m = mat[sel]; m[nl] = 3; mat[sel] = m
    # knuckles on the back: a little redder
    # leather bracer, linen wrap, iron buckles
    arm = (part == 0) & (region == 1)
    lea = np.array([0.24, 0.14, 0.08]) * (0.75 + 0.4 * n[..., None]) * (0.9 + 0.2 * fine[..., None])
    lea = lea * (1 - 0.35 * pa['strap'][..., None])
    lea = np.where((pa['stitch'] > 0.1)[..., None], np.array([0.46, 0.37, 0.25]), lea)
    col = np.where(arm[..., None], lea, col)
    mat = np.where(arm, 1, mat)
    wrap = (part == 0) & (region == 2)
    lin = np.array([0.62, 0.56, 0.46]) * (0.80 + 0.25 * fine[..., None]) * (0.85 + 0.15 * np.sin(x * 0.55 + pa['ang'] * 2.0))[..., None]
    col = np.where(wrap[..., None], lin, col)
    mat = np.where(wrap, 2, mat)
    iron = (part == 0) & (region == 4)
    col = np.where(iron[..., None], np.array([0.42, 0.41, 0.40]) * (0.85 + 0.3 * fine[..., None]), col)
    mat = np.where(iron, 4, mat)
    return col, mat


def main(out):
    os.makedirs(out, exist_ok=True)
    xs = np.arange(X0, X1 + 1e-6, CELL); ys = np.arange(Y0, Y1 + 1e-6, CELL); zs = np.arange(Z0, Z1 + 1e-6, CELL)
    nx, ny, nz = len(xs), len(ys), len(zs)
    print('grid %d x %d x %d = %.2f M cells' % (nx, ny, nz, nx * ny * nz / 1e6))
    A = np.zeros((nz, ny, nx, 4), np.uint8)
    B = np.zeros((nz, ny, nx, 2), np.uint8)
    C = np.zeros((nz, ny, nx, 4), np.uint8)
    enc = lambda d: np.clip(np.round((np.clip(d, -RANGE, RANGE) + RANGE) / (2 * RANGE) * 255), 0, 255).astype(np.uint8)
    for k in range(nz):                                   # slab by slab (memory)
        Z, Y, X = np.meshgrid([zs[k]], ys, xs, indexing='ij')
        P = np.stack([X, Y, Z], -1)[0]                    # (ny, nx, 3)
        d0, region, pa = palm_and_arm(P)
        ds, fi = [d0], []
        for f in range(5):
            df, info = finger(P, f)
            ds.append(df); fi.append(info)
        D = np.stack(ds, 0)
        part = D.argmin(0)
        col, mat = colours(P, D.min(0), part, region, pa, fi)
        A[k] = np.stack([enc(ds[0]), enc(ds[1]), enc(ds[2]), enc(ds[3])], -1)
        B[k] = np.stack([enc(ds[4]), enc(ds[5])], -1)
        C[k, ..., :3] = np.clip(np.round(col * 255), 0, 255).astype(np.uint8)
        C[k, ..., 3] = (mat * 50).astype(np.uint8)
    with gzip.open(os.path.join(out, 'hands.bin.gz'), 'wb', 9) as f:
        f.write(struct.pack('<iiiffff', nx, ny, nz, CELL, X0, Y0, Z0))
        f.write(A.tobytes()); f.write(B.tobytes()); f.write(C.tobytes())
    print('wrote', os.path.join(out, 'hands.bin.gz'), os.path.getsize(os.path.join(out, 'hands.bin.gz')) // 1024, 'KB')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/hands')
