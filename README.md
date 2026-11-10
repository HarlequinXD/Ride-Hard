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
- **Trees (10 species, per pixel)**: oak, silver birch, Scots pine, spruce, willow, dead tree,
  autumn maple, blossom, ancient yew and poplar. Each is an implicit shape (trunk with root flare
  plus a crown of clustered spheres, tiered cone, ellipsoid, willow dome with hanging curtains, or
  bare branches) with lumpy, gapped foliage computed per pixel; no meshes. Rays walk the 16 m tree
  cells they cross, nearest first, and stop at the terrain. Trees grow in groves (broadleaf or
  pine wood), willows by the river, others scattered; baked shadows; solid trunks.
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
