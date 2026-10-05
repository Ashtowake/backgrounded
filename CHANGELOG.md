# v0.5.2

- Add a floating widget gallery picker with composed pair and album previews, matching directory-up cards,
  and authenticated hidden-album access.
- Add icon-based image and album controls, including previous/random albums and random images.
- Add the Wallpaper controls home-screen widget with album name and playback buttons.
- Avoid repeat authentication for revealed hidden albums and keep active hidden albums visible.
- Add one-time wallpaper setup, centered playback controls, larger album previews, and vertical activation switches.
- Add landing-page rename/delete actions and a replacement picker after deleting the active album.
- Move image, folder, and pair actions to the album toolbar.
- Standardize landing-page card heights and fill thumbnail strips to available width.
- Adapt album and wallpaper-pair grids to tablet and split-screen window widths.
- Stack pair thumbnails with Lock above Home in landscape.
- Show editor previews side by side on wide windows.
- Follow display rotation for single-screen wallpaper previews.

# v0.5.1

- Recover missing images from configuration imports by matching SHA-256 hashes against originals.
- Scan granted locations or choose an original folder, including its subfolders.
- Preserve image pairs, ordering, and all editing compositions during recovery.
- Report missing images and clarify that configuration exports do not include image files.
- Roll back database changes if importing a malformed configuration fails.

# v0.5.0

- Separate front/inner and home/lock image compositions, mirroring, and editing controls.
- Album-specific crossfade and continuous slide/zoom animation with speed in pixels per second.
- Album rotation toggles, pair ordering, album previews, and copying pairs between albums.
- Folder import and linking, optional source-file hiding, and encrypted private image storage.
- Hidden album authentication using biometrics, device credentials, or an optional custom PIN.
- Independent widget configuration and single/double-tap actions.
- Black editor canvas with transparent rotated image corners.
- Local diagnostics in debug builds only; release builds have no network permission.

## Installation

Install the attached signed APK. In Obtainium, add `https://github.com/Ashtowake/backgrounded`.

This release uses the project's release signing key. A build signed with an earlier debug key
cannot be updated in place with this APK. Export configuration before changing installations.
