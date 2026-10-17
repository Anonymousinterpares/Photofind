# PhotoFind

PhotoFind finds photos on your phone by describing them, and it can group faces into people. The phone keeps the photos and the index. This PC installs the app and, if you choose, scans the photos for the phone. Nothing is sent to the internet.

Open **PhotoFind** from the desktop or the Start menu. If that shortcut is missing, double-click `Install PhotoFind.bat` in this folder.

Only one phone can be plugged in at a time. Unplug the other one first.

---

## You already have PhotoFind on a phone

You do not need to turn on developer options again if USB debugging is already on.

1. Unlock the phone and plug it into this PC. USB can stay on **No data transfer**.
2. If the phone asks **Allow USB debugging?**, tap **Allow**.
3. Open **PhotoFind** on the PC. The top line should turn green and name the phone.
4. Press **Set up the phone**, then **Install on this phone**. This updates the app. Photos already scanned stay on the phone.
5. If the phone opens an access screen, turn on **Allow access to manage all files**, then press **Check again**.
6. Choose where to scan:
   - **Scan on this PC** — this computer does the scan, using the graphics card when it can, otherwise the processor. You can press **Pause**. If you unplug the phone, plug it back in and press **Continue the scan**. Photos already saved are not scanned again.
   - **Scan on the phone** — the phone scans by itself, so you can unplug after it starts. If the phone is interrupted, it continues from the last photo it saved. Pausing from the PC needs the cable.

A scan that was stopped on this PC shows **Continue the scan** on the first screen.

---

## A new phone

Do this when the phone has never had PhotoFind. The old phone is left as it is. The new phone starts empty: its photos are scanned from the beginning, and the old phone’s index is not copied across.

### On this PC, once

1. Double-click `Install PhotoFind.bat` in this folder (the same folder as this file).
2. Wait until the PhotoFind window opens. The first screen checks four things. All four should be marked ready:
   - the scanning model
   - the face models
   - the USB tool
   - the phone app package

If the model line is not ready, the file `models\embeddinggemma-2-text-vision-440m.litertlm` is missing, and the face files `models\faces\det_500m.onnx` and `models\faces\w600k_mbf.onnx` must be there too. Put them in those places and open PhotoFind again.

If Windows asks you to install Python, install Python 3.11 or newer from [python.org](https://www.python.org/downloads/), tick **Add python.exe to PATH**, then run `Install PhotoFind.bat` again.

The phone needs Android 12 or newer.

### On the new phone

1. Open **Settings** and search for **Build number**.
   - Pixel: **Settings → About phone → Build number**.
   - Some other phones: **Settings → About phone → Software information → Build number**.
2. Tap **Build number** seven times, until the phone says you are a developer. Enter your PIN if it asks.
3. Search Settings for **USB debugging** and turn it on.
   - Pixel: **Settings → System → Developer options → USB debugging**.
4. Plug the phone into this PC with a USB cable. You can leave the USB mode on **No data transfer**. File transfer is not required.
5. When the phone asks **Allow USB debugging?**, tap **Allow**. **Always allow from this computer** is fine.

### In the PhotoFind window

1. The top line should name the phone. If it says the phone is not allowed yet, unlock the phone and tap **Allow**.
2. Press **Set up the phone**, then **Install on this phone**.
3. Wait. The window copies the app and the models. The large model can take a few minutes the first time.
4. If a settings screen opens on the phone, turn on **Allow access to manage all files**, then press **Check again** on the PC. PhotoFind needs this to see photos in WhatsApp and other folders.
5. When the window says PhotoFind is installed, press **Choose where to scan** and pick one:
   - **Scan on this PC** if you want this computer to do the work. Leave the cable in, or unplug and plug back in later. The scan continues from the same photo.
   - **Scan on the phone** if you want to unplug and let the phone finish on its own.

Then open PhotoFind on the phone. The first time, it may ask for notifications. Allow them if you want to see scan progress there.

---

## While a scan is running

- **Pause** on the PC stops safely. **Resume** continues. On a phone scan, the cable has to be connected to pause from the PC.
- Unplugging during a **PC** scan does not lose photos already saved. Plug the phone back in. The window waits, then continues. You can also close the window and later press **Continue the scan**.
- Unplugging during a **phone** scan is normal. The phone keeps going. Plug it back in when you want the PC to show progress or to pause.
- Closing the PhotoFind window during a PC scan saves progress first. Open it again to continue.

---

## If the window does not see the phone

- **No phone connected** — use a data cable, not a charge-only cable. Unlock the screen. Try another USB port.
- **Not allowed yet** — unlock the phone and tap **Allow** on the USB debugging prompt. If you tapped Cancel before, unplug the cable, plug it in again, and watch the phone.
- **More than one phone** — unplug the one you are not setting up.
- **Not responding** — unplug, plug back in, and unlock the screen.
- **Install stays grey** — the phone is not in debugging mode yet. Finish the phone steps above.
- **All-files access will not turn on** — on the phone open the PhotoFind page under Settings, or press **Open phone settings** in the window, and turn the switch on. Then press **Check again**.
