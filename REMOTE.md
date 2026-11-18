# Remote mode (v2.0.0)

The second launcher entry **SweepCal Remote** turns the phone into the microphone of the `sweepcal` server
running on the Debian PC (project `~/claude/sweepcal`, protocol in its `PROTOCOL.md`).

1. On the PC: `sweepcal serve ...`, then `sweepcal pair` (prints a QR code).
2. Scan the QR code with the phone camera: the app opens, pairs and connects.
   (Or: Find PC on LAN + type the token.)
3. Open the calibration page from the app (or `http://<pc>:8765/?token=...`) and start the calibration.

The phone records with UNPROCESSED (AGC/NS/AEC off), mono 16-bit, and uploads each recording as WAV.
A foreground service keeps the connection alive while the screen is off.
