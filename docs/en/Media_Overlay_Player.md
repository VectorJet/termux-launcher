# Web overlay player and browser

The terminal supports `OSC 7770 ; <source>` requests that open a floating WebView window over the
launcher. The window is draggable by its title bar, resizable from its bottom-right corner, keeps
its shape between opens while the process lives, and consumes its own touches — the terminal
underneath never sees them. Close it with the ✕ button in its title bar.

Playback and browsing run through Chromium's stack on a dedicated surface, so video is
hardware-decoded and never passes through the pty or the terminal bitmap pipeline — unlike the
Kitty graphics or Sixel paths, which redraw every frame as pixels in the grid.

## Shell usage

```sh
sh recipes/termux/media-overlay/media.sh media  ~/Videos/clip.mp4
sh recipes/termux/media-overlay/media.sh browse example.com
```

or copy the one-liners into your shell profile:

```sh
media()  { printf '\033]7770;%s\007' "$1"; }
browse() { printf '\033]7770;browse:%s\007' "$1"; }
```

Accepted sources:

- `media`: absolute paths, relative paths (resolved against external storage first, then Termux
  home), `file://`/`http(s)`/`content://` URLs;
- `browse`: any URL; a bare hostname gets `https://` prefixed.

## Why not play videos through Kitty graphics?

The Kitty graphics implementation transmits raw RGBA frames over escape sequences. That works for
animation formats like GIF at modest sizes, but video means software decode plus a full-frame copy
into the terminal per frame; a 640×360 stream is near the practical ceiling on-device. The overlay
hands the URI to Chromium instead and only costs a floating view.
