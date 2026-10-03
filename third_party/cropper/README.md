# Third-party code

## cropper

Planned: vendor the parameter/state-based cropper from
[ImageToolbox](https://github.com/T8RIN/ImageToolbox) (`lib/cropper`, Apache-2.0) at a pinned
commit, keeping its original namespace and per-file license headers, with a list of local
modifications and attribution, and with its `coil`/`exif`/gesture/resource dependencies replaced
by this project's equivalents.

Status: **not vendored yet.** The editor in `ui/editor` currently uses an in-house pan/zoom/preview
surface built on top of `BackgroundRenderer`. When the vendored cropper lands, replace the gesture
surface only; the parameter model (`Background.crop`/`zoom`/`pan`) stays unchanged.

Apache-2.0 is compatible with this project's GPL-3.0-or-later license provided the Apache-2.0
copyright and license notices are retained and modified files are marked as changed.
