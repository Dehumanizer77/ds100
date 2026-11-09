# DS100 Datalogger for Linux

Replacement for the Windows program DataLogger V2.70 for the DS100 USB temperature and
humidity logger (USB ID `1941:8021`). Project page: https://github.com/Dehumanizer77/ds100

## Installation

Needs Python 3.10 or newer with the `venv` module and an internet connection
(PySide6 and pyqtgraph are installed into `venv/` in this folder).

```sh
./install.sh
```

No root is needed. The script also adds **DS100 Datalogger** to the application menu.

To use the logger without `sudo`, install the udev rule once:

```sh
sudo cp 70-ds100.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
```

Then unplug the logger and plug it in again.

## Running

```sh
./ds100gui.sh              # normal start
./ds100gui.sh --simulate   # without a logger, with simulated data
./ds100gui.sh --dev /dev/hidraw3 --db /path/to/datalogger.db
```

The logger is read automatically when it is plugged in.

* **History** – table for a period, export to CSV
* **Graph** – temperature, humidity, dew point; export to PNG, PDF, SVG, CSV
* **Setup** – title, °C/°F and date format on the LCD, interval 1–240 minutes
* **Sync time** – sets the logger clock (the logger may take up to a few minutes to accept it)
* **Management** – remove a device or its history from the database, erase the logger memory
* **Language** – English or Slovak (default: the system language)

Data: `~/.local/share/ds100/datalogger.db` (SQLite), settings: `~/.config/ds100/ds100gui.conf`.

## Command line

```sh
venv/bin/python ds100.py info
venv/bin/python ds100.py csv data.csv
venv/bin/python ds100.py set --interval 30 --unit C --date dmy
venv/bin/python ds100.py sync
venv/bin/python ds100.py dump memory.bin
venv/bin/python ds100.py read 0x20
```

## Troubleshooting

* `Permission denied: '/dev/hidrawN'` – install the udev rule, close the app, replug the logger.
* `ensurepip is not available` – `sudo apt install python3-venv` (Ubuntu 24.04:
  `python3.12-venv`), then run `./install.sh` again.
* The window does not open and Qt complains about the "xcb" plugin –
  `sudo apt install libxcb-cursor0`.

## Tests

```sh
venv/bin/python tests/test_device.py
QT_QPA_PLATFORM=offscreen venv/bin/python tests/test_ui.py /tmp/ds100-test
```
