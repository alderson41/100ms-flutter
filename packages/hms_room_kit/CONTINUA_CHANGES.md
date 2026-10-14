# Maintained Continua native PiP integration

Based on official 100mslive/100ms-flutter revision c0a3b67d0d1a0a40949fade582889051ae5a4ded. RoomKit 1.2.1 and Flutter SDK 1.11.1 versions and original licenses are retained.

RoomKit exposes opt-in host-owned PiP state/entry callbacks and camera lifecycle ownership. Android compact mode renders remote clinician screen-share/video without call controls, preserves existing mute choices, and handles absent preview camera tracks. Default behavior remains available to existing integrations.

The Flutter SDK package retains the existing SurfaceTexture renderer integration, orders EGL/texture release before retained-engine shutdown, and snapshots listeners during room-ended dispatch so callbacks can unsubscribe safely. These sources match the app's qualified maintained copies; no SDK version was upgraded.
