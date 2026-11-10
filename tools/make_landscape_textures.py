"""Procedural landscape texture sets (placeholders you can swap for your own).

Writes, for each surface, <name>_col.png, <name>_nrm.png (OpenGL style, green = up) and
<name>_hgt.png at 1024 x 1024 into textures/landscape/. Everything is generated on a torus
(periodic noise, wrapped stamping) so every map tiles seamlessly by construction.
Then run tools/build_surface.py on each set to pack it for the game, or replace the source images
with your own and rebuild.

usage: make_landscape_textures.py out_dir
"""
import sys, os
import numpy as np
from PIL import Image

N = 1024
rng = np.random.default_rng(20261111)


def fbm(n=N, beta=2.0, seed=None, lo=2, hi=None):
    """Periodic fractal noise via spectral synthesis: tiles perfectly. beta = falloff (higher = smoother)."""
    r = np.random.default_rng(seed) if seed is not None else rng
    f = np.fft.fftfreq(n) * n
    fx, fy = np.meshgrid(f, f)
    k = np.sqrt(fx * fx + fy * fy)
    amp = np.where(k > 0, 1.0 / np.maximum(k, 1e-6) ** (beta / 2 + 0.5), 0)
    if lo: amp = np.where(k < lo, 0, amp)
    if hi: amp = np.where(k > hi, 0, amp)
    ph = r.uniform(0, 2 * np.pi, (n, n))
    z = np.real(np.fft.ifft2(amp * np.exp(1j * ph)))
    z -= z.min()
    return z / z.max()


def stamp(h, col, cx, cy, rx, ry, ang, hval, cval, profile=2.0, maxmode=True):
    """Add an ellipse 'pebble' with a rounded profile, wrapping across the edges."""
    r = int(max(rx, ry)) + 2
    ys, xs = np.mgrid[-r:r + 1, -r:r + 1]
    ca, sa = np.cos(ang), np.sin(ang)
    u = (xs * ca + ys * sa) / rx
    v = (-xs * sa + ys * ca) / ry
    d = u * u + v * v
    m = d < 1
    prof = (1 - d[m]) ** (1 / profile)
    yy = (ys[m] + cy) % N
    xx = (xs[m] + cx) % N
    hv = hval * prof
    if maxmode:
        cur = h[yy, xx]
        upd = hv > cur
        h[yy[upd], xx[upd]] = hv[upd]
        if col is not None:
            col[yy[upd], xx[upd]] = cval
    else:
        h[yy, xx] += hv
        if col is not None:
            col[yy, xx] = cval


