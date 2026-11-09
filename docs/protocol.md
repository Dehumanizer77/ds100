# DS100 USB protocol and memory map

Reverse engineered from the vendor's Windows software *DataLogger V2.70*
(`DataLogger.exe` and `USBDrive.dll`) and checked on a real logger.

## USB

| | |
|---|---|
| Vendor / product ID | `1941:8021` (shown as `HID 1941:8021`, no strings) |
| Class | HID, one interface, low speed, interrupt IN endpoint, 8-byte packets |
| Reports | no report IDs, 8 bytes in both directions |

An output report is sent as HID `SET_REPORT` (bmRequestType `0x21`, bRequest `0x09`,
wValue `0x0200`, wIndex = interface) or on an interrupt OUT endpoint if the device has one.
On Linux, writing `00` + 8 bytes to `/dev/hidrawN` does exactly that; reading `/dev/hidrawN`
returns one 8-byte input report.

On Android, read **exactly one packet (8 bytes)** per transfer: the packets are full-size,
so a longer request is not ended by a short packet and runs into the timeout.

## Commands

The logger behaves like a 64 KiB memory. Addresses are 16 bit, big endian.

| Operation | Sent (8 bytes) | Answer |
|---|---|---|
| read 32 bytes | `A1 hi lo 20 A1 00 00 20` | 4 reports × 8 bytes of data |
| write 32 bytes | `A0 hi lo 20 A0 00 00 20`, then 4 reports × 8 bytes of data | `A5 A5 A5 A5 A5 A5 A5 A5` |
| write 1 byte | `A2 hi lo 20 A2 vv 00 20` | `A5 A5 A5 A5 A5 A5 A5 A5` |

Like `USBDrive.dll`, the apps read every block twice and accept it only when both reads are
equal, and after a write they read the block back and compare (up to 10 retries).

## Memory map

| Address | Content |
|---|---|
| `0x00` | LCD temperature unit: `FF` = °C, `00` = °F |
| `0x01` | logging interval in minutes, 1–240 |
| `0x02` | page of the write pointer: records end before `(page + 1) × 0x100` |
| `0x03` | `FF` = the ring buffer has wrapped |
| `0x04` | number of enabled schedules (no UI in the Windows app) |
| `0x05` | LCD date format: `FF` = dd-mm-yy, `00` = mm-dd-yy |
| `0x08–0x0B` | device ID, big-endian Unix time written by the PC software |
| `0x0C–0x0F` | bitwise NOT of the ID (consistency check) |
| `0x10` | clock-set trigger, write `55` |
| `0x11–0x16` | new clock: YY MM DD hh mm ss (binary, YY = year − 2000) |
| `0x20` | clock-set answer: `55` when the logger took the time |
| `0x21–0x26` | logger clock after setting it: YY MM DD hh mm ss |
| `0x30–0x6F` | 4 schedules × 16 bytes (start YY MM DD hh mm, end YY MM DD hh mm, 2 parameter bytes); not used by the Windows UI |
| `0xF0` | one byte written by the Windows app on setup, meaning unknown |
| `0x100–0xFFFF` | ring buffer of 4-byte records, `FF FF FF FF` = empty |

If the ID is missing or the check fails (for example after clearing), the PC software writes
the current Unix time as the new ID.

## Records

| Byte | Meaning |
|---|---|
| b0 bit 7 | sign |
| (b0 & 0x7F) << 8 \| b1 | temperature × 10 + 400 (0 = −40.0 °C, 1050 = 65.0 °C; larger values are clamped) |
| b2 | relative humidity in %, 0–100 |
| b3 | minutes since the previous record, 1–240 (other values: record skipped) |

The logger does not store timestamps. The newest record is the one in progress: its b3 still
counts the minutes since the previous record and its values are the current reading.

Times are reconstructed like the Windows app does it:

* first download: the newest (in-progress) record is placed at the PC clock and every older
  record is the sum of the following b3 minutes earlier;
* later downloads start at the address of the record that was in progress last time and
  continue from the time of the newest stored record, unless the new records cover more minutes
  than have passed since then (then they are anchored to the PC clock again).

Reading order: from `(page + 1) × 0x100` to `0xFFFF` and then from `0x100` if the buffer has
wrapped, otherwise from `0x100` (or the last position) up to `(page + 1) × 0x100`, stopping at
the first empty record.

## Settings

The Windows Setup dialog writes byte `0x00` (unit), `0x01` (interval) and `0x05` (date format).
It also writes `0x04`, `0xF0` and the schedules at `0x30–0x6F`; the Linux and Android apps
leave those untouched.

## Setting the clock

1. write `0x11–0x16` = YY MM DD hh mm ss and then `0x10` = `55`;
2. wait 2.5 s and read `0x20`; `55` means the logger took the time, `0x21–0x26` is its clock;
3. write `FF` to `0x20`.

The logger picks up the trigger only now and then, so like the Windows app the time and the
trigger are written again every 5 seconds until it answers (the apps give up after 3 minutes).
Some loggers report the clock in whole minutes, so the difference to the PC clock is only
reported, not checked.

## Clearing the memory

The Windows app writes `FF` to `0x000–0x1FF` (header and the start of the ring). The logger has
to be unplugged and plugged in again; it then gets a new ID from the PC software.
