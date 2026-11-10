# Chronicles of Eryndor: Hollowspire

A first-person fantasy RPG for Android. First milestone: the world around the village of
Hollowspire.

- **Pixel-voxel terrain (a volume, not a heightmap)**: the land is a 3D signed-distance volume
  (512 x 96 x 512 samples, 0.5 m apart, over the 256 m tile and 48 m of height) traced per screen
  pixel: sphere steps through open air, fine steps near the surface, trilinear blending so every
  surface is smooth (no cubes; the grid is only data). That allows real **tunnels, caves and
  overhangs**: Hollow Hill's winding cave and domed chamber, and three ridges raised across open
  country, each with a tunnel bored through under several metres of rock. Sky openness is traced
  through the volume too (a second channel), so tunnels darken away from their portals and the
  lantern takes over; walls, ceilings and tunnel floors are rock, the open-air ground keeps the
  painted ground, texture sets and 2D relief. You walk on the first rock surface under your feet,
  so you can go through a tunnel or over its ridge. The volume is built once (a couple of seconds)
  and cached.
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
- **Physically based rendering**: one shading function for the ground, trees, arms and sword:
  Lambert diffuse plus Cook-Torrance specular (GGX, Smith/Schlick-GGX, Schlick Fresnel) from the
  sun, the sky as hemisphere ambient and as a roughness-blurred reflection (split-sum with an
  analytic environment BRDF), and the lantern as a light at the eye; linear-light maths with a
  soft-shoulder tone map. Roughness per material (grass 0.9, cobbles 0.62, wet ground glossier),
  metals take their colour as F0 (the sword's steel, the iron guard, brass bands, the arm plates
  and buckles), and leaves and grass let light through from behind.
- **Real-time global illumination**: each ground pixel within 45 m casts three short rays over
  the heightmap across its hemisphere (directions dithered per pixel); escaped rays bring sky
  light, rays that hit terrain bring the light bounced off that spot (its colour, lit by sun and
  sky, and how open its sky is). Colour bleeding, ambient occlusion in hollows and caves, and cave
  mouths lighting the tunnel. Trees, arms and sword get sky plus light bounced up off the meadow.
- **Sun shafts and glare**: the scene writes how much open sky each pixel shows into its alpha
  (clouds, hills, the Hollowspire, trees, arms and the sword all block it); the final pass blurs
  that mask toward the sun's place on screen (dithered steps) for light shafts through branches
  and around clouds, and adds a sun halo, a horizontal glint and lens ghosts that dim when the
  sun's disc is hidden.
- **Colour grading**: film-like white balance, S-curve contrast, saturation, split toning (cool
  shadows, warm highlights), black point and vignette, driven by the time of day and weather:
  warm and rich at golden hour, neutral at noon, softer and cooler in grey weather, rain and mist,
  moonlit blue at night. Then back to dithered 15-bit colour.
- **Landscape texture library** (placeholders made to be swapped): 15 tiling sets, each a colour
  map, an OpenGL-style normal map and a height map (the height drives per-pixel relief and the
  parallax self-shadowing): grass, cobblestone, dirt, cave rock, forest floor (leaf litter), gravel,
  sand, snow, moss, pine needles, roots, mud, forest grass, cliff and mossy rock. Steep slopes in
  the open wear the cliff face and tunnels the cave rock, both wrapped from three sides; the
  terrain mask picks the rest (palette in `TerrainMask.java`). To swap one: put your colour,
  normal and height images in `textures/landscape/` as `<name>_col.png`, `<name>_nrm.png`,
  `<name>_hgt.png` and run `tools/build_surface.py <name> <col> <nrm> <hgt> app/src/main/assets/surfaces`
  (or regenerate all placeholders with `tools/make_landscape_textures.py textures/landscape`).
- **Terrain texture mask**: `app/src/main/assets/terrain/terrain_mask.png` (2048 x 2048, one
  pixel per 12.5 cm) is a painted colour map that picks the texture for each patch of ground.
  Colours snap to the nearest palette entry, so soft brushes work:

  | Colour | Hex | Texture |
  |---|---|---|
  | green | `#2EB82E` | grass (texture set) |
  | brown | `#8C5A28` | earth / dirt |
  | grey | `#808080` | cobblestone road (texture set) |
  | dark brown | `#4A2810` | wood |
  | blue | `#1E5AFF` | water |
  | dark green | `#0F5A28` | plants |

  Delete the file to go back to the generated layout; `TerrainMaskExport` writes a fresh one.
- **Ground texture sets** (grass, cobblestone road): tiling colour, normal map and height map,
  one texture-array layer each. `tools/build_surface.py name colour normal height out_dir` makes a
  set seamless (a cheapest-path cut through each border band, following the mortar or low ground,
  so stones don't ghost) and packs normal + height. Grass shows the photo's own colour, lit by its normal
  map, point sampled, with tree shadows on top; two placements, one turned and larger, are mixed
  per pixel by a dithered noise so the tile doesn't repeat visibly. Small detail comes only from
  the textures: each set's height map is traced per pixel (cobbles bulge 6 cm out of the mortar,
  grass clumps 3.5 cm), with real silhouettes, plus self-shadowing like parallax occlusion
  mapping (a short march toward the sun over the height map shades the crevices) and dithered
  ray steps so there's no banding; the map itself no longer paints or raises small flora (flowers,
  shrubs, tall grass clumps, reeds, mushroom rings, ferns) or paving joints; rocks, logs,
  stumps, cairns, curbs and walls remain (`WorldTextures.FLORA` turns the flora back on).
- **Sword, per pixel**: a longsword in the right hand: a diamond-section steel blade with a
  fuller and honed edges that mirror the sky, a blackened-iron crossguard with brass bands, a
  spiral leather grip and a pommel. Each pixel traces its distance-field shapes. The right hand's
  fingers bend around one grip line fitted through the knuckles, so they close on the handle;
  the grip is round, so the blade is turned to show its flat. On PC, B / F sheathes and draws it.
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
- **Look**: PS1 resolution (240 lines, width follows the screen so it fills the whole display),
  nearest-neighbour upscale, 15-bit ordered dither.
- **Controls**: touch pad on the left 45% of the screen to walk, swipe anywhere else to look.

## PC (Windows)

Download `Eryndor-Windows.zip` from the latest release, unzip it and run `Eryndor\Eryndor.exe`.
Java is bundled; no install needed. It needs a graphics driver with OpenGL 3.3 (any PC from the
last ten years).

| | Xbox controller | Keyboard and mouse |
|---|---|---|
| Walk | Left stick | WASD / arrow keys |
| Look | Right stick | Mouse (click the window; Esc frees the mouse) |
| Change the weather | Y | Q |
| Speed up time | X | T |
| Draw / sheathe the sword | B | F |
| Invert look | Click right stick | I |
| Show / hide help | View or Menu | F1 |
| Full screen | | F11 or Alt+Enter |

The world cache, saved tree designs and `eryndor.log` live in `%APPDATA%\ChroniclesOfEryndor`.
The PC version runs the same code as the phone (`desktop/` adds the window, controls, HUD and small
stand-ins for Android's GL classes). The Tree Builder is phone-only for now.
Build it yourself with `gradle -p desktop run`.

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
