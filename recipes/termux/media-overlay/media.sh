#!/data/data/com.termux/files/usr/bin/sh
# Play a file or URL in the launcher's floating media overlay instead of the terminal grid.
#
# Usage:  media <file-or-url>
# Example: media ~/Movies/clip.mp4
#          media https://example.com/stream.m3u8
#
# The overlay uses Android's MediaCodec directly, so playback is hardware-accelerated and does
# not pass through the pty. Works for anything the platform MediaPlayer handles (h264, vp9, av1
# depending on device, mp3, aac, ...). HLS/DASH support depends on the Android version.

[ $# -eq 1 ] || { echo "usage: media <file-or-url>" >&2; exit 2; }

src=$1
case $src in
    http://*|https://*|content://*) ;;
    /*) ;;
    *) src="$PWD/$src" ;;
esac

# BEL-terminated OSC 7770. Percent-encode nothing: paths with ';' would split parameters.
printf '\033]7770;%s\007' "$src"
