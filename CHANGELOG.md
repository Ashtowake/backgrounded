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
