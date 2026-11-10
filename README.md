# Ride-Hard

A Western game for Android. First milestone: a first-person test scene.

- **Mode 7 ground**: an old-West desert (main street with wagon ruts and boardwalks, a cross
  street, a railroad, a stone well, sagebrush, rocks and cracked hardpan), ray-cast per pixel from
  one procedurally painted, photographic-style texture (2048 px over 256 m, 12.5 cm per texel,
  tiles seamlessly).
- **Mode 7 sky**: an inverted cloud plane drifting with the wind, mesas on the horizon, sun, moon
  and stars. Clouds cast moving shadows on the ground.
- **Day/night**: a full 24 h cycle in 8 minutes (x20 with the Time button).
- **Weather**: clear, cloudy, overcast, rain (ground gets wet), thunderstorm (lightning) and dust
  storm. Changes on its own every 1 to 3 minutes; the Weather button cycles it.
- **Look**: 240-line low-res buffer, nearest-neighbour upscale, 15-bit ordered dither.
- **Controls**: touch pad on the left 45% of the screen to walk, swipe anywhere else to look.

## Build

GitHub Actions builds a debug APK on every push and attaches it to the **latest** release
(`RideHard.apk`). The APK is signed with the fixed key in `app/debug.keystore`, so new builds
install over old ones.

Locally: `gradle assembleDebug` (Gradle 8.14, JDK 17, Android SDK 35).

## Layout

| File | What |
| --- | --- |
| `GameRenderer.java` | Player movement, low-res render target, uniforms |
| `SkyState.java` | Time of day, sun, sky colours, weather blending, lightning |
| `WorldTextures.java` | Procedural ground and cloud textures (pure Java, cached on disk) |
| `ControlsView.java` | Touch pad, swipe look, HUD buttons |
| `assets/shaders/scene.frag` | Mode 7 ground + sky + clouds + mesas + rain + dither |
