#!/bin/sh
# DS100 Datalogger – installs the Python dependencies into ./venv and adds a menu entry.
# Needs no root. The udev rule for USB access without sudo is printed at the end.
set -e
D=$(cd "$(dirname "$0")" && pwd)

if ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 10))'; then
    echo "Python 3.10 or newer is required."
    exit 1
fi
rm -f "$D/venv/.ds100-installed"
CLEAR=""
[ -x "$D/venv/bin/pip" ] || CLEAR="--clear"     # an earlier failed attempt left a venv without pip
if ! python3 -m venv $CLEAR "$D/venv"; then
    PV=$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')
    echo
    echo "The Python venv module is missing. On Debian/Ubuntu: sudo apt install python3-venv python$PV-venv"
    echo "Then run ./install.sh again."
    exit 1
fi
"$D/venv/bin/pip" install --upgrade pip
"$D/venv/bin/pip" install -r "$D/requirements.txt"
touch "$D/venv/.ds100-installed"
chmod +x "$D/ds100gui.sh" "$D/ds100.py"

APPS="$HOME/.local/share/applications"
mkdir -p "$APPS"
cat > "$APPS/ds100gui.desktop" <<DS100_DESKTOP_END
[Desktop Entry]
Type=Application
Name=DS100 Datalogger
Comment=Temperature and humidity from the DS100 USB logger
Exec="$D/ds100gui.sh"
Icon=utilities-system-monitor
Terminal=false
Categories=Utility;Science;
DS100_DESKTOP_END
echo
echo "Done. Start it with $D/ds100gui.sh (or 'DS100 Datalogger' in the application menu)."

if [ ! -f /etc/udev/rules.d/70-ds100.rules ]; then
    echo
    echo "To use the logger without sudo, install the udev rule once (needs sudo):"
    echo "  sudo cp \"$D/70-ds100.rules\" /etc/udev/rules.d/"
    echo "  sudo udevadm control --reload-rules"
    echo "Then unplug the logger and plug it in again."
fi
