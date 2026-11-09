#!/bin/sh
# DS100 Datalogger GUI. Options: --simulate (no logger needed), --dev /dev/hidrawN, --db FILE
D=$(dirname "$(readlink -f "$0")")
if [ ! -f "$D/venv/.ds100-installed" ]; then
    echo "The installation is not finished. Run: $D/install.sh"
    exit 1
fi
cd "$D" && exec "$D/venv/bin/python" -m ds100gui "$@"
