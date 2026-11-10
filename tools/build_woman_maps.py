#!/usr/bin/env python3
"""
Detail maps for the voxel woman's surfaces: a height map and the normal map made from it, for each
of her materials, tiling seamlessly (512 x 512, packed like the ground's texture sets: *_col.png is
a shading/colour modulation, *_nh.png holds the normal in R, G and the height in B).

    python3 tools/build_woman_maps.py app/src/main/assets/surfaces

  w_shirt   cotton jersey knit: rows of little interlocking loops
  w_cargo   heavy cotton twill: diagonal ribs, with slubs
  w_leather pebbled boot leather with creases
  w_skin    skin: fine pores and faint creases
  w_hair    hair: long strands, clumped
They are mapped onto her in her bind pose from three sides and traced with parallax occlusion
(and self-shadowing) in scene.frag.
"""
import os, sys
import numpy as np
from PIL import Image

N = 512
rng = np.random.default_rng(11)
yy, xx = np.mgrid[0:N, 0:N] / N           # 0..1, tiles


def tile_noise(freq, seed):
    """Smooth periodic value noise with `freq` cells across the tile."""
    r = np.random.default_rng(seed).random((freq, freq))
    fx, fy = xx * freq, yy * freq
    x0, y0 = np.floor(fx).astype(int), np.floor(fy).astype(int)
    tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a = r[y0 % freq, x0 % freq]; b = r[y0 % freq, (x0 + 1) % freq]
    c = r[(y0 + 1) % freq, x0 % freq]; d = r[(y0 + 1) % freq, (x0 + 1) % freq]
    return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty


def fbm(base, seed, oct=4):
    s, amp, tot = 0, 1.0, 0
    for o in range(oct):
        s = s + tile_noise(base * 2 ** o, seed + o) * amp; tot += amp; amp *= 0.5
    return s / tot


def norm01(h):
    lo, hi = np.percentile(h, 0.5), np.percentile(h, 99.5)
    return np.clip((h - lo) / (hi - lo), 0, 1)


def shirt():
    # jersey knit: columns of V-shaped loops (wales), 64 across the tile
    w, c = 64, 48
    u = xx * w; v = yy * c
    fu = u - np.floor(u) - 0.5
    fv = v - np.floor(v)
    lean = np.where(fu < 0, 1, -1) * 0.35
    loop = np.cos((fv + fu * lean * 2) * np.pi * 2) * 0.5 + 0.5
    ridge = 1 - np.abs(fu) * 2
    h = loop * 0.45 + ridge ** 0.7 * 0.55 + (fbm(16, 3) - 0.5) * 0.25
    return norm01(h), 0.92 + 0.10 * norm01(h)


def cargo():
    # twill: diagonal ribs, 40 across, with slubby yarn
    k = 40
    d = (xx + yy) * k
    rib = np.abs(np.sin(d * np.pi))
    slub = fbm(8, 7) * 0.35 + tile_noise(96, 9) * 0.15
    weft = np.abs(np.sin(xx * k * 2 * np.pi)) * 0.15
    h = rib * 0.75 + slub + weft
    return norm01(h), 0.88 + 0.16 * norm01(h)


def leather():
    # pebble grain: cells of a periodic voronoi, raised in the middle, plus creases
    pts = rng.random((180, 2))
    best = np.full((N, N), 9.0); second = np.full((N, N), 9.0)
    for px, py in pts:
        dx = np.abs(xx - px); dx = np.minimum(dx, 1 - dx)
        dy = np.abs(yy - py); dy = np.minimum(dy, 1 - dy)
        dd = np.sqrt(dx * dx + dy * dy)
        second = np.where(dd < best, best, np.minimum(second, dd)); best = np.minimum(best, dd)
    pebble = np.clip((second - best) * 30, 0, 1) ** 0.6
    crease = 1 - np.clip(np.abs(np.sin((yy * 5 + fbm(4, 21) * 0.8) * np.pi * 2)) * 6, 0, 1) * 0.0
    folds = np.clip(1 - np.abs((fbm(3, 23) - 0.5)) * 20, 0, 1)
    h = pebble * 0.7 + fbm(24, 25) * 0.25 - folds * 0.35
    return norm01(h), 0.85 + 0.2 * norm01(h)


def skin():
    pores = tile_noise(160, 31)
    pores = 1 - np.clip((0.22 - pores) * 8, 0, 1)
    creases = np.clip(1 - np.abs(np.sin((xx * 3 + fbm(2, 33) * 1.5) * np.pi * 2 * 6)) * 12, 0, 1) * 0.25
    h = fbm(12, 35) * 0.5 + pores * 0.45 - creases
    return norm01(h), 0.94 + 0.08 * norm01(h)


def hair():
    # strands run along v: thin stripes with wandering phase, gathered into clumps
    wander = (fbm(4, 41) - 0.5) * 0.2
    s1 = np.abs(np.sin((xx + wander) * np.pi * 2 * 90))
    s2 = np.abs(np.sin((xx + wander * 1.3 + 0.13) * np.pi * 2 * 37))
    clump = tile_noise(12, 43)
    h = s1 * 0.45 + s2 * 0.35 + clump * 0.4
    return norm01(h), 0.80 + 0.30 * norm01(h)


def pack(name, h, shade, out):
    # normals from the height (OpenGL convention: green = up the image)
    k = 3.0
    gx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * 0.5 * k
    gy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * 0.5 * k
    n = np.stack([-gx, gy, np.ones_like(h)], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    nh = np.concatenate([(n[..., :2] * 0.5 + 0.5), h[..., None]], -1)
    Image.fromarray(np.clip(np.round(nh * 255), 0, 255).astype(np.uint8), 'RGB').save(os.path.join(out, name + '_nh.png'))
    col = np.clip(np.round(np.repeat(shade[..., None], 3, -1) * 200), 0, 255).astype(np.uint8)
    Image.fromarray(col, 'RGB').save(os.path.join(out, name + '_col.png'))


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/surfaces'
    for name, fn in (('w_shirt', shirt), ('w_cargo', cargo), ('w_leather', leather), ('w_skin', skin), ('w_hair', hair)):
        h, shade = fn()
        pack(name, h, shade, out)
        print('wrote', name)
