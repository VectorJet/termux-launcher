#!/data/data/com.termux/files/usr/bin/sh
# Drive the launcher's floating web overlay from the shell.
#
# Usage:  media  <file-or-url>   play a file or stream in a floating window
#         browse <url>           open a floating browser
#
# Examples:
#          media ~/Movies/clip.mp4
#          browse example.com
#
# Both open the same draggable, resizable WebView window. Media files play through an HTML5
# video element backed by the platform decoder; nothing passes through the pty.

[ $# -eq 1 ] || { echo "usage: media|browse <file-or-url>" >&2; exit 2; }

mode=$1
src=$2

case $src in
    http://*|https://*|content://*) ;;
    /*) ;;
    *) src="$PWD/$src" ;;
esac

case $mode in
    browse) printf '\033]7770;browse:%s\007' "$src" ;;
    media)  printf '\033]7770;%s\007' "$src" ;;
    *)      echo "usage: media|browse <file-or-url>" >&2; exit 2 ;;
esac