def normal_from_height(h, strength):
    gx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * 0.5 * strength
    gy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * 0.5 * strength
    # OpenGL style: x = image right, y = image up (rows grow downward, so up = -row)
    n = np.dstack([-gx, gy, np.ones_like(h)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    return ((n * 0.5 + 0.5) * 255).clip(0, 255).astype(np.uint8)


def save(out, name, col, h, strength):
    col = np.clip(col, 0, 1)
    Image.fromarray((col * 255).astype(np.uint8), 'RGB').save(f'{out}/{name}_col.png', optimize=True)
    hn = (h - h.min()) / max(h.max() - h.min(), 1e-6)
    Image.fromarray((hn * 255).astype(np.uint8), 'L').save(f'{out}/{name}_hgt.png', optimize=True)
    Image.fromarray(normal_from_height(hn, strength), 'RGB').save(f'{out}/{name}_nrm.png', optimize=True)
    print(name, 'done')


def tint(base, var, *noises):
    c = np.zeros((N, N, 3))
    c[:] = base
    for amt, nz in zip(var, noises):
        c *= (1 + amt * (nz - 0.5))[..., None]
    return c


def dirt(out):
    big, mid, fine = fbm(beta=2.2, seed=1), fbm(beta=1.6, seed=2), fbm(beta=0.8, seed=3)
    h = 0.35 * big + 0.35 * mid + 0.15 * fine
    col = tint(np.array([0.36, 0.27, 0.19]), [0.35, 0.3, 0.25], big, mid, fine)
    damp = fbm(beta=2.4, seed=4)
    col *= (1 - 0.25 * np.clip((damp - 0.55) * 4, 0, 1))[..., None]          # damp darker patches
    for _ in range(900):                                                       # grit and pebbles
        r = rng.uniform(2, 7)
        g = rng.uniform(0.75, 1.15)
        stamp(h, col, rng.integers(N), rng.integers(N), r, r * rng.uniform(0.6, 1), rng.uniform(0, 3.14),
              0.55 + 0.25 * rng.random(), np.array([0.46, 0.42, 0.37]) * g)
    for _ in range(60):                                                        # twigs
        L = rng.uniform(25, 70)
        stamp(h, col, rng.integers(N), rng.integers(N), L, rng.uniform(1.5, 2.5), rng.uniform(0, 3.14),
              0.5, np.array([0.30, 0.21, 0.13]) * rng.uniform(0.8, 1.2))
    save(out, 'dirt', col, h, 6.0)


def rock(out):
    big, mid, fine = fbm(beta=2.6, seed=11), fbm(beta=1.8, seed=12), fbm(beta=1.0, seed=13)
    strata = (np.sin((np.arange(N)[:, None] / N * 2 * np.pi * 9) + big * 9) * 0.5 + 0.5)
    h = 0.45 * big + 0.25 * mid + 0.12 * fine + 0.10 * strata
    # cracks: low ridges of a second noise
    cr = np.abs(fbm(beta=2.0, seed=14) - 0.5)
    crack = np.clip(1 - cr / 0.025, 0, 1)
    h -= 0.25 * crack
    col = tint(np.array([0.47, 0.45, 0.42]), [0.3, 0.25, 0.2], big, mid, fine)
    col *= (1 - 0.45 * crack)[..., None]
    lich = np.clip((fbm(beta=2.2, seed=15) - 0.62) * 5, 0, 1) * (fine > 0.4)
    col = col * (1 - lich[..., None] * 0.5) + np.array([0.42, 0.45, 0.22]) * lich[..., None] * 0.5   # lichen
    col *= (0.9 + 0.2 * strata)[..., None]
    save(out, 'rock', col, h, 8.0)


def forest(out):
    soil, mid = fbm(beta=2.0, seed=21), fbm(beta=1.2, seed=22)
    h = 0.25 * soil + 0.1 * mid
    col = tint(np.array([0.24, 0.18, 0.12]), [0.3, 0.3], soil, mid)
    leafc = [np.array([0.55, 0.32, 0.12]), np.array([0.62, 0.45, 0.16]), np.array([0.40, 0.22, 0.10]),
             np.array([0.33, 0.30, 0.14]), np.array([0.70, 0.52, 0.22])]
    layer = 0.3
    for i in range(2600):                                                      # leaf litter, stacking up
        layer += 0.00025
        L = rng.uniform(9, 20)
        stamp(h, col, rng.integers(N), rng.integers(N), L, L * rng.uniform(0.45, 0.65), rng.uniform(0, 3.14),
              layer + rng.uniform(0, 0.08), leafc[rng.integers(len(leafc))] * rng.uniform(0.7, 1.1), profile=4.0)
    for _ in range(500):                                                       # pine needles and twigs
        stamp(h, col, rng.integers(N), rng.integers(N), rng.uniform(10, 30), 1.0, rng.uniform(0, 3.14),
              layer + 0.12, np.array([0.45, 0.33, 0.18]) * rng.uniform(0.7, 1.1))
    save(out, 'forest', col, h, 5.0)


def gravel(out):
    h = 0.15 * fbm(beta=1.5, seed=31)
    col = tint(np.array([0.30, 0.28, 0.25]), [0.2], fbm(beta=1.5, seed=32))
    greys = [np.array(c) for c in ([0.55, 0.53, 0.50], [0.45, 0.43, 0.40], [0.62, 0.58, 0.52], [0.40, 0.38, 0.36],
                                    [0.52, 0.46, 0.38])]
    for _ in range(5500):
        r = rng.uniform(5, 13)
        stamp(h, col, rng.integers(N), rng.integers(N), r, r * rng.uniform(0.6, 0.95), rng.uniform(0, 3.14),
              0.5 + 0.5 * rng.random(), greys[rng.integers(len(greys))] * rng.uniform(0.85, 1.15), profile=2.5)
    save(out, 'gravel', col, h, 6.0)


def sand(out):
    big, fine = fbm(beta=2.4, seed=41), fbm(beta=0.6, seed=42)
    y = np.arange(N)[:, None] / N
    x = np.arange(N)[None, :] / N
    rip = np.sin((y * 2 * np.pi * 24) + big * 14 + x * 2 * np.pi * 2) * 0.5 + 0.5      # wind ripples
    h = 0.5 * rip + 0.3 * big + 0.1 * fine
    col = tint(np.array([0.70, 0.62, 0.47]), [0.15, 0.10, 0.12], big, rip, fine)
    for _ in range(400):
        r = rng.uniform(2, 5)
        stamp(h, col, rng.integers(N), rng.integers(N), r, r, 0, 0.9, np.array([0.50, 0.45, 0.38]))
    save(out, 'sand', col, h, 4.0)


def snow(out):
    big, mid, fine = fbm(beta=2.6, seed=51), fbm(beta=1.8, seed=52), fbm(beta=0.7, seed=53)
    h = 0.6 * big + 0.3 * mid + 0.08 * fine
    col = tint(np.array([0.90, 0.92, 0.96]), [0.06, 0.05, 0.08], big, mid, fine)
    sparkle = (fine > 0.985)[..., None]
    col = np.where(sparkle, 1.0, col)
    save(out, 'snow', col, h, 3.0)


def moss(out):
    big, mid, fine = fbm(beta=2.2, seed=61), fbm(beta=1.4, seed=62), fbm(beta=0.5, seed=63)
    h = 0.35 * big + 0.3 * mid + 0.25 * fine
    col = tint(np.array([0.24, 0.36, 0.12]), [0.35, 0.3, 0.3], big, mid, fine)
    for _ in range(3500):                                                      # cushions and sprigs
        r = rng.uniform(3, 9)
        g = rng.uniform(0.8, 1.25)
        stamp(h, col, rng.integers(N), rng.integers(N), r, r * rng.uniform(0.7, 1), rng.uniform(0, 3.14),
              0.5 + 0.4 * rng.random(), np.array([0.28, 0.42, 0.14]) * g, profile=1.5)
    save(out, 'moss', col, h, 4.0)


def needles(out):
    soil = fbm(beta=2.0, seed=71)
    h = 0.2 * soil
    col = tint(np.array([0.22, 0.15, 0.09]), [0.3], soil)
    layer = 0.25
    for _ in range(9000):                                                      # pine needles criss-crossing
        layer += 0.00005
        stamp(h, col, rng.integers(N), rng.integers(N), rng.uniform(12, 26), rng.uniform(0.8, 1.3), rng.uniform(0, 3.14),
              layer + rng.uniform(0, 0.05), np.array([0.50, 0.33, 0.17]) * rng.uniform(0.6, 1.15), profile=3.0)
    for _ in range(70):                                                        # cones
        r = rng.uniform(10, 16)
        stamp(h, col, rng.integers(N), rng.integers(N), r * 1.6, r, rng.uniform(0, 3.14), layer + 0.4,
              np.array([0.36, 0.24, 0.12]) * rng.uniform(0.8, 1.1), profile=1.5)
    save(out, 'needles', col, h, 5.0)


def roots(out):
    soil, mid = fbm(beta=2.0, seed=81), fbm(beta=1.0, seed=82)
    h = 0.25 * soil + 0.1 * mid
    col = tint(np.array([0.26, 0.19, 0.13]), [0.3, 0.25], soil, mid)
    for _ in range(26):                                                        # winding roots
        x, y = rng.uniform(0, N), rng.uniform(0, N)
        ang = rng.uniform(0, 6.28)
        w = rng.uniform(8, 16)
        for k in range(160):
            ang += rng.normal(0, 0.12)
            x += np.cos(ang) * 4; y += np.sin(ang) * 4
            w = max(3, w * 0.993)
            stamp(h, col, int(x) % N, int(y) % N, w, w, 0, 0.75 + 0.2 * (w / 16), np.array([0.40, 0.29, 0.19]) * rng.uniform(0.85, 1.1), profile=1.6)
    for _ in range(1200):                                                      # leaves between
        L = rng.uniform(7, 14)
        stamp(h, col, rng.integers(N), rng.integers(N), L, L * 0.55, rng.uniform(0, 3.14), 0.35,
              np.array([0.45, 0.30, 0.14]) * rng.uniform(0.7, 1.2), profile=4.0)
    save(out, 'roots', col, h, 6.0)


def mud(out):
    big, mid, fine = fbm(beta=2.4, seed=91), fbm(beta=1.4, seed=92), fbm(beta=0.6, seed=93)
    h = 0.4 * big + 0.3 * mid + 0.1 * fine
    col = tint(np.array([0.25, 0.19, 0.13]), [0.3, 0.25, 0.15], big, mid, fine)
    wet = np.clip((0.45 - h) * 6, 0, 1)                                        # puddles in the dips
    col = col * (1 - 0.35 * wet[..., None])
    for _ in range(40):                                                        # footprints and hoof prints
        x, y = rng.integers(N), rng.integers(N)
        stamp(h, None, x, y, 14, 22, rng.uniform(0, 3.14), -0.15, None, maxmode=False)
    save(out, 'mud', col, h, 5.0)


def forestgrass(out):
    base = fbm(beta=2.0, seed=101)
    h = 0.2 * base
    col = tint(np.array([0.17, 0.25, 0.09]), [0.4], base)
    greens = [np.array(c) for c in ([0.22, 0.36, 0.10], [0.30, 0.42, 0.14], [0.18, 0.30, 0.09], [0.36, 0.44, 0.18])]
    for _ in range(14000):                                                     # blades
        stamp(h, col, rng.integers(N), rng.integers(N), rng.uniform(8, 20), rng.uniform(0.8, 1.6), rng.uniform(0, 3.14),
              0.4 + 0.5 * rng.random(), greens[rng.integers(len(greens))] * rng.uniform(0.8, 1.15), profile=2.0)
    for _ in range(700):                                                       # fallen leaves
        L = rng.uniform(7, 13)
        stamp(h, col, rng.integers(N), rng.integers(N), L, L * 0.55, rng.uniform(0, 3.14), 0.95,
              np.array([0.55, 0.36, 0.13]) * rng.uniform(0.7, 1.15), profile=4.0)
    for _ in range(160):                                                       # clover
        cx, cy = rng.integers(N), rng.integers(N)
        for k in range(3):
            a = k * 2.09 + rng.uniform(0, 0.3)
            stamp(h, col, int(cx + np.cos(a) * 6) % N, int(cy + np.sin(a) * 6) % N, 6, 5, a, 1.0,
                  np.array([0.24, 0.42, 0.14]), profile=2.0)
    save(out, 'forestgrass', col, h, 5.0)


def cliff(out):
    y = np.arange(N)[:, None] / N
    warp = fbm(beta=2.6, seed=111)
    strata = np.sin((y + warp * 0.08) * 2 * np.pi * 14) * 0.5 + 0.5           # bedding planes
    ledges = np.floor((y + warp * 0.06) * 14) / 14
    mid, fine = fbm(beta=1.6, seed=112), fbm(beta=0.9, seed=113)
    h = 0.35 * strata + 0.25 * mid + 0.12 * fine + 0.2 * (ledges * 7 % 1)
    vc = np.abs(fbm(beta=1.8, seed=114) - 0.5)
    cracks = np.clip(1 - vc / 0.02, 0, 1)
    h -= 0.3 * cracks
    col = tint(np.array([0.52, 0.48, 0.42]), [0.25, 0.25, 0.15], strata, mid, fine)
    col *= (1 - 0.5 * cracks)[..., None]
    stain = np.clip((fbm(beta=2.4, seed=115) - 0.55) * 3, 0, 1)               # dark water streaks
    col *= (1 - 0.3 * stain * (0.6 + 0.4 * strata))[..., None]
    save(out, 'cliff', col, h, 9.0)


def mossyrock(out):
    big, mid, fine = fbm(beta=2.6, seed=121), fbm(beta=1.8, seed=122), fbm(beta=0.9, seed=123)
    h = 0.5 * big + 0.25 * mid + 0.12 * fine
    col = tint(np.array([0.44, 0.43, 0.40]), [0.3, 0.25, 0.2], big, mid, fine)
    mossm = np.clip((fbm(beta=2.2, seed=124) - 0.45) * 4, 0, 1)
    mcol = tint(np.array([0.24, 0.37, 0.12]), [0.4, 0.3], mid, fine)
    col = col * (1 - mossm[..., None]) + mcol * mossm[..., None]
    h += 0.08 * mossm * fine
    save(out, 'mossyrock', col, h, 7.0)


if __name__ == '__main__':
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for f in (dirt, rock, forest, gravel, sand, snow, moss, needles, roots, mud, forestgrass, cliff, mossyrock):
        f(out)
