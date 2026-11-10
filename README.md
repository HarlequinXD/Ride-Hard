# Chronicles of Eryndor: Hollowspire

A first-person fantasy RPG for Android. First milestone: the world around the village of
Hollowspire.

- **Pixel-voxel terrain**: every screen pixel marches a ray across a layered heightmap (floor,
  cave ceiling, rock top, sky openness). One texture read per step gives hills, valleys, cliffs,
  overhangs and **caves**. Ground colour comes from a painted photographic-style texture
  (2048 px over 256 m, nearest texels, pixel perfect); steep faces and cave rock use procedural
  stone; hills are shaded by the sun.
- **Fine relief**: a second 2048 x 2048 detail heightmap, texel for texel with the ground colours,
  lifts lumpy hummocks, dense grass, shrubs, ferns, fallen logs, stumps, cairns, molehills,
  boulders, pebbles, grass tufts, flowers, cobbles, curbs, bridge parapets, the
  fountain, the well and the standing stones. It is ray-marched per screen pixel with smooth
  (bilinear) heights, so detail is pixel-perfect columns of colour rather than cubes, and fades out
  past about 70 m. Big stones, rocks and shrubs block movement; grass and pebbles don't.
- **Everything per pixel**: surface texture is computed for each screen pixel at the exact point
  its ray hits (grass blades, soil grain and grit, stone speckle, wood grain, plus per-pixel
  bumps); painted colours are blended smoothly so no texel squares show. Close to the camera
  every grass blade has its own height. **Ferns are 3D**: an implicit shape (fronds that rise,
  arch and droop, leaflets with gaps, air underneath) tested by each pixel's ray; no meshes, no
  voxel grid.
- **Trees (10 species, spline-grown, per pixel)**: oak, silver birch, Scots pine, spruce, willow,
  dead tree, autumn maple, blossom, ancient yew and poplar. Each species is grown from a spec:
  a curved trunk spline with root flare, branches, sub-branches and twigs (tapered capsules that
  bend, droop and curl), and leaf sprigs on the outer twigs. Leaves are cards textured from a
  hand-painted leaf atlas (oval, oak, maple, birch, willow, needles, blossom, heart) with veins
  and two colours. Nothing is a mesh: every pixel's ray walks the 16 m tree cells, steps through
  each species' zone grid and tests the capsules and leaf cards inside. Bark has 4 textures.
  Shadows on the ground are cast from the real branches and leaves.
- **First-person arms, per pixel**: both arms (leather bracers, a steel plate, studded fingerless
  gloves) are built from three images: colour, a normal map and a height map, each showing the
  palm side and the back. The outline is inflated into a solid with round fingers and a flat palm.
  The height map raises the straps, buckles and studs; the normal map lights every pixel. Each
  finger is found in the outline and has its own knuckle, so the fingers curl one by one: a
  relaxed hand that breathes and tightens as you walk. The arms bob with your steps and lag a
  little when you turn. Nothing is a mesh: each pixel's ray steps through the arm's volume.
  Rebuild the arm data with `tools/build_arms.py colour.png normal.png height.png out/`.
- **Tree Builder** (button top-left): pick any of the 10 species and edit it with sliders in six
  tabs (Trunk, Branches, Sub-branches, Twigs, Leaves, Bark): height, bend, flare, branch count,
  angle, droop, twig density, leaf shape, size, count and colours, bark type and colour. The tree
  regrows live in a preview (drag to orbit, pinch to zoom). Save applies it to every tree of that
  species in the world (and keeps it between launches), Reset restores the original, New seed
  regrows with a different random layout, Copy text puts the spec on the clipboard.
- **Per-pixel normal mapping**: every material has a tiny height field (grass blade streaks,
  earth grains and pebbles, stone pitting and hairline cracks, wood grain, water ripples). Each
  pixel derives its normal from it with differences sized to its footprint on the ground, adds the
  terrain slope and the 12.5 cm relief, and is lit by the sun with soft specular on stone and wet
  ground.
- **Loading screen**: title, a real progress bar and the stage being built (shaping the land,
  carving Hollow Hill, painting meadows, planting ferns...). After the first run the world is
  read back from a save, which is much quicker.
- **The world**: a cobbled village with a plaza fountain and a well, a cart track running north to
  a stone bridge over a winding river, meadows with wildflowers, shrubs and mossy boulders, a
  ring of standing stones on a mound, fairy rings, and Hollow Hill to the east with a tunnel
  leading to a domed chamber (with a skylight). Tiles every 256 m.
- **Caves**: daylight fades as you go in, rain stops, and a warm lantern lights the way (it also
  comes on at night). You collide with walls and the ceiling.
- **Sky (Mode 7)**: a drifting cloud plane with moving cloud shadows, snow-capped mountains, the
  Hollowspire tower to the north (its window glows at night), sun, moon and stars. The river and
  fountain reflect it.
- **Day/night**: full 24 h in 8 minutes (x20 with the Time button).
- **Weather**: clear, cloudy, overcast, rain (wet ground), thunderstorm (lightning) and mist.
  Changes on its own; the Weather button cycles it.
- **Look**: GBA resolution (160 lines, width follows the screen so it fills the whole display),
  nearest-neighbour upscale, 15-bit ordered dither.
- **Controls**: touch pad on the left 45% of the screen to walk, swipe anywhere else to look.

## Build

GitHub Actions builds a debug APK on every push and attaches it to the **latest** release
(`RideHard.apk`). It is signed with the fixed key in `app/debug.keystore`, so new builds install
over old ones. Locally: `gradle assembleDebug` (Gradle 8.14, JDK 17, Android SDK 35).

## Layout

| File | What |
| --- | --- |
| `GameRenderer.java` | Player movement, collision, eye height, low-res target, uniforms |
| `SkyState.java` | Time of day, sun, sky colours, weather blending, lightning |
| `WorldTextures.java` | Ground colour, layered heightmap and cloud textures (pure Java) |
| `Cave.java` | Hollow Hill cave layout (tunnel path, chamber) |
| `ControlsView.java` | Loading screen, title card, touch pad, swipe look, HUD buttons |
| `Loading.java` | World-building progress shared with the loading screen |
| `assets/shaders/scene.frag` | Heightmap ray-march, lighting, water, sky, weather, dither |
