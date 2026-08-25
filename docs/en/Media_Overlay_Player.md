# Media overlay player

The terminal supports `OSC 7770 ; <source>` requests that open a floating video/audio player on
top of the launcher. Playback runs through Android's `MediaPlayer` on a dedicated surface, so it
is hardware-decoded and never passes through the pty or the terminal bitmap pipeline — unlike the
Kitty graphics or Sixel paths, which redraw every frame as pixels in the grid.

## Shell usage

```sh
sh recipes/termux/media-overlay/media.sh ~/Videos/clip.mp4
```

or copy the one-liner into your shell profile:

```sh
media() { printf '\033]7770;%s\007' "$1"; }
```

Accepted sources:

- absolute paths (`/storage/emulated/0/Movies/clip.mp4`);
- relative paths, resolved against the current directory first and then Termux home;
- `http://`, `https://`, and `content://` URLs.

Tap the video for transport controls; playback ends when the stream completes or the dialog is
dismissed.

## Why not play videos through Kitty graphics?

The Kitty graphics implementation transmits raw RGBA frames over escape sequences. That works for
animation formats like GIF at modest sizes, but video means software decode plus a full-frame copy
into the terminal per frame; a 640×360 stream is near the practical ceiling on-device. The overlay
path hands the URI to the platform decoder instead and only costs a dialog window.
