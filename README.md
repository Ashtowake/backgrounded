# Backgrounded

Live wallpaper album rotation for Android optimised for high customisation and additional compatibility with GrapheneOS and Fold devices.

Complete the wallpaper setup on first launch. You can also select Backgrounded through
**Wallpaper & style → Live wallpapers → Backgrounded**.

License: GPL-3.0-or-later.

# Features

- Home and lock wallpaper pairs, with separate front and inner display compositions on foldables.
- Image positioning, cropping, rotation, mirroring, independent stretch, and optional color or blur backgrounds.
- Home-screen scrolling and gyroscope parallax.
- Ordered or shuffled rotation, timed intervals, fixed times, and unlock triggers.
- Per-album crossfade duration and continuous slide or zoom animation with adjustable speed.
- Album rotation toggle, pair reordering, and copying pairs between albums.
- One-time folder import and linked folders with configurable discovery intervals.
- Fixed home or lock images selected from the phone.
- Hidden albums with biometric, device credential, or custom PIN authentication.
- Optional source-file moves into private storage and encryption for hidden images.
- Configurable widgets with separate settings and single- or double-tap actions.
- Wallpaper controls widget with an album name, image/album navigation, random selection, and pause/play.
- Floating widget gallery picker for applying specific wallpaper pairs and browsing albums.
- JSON configuration import and export.
- Restore missing images from original files by content hash, keeping pair order and image compositions.
- Debug builds include optional local diagnostics; release builds have no diagnostics logging or network permission.

# Install and updates

Download the signed APK from [Releases](https://github.com/Ashtowake/backgrounded/releases).
For Obtainium, add `https://github.com/Ashtowake/backgrounded` as the app source.
Updates use the same application ID and signing key, with increasing version codes.

Configuration JSON exports do not contain image files. Keep the originals separately.

Uninstalling the app deletes its private image copies, including encrypted images. If originals were moved into
private storage, unhide the albums to restore those originals and verify them before uninstalling.

After importing a configuration, grant its original folders or full access, then use
**Settings → Restore images from folder** or **Scan granted locations** to restore missing private copies.

# Rendering and scheduling

Automatic rotation runs while the wallpaper is visible. When it becomes visible again,
one overdue change is applied; missed intervals are not replayed. Wallpaper previews do not drive rotation.
Static wallpapers do not schedule continuous frames or wakeup alarms.
The interactive editor uses its own renderer and stable working resolution, independently of
the wallpaper and thumbnail memory budget.

Settings provides a 30 FPS animation ceiling by default, with an optional 60 FPS ceiling,
and an app-wide linked-folder scan interval (every rotation, 1 minute, 5 minutes, or 15 minutes).
Folder scans default to 15 minutes when no preference is saved; saved choices are preserved.
Supported provider notifications trigger discovery without waiting for that interval.
Shorter intervals increase battery consumption.

Configuration imports are limited to 32 MiB and validated before replacement. Restore moved originals
and decrypt private encrypted images before replacing their recovery metadata with an imported configuration.
Encrypted image decoding accepts encoded files up to 64 MiB and does not create a plaintext album cache.
Restoration and encryption maintenance stream larger existing files without loading them into a display buffer.

Without an enabled recovery or system-authentication route, encrypted hidden albums require PIN entry
after a process restart. The optional PIN remains an alternative to system authentication.

# Planned Features

- depth wallpapers
- animation (gif/video) support
- wallpaper engine scene support
- Move images between albums
- Back/Forward home screen double tap using screen side or triple tap
- individual image dimming
- create an album selection for the app widget instead of always using all albums
- Location based album selection (e.g., user can name multiple like "work" or "home", etc., and set a trigger like a specific wifi network)
- battery optimisation
- sharable .zip wallpaper packages

# Known Bugs
- incompatibility on some devices where the live wallpaper system prompt is handled differently