# Chronicles of Eryndor: Hollowspire

A first-person fantasy RPG exploration game, for Android and PC. First milestone: the wilds around
Hollowspire.

- **A storybook fantasy world**: rich green meadows with wildflowers, woods of deep green with a
  few trees turning copper and gold, fireflies blinking over the grass at night, and on the
  horizon the leaning black Hollowspire with a wizard's lamp burning at its top. The sky is a deep
  storybook blue, and **two moons** cross it, both lit by the sun so they show phases and both
  visible by day: a great silver moon and a small pale-blue one that moves faster. You can't dig
  the ground.
- **First person, no hands for now.** The third-person voxel woman below is still built and
  loaded; `GameRenderer.THIRD_PERSON = true` brings her view back.
- **The voxel woman (third person, switched off for now)**: sculpted in `tools/build_woman.py` and baked as
  ONE whole voxel model (7 mm cells, signed distance + colour) in her bind pose, rigged to a
  16-bone skeleton: every cell stores the two bones that move it and their weights, shared
  smoothly across each joint. Each frame the skeleton is posed (walking, breathing, an idle shift
  of weight) and the shader carries each sample point back into the bind pose by inverse
  linear-blend skinning, so the one model bends smoothly at the knees, hips, elbows and shoulders.
  She wears an orange T-shirt with a green alien head on the chest, brown cargo trousers with thigh
  pockets and a belt with a brass buckle, laced army boots, and her dark brown hair in a high
  ponytail. Her surfaces have their own detail maps (`tools/build_woman_maps.py`): a height map
  and the normal map made from it for the jersey knit, the cargo twill, pebbled boot leather, skin
  and hair, mapped onto her from three sides and traced with **parallax occlusion** plus
  **parallax self-shadowing** (the relief shades itself away from the sun), with the hollows of
  the weave darkened. The camera sits behind her over the right shoulder, pulls in where rock is
  in the way, and trees between you and her are cut away; she casts a soft shadow.
- **Runs on phones**: on Android the shaders are built in a mobile mode (fewer ray steps, shorter
  view distance, no traced bounce light, fewer sun-shaft samples, lighter parallax), the game
  starts at 180 lines and the resolution follows the frame rate (120 to 240 lines, still filling
  the screen), and it asks for a large heap for the streamed world.
- **Mode 7 terrain**: the land is a procedural heightmap (0.5 m) traced per screen pixel with the
  ground textures, materials and fine relief on top: sphere steps through open air, fine steps near
  the ground. No voxels and no caves.
- **A 32 km procedural world in small chunks**: the land is 32 km across (16 km each way from the
  village) and never repeats. It is generated in small 16 m chunks from world position
  (non-repeating hashed noise, seeds per chunk, trees per 16 m cell), so chunks come out the same in
  any order. The GPU holds a 512 m window of ground textures, heightmap, materials and trees with
  wrap-around addressing; background threads make the chunks you walk toward (nearest first) and
  a dozen small ones are uploaded each frame, so there is no seam and no hitch. Past ~150 m the
  land fades into the painted far tree line. The wilds: rolling lowlands to big high ground
  varying over kilometres, forests with their own floors, bare rock and gravel on steep slopes, a
  river every ~1.4 km (bridged where the north road crosses), boulders, fallen logs, stumps,
  cairns and molehills, and mountains at the edges.
- **Fine relief**: a detail heightmap (8 texels per metre), texel for texel with the ground colours,
  lifts lumpy hummocks, dense grass, shrubs, ferns, fallen logs, stumps, cairns, molehills,
  boulders, pebbles, grass tufts, flowers, cobbles, curbs and bridge parapets. It is ray-marched per screen pixel with smooth
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
- **Physically based rendering**: one shading function for the ground, trees and the player:
  Lambert diffuse plus Cook-Torrance specular (GGX, Smith/Schlick-GGX, Schlick Fresnel) from the
  sun, the sky as hemisphere ambient and as a roughness-blurred reflection (split-sum with an
  analytic environment BRDF), and the lantern as a light at the eye; linear-light maths with a
  soft-shoulder tone map. Roughness per material (grass 0.9, cobbles 0.62, wet ground glossier),
  metals take their colour as F0 (her belt buckle), and leaves and grass let light through from behind.
- **Real-time global illumination**: each ground pixel within 45 m casts three short rays over
  the heightmap across its hemisphere (directions dithered per pixel); escaped rays bring sky
  light, rays that hit terrain bring the light bounced off that spot (its colour, lit by sun and
  sky, and how open its sky is). Colour bleeding, ambient occlusion in hollows and caves, and cave
  mouths lighting the tunnel. Trees and the player get sky plus light bounced up off the meadow.
- **Sun shafts and glare**: the scene writes how much open sky each pixel shows into its alpha
  (clouds, hills, the Hollowspire, trees and the player all block it); the final pass blurs
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
  the open wear the cliff face and tunnels the cave rock, both wrapped from three sides; the world
  generator's material ids pick the rest for now (a better way of placing textures on the voxel
  terrain is coming; the old painted colour-mask map is gone). To swap one: put your colour,
  normal and height images in `textures/landscape/` as `<name>_col.png`, `<name>_nrm.png`,
  `<name>_hgt.png` and run `tools/build_surface.py <name> <col> <nrm> <hgt> app/src/main/assets/surfaces`
  (or regenerate all placeholders with `tools/make_landscape_textures.py textures/landscape`).
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
  growing the trees, raising the land around you); the first 512 m are generated on all cores.
- **The world**: a flat meadow plateau, a cart track running north to a stone bridge over a winding
  river, meadows with wildflowers, shrubs and mossy boulders, and fairy rings, at the heart of the
  32 km wilds.
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
| Invert look | Click right stick | I |
| Show / hide help | View or Menu | F1 |
| Full screen | | F11 or Alt+Enter |

Saved tree designs and `eryndor.log` live in `%APPDATA%\ChroniclesOfEryndor`.
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
| `ControlsView.java` | Loading screen, title card, touch pad, swipe look, HUD buttons |
| `Loading.java` | World-building progress shared with the loading screen |
| `assets/shaders/scene.frag` | Heightmap ray-march, lighting, water, sky, weather, dither |
