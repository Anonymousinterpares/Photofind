"""PhotoFind PC indexer: a window around pc_index.run."""

import queue
import threading
import time
import tkinter as tk
from tkinter import scrolledtext, ttk

from PIL import ImageTk

import pc_index
from pc_index import Progress

POLL_MS = 100
PHONE_CHECK_MS = 3000


def duration(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 60:
        return f"{seconds} s"
    minutes, seconds = divmod(seconds, 60)
    if minutes < 60:
        return f"{minutes} min {seconds:02d} s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours} h {minutes:02d} min"


class App:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.events: "queue.Queue[tuple[str, object]]" = queue.Queue()
        self.stop = threading.Event()
        self.worker: threading.Thread | None = None
        self.phone_ready = False
        self.preview_image: ImageTk.PhotoImage | None = None

        root.title("PhotoFind – PC indexer")
        root.geometry("760x620")
        root.minsize(640, 520)
        root.protocol("WM_DELETE_WINDOW", self.on_close)

        main = ttk.Frame(root, padding=14)
        main.pack(fill="both", expand=True)

        phone = ttk.LabelFrame(main, text="Phone", padding=10)
        phone.pack(fill="x")
        self.phone_label = ttk.Label(phone, text="Looking for the phone…", font=("Segoe UI", 11))
        self.phone_label.pack(anchor="w")
        self.phone_hint = ttk.Label(phone, text="", foreground="#666")
        self.phone_hint.pack(anchor="w")

        run = ttk.LabelFrame(main, text="Indexing", padding=10)
        run.pack(fill="x", pady=(10, 0))
        top = ttk.Frame(run)
        top.pack(fill="x")

        stats = ttk.Frame(top)
        stats.pack(side="left", fill="both", expand=True)
        self.stage_label = ttk.Label(stats, text="Idle", font=("Segoe UI", 12, "bold"))
        self.stage_label.pack(anchor="w")
        self.count_label = ttk.Label(stats, text="", font=("Segoe UI", 11))
        self.count_label.pack(anchor="w", pady=(6, 0))
        self.speed_label = ttk.Label(stats, text="")
        self.speed_label.pack(anchor="w")
        self.time_label = ttk.Label(stats, text="")
        self.time_label.pack(anchor="w")
        self.current_label = ttk.Label(stats, text="", foreground="#666", wraplength=420)
        self.current_label.pack(anchor="w", pady=(6, 0))

        self.preview = ttk.Label(top, text="", width=1)
        self.preview.pack(side="right", padx=(10, 0))

        self.bar = ttk.Progressbar(run, mode="determinate")
        self.bar.pack(fill="x", pady=(10, 0))

        buttons = ttk.Frame(run)
        buttons.pack(fill="x", pady=(10, 0))
        self.start_button = ttk.Button(buttons, text="Index new photos", command=self.start)
        self.start_button.pack(side="left")
        self.stop_button = ttk.Button(buttons, text="Stop", command=self.request_stop, state="disabled")
        self.stop_button.pack(side="left", padx=(8, 0))

        log_frame = ttk.LabelFrame(main, text="Log", padding=6)
        log_frame.pack(fill="both", expand=True, pady=(10, 0))
        self.log_box = scrolledtext.ScrolledText(log_frame, height=8, font=("Consolas", 9), state="disabled")
        self.log_box.pack(fill="both", expand=True)

        self.log("Plug in the phone and press “Index new photos”. Searching on the phone works offline.")
        self.root.after(POLL_MS, self.pump)
        self.root.after(200, self.check_phone)

    def log(self, text: str) -> None:
        self.log_box.configure(state="normal")
        self.log_box.insert("end", f"{time.strftime('%H:%M:%S')}  {text}\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def check_phone(self) -> None:
        def probe() -> None:
            status = pc_index.phone_status()
            model = installed = None
            if status == "device":
                model = pc_index.phone_model()
                installed = pc_index.photofind_installed()
            self.events.put(("phone", (status, model, installed)))

        if self.worker is None:
            threading.Thread(target=probe, daemon=True).start()
        self.root.after(PHONE_CHECK_MS, self.check_phone)

    def show_phone(self, status: str, model: str | None, installed: bool | None) -> None:
        self.phone_ready = status == "device" and bool(installed)
        if status == "device" and installed:
            self.phone_label.configure(text=f"● {model} connected", foreground="#1a7f37")
            self.phone_hint.configure(text="PhotoFind is installed.")
        elif status == "device":
            self.phone_label.configure(text=f"● {model} connected", foreground="#9a6700")
            self.phone_hint.configure(text="PhotoFind is not installed on this phone.")
        elif status == "unauthorized":
            self.phone_label.configure(text="● Phone not authorised", foreground="#9a6700")
            self.phone_hint.configure(
                text="Unlock the phone, set USB mode to File transfer and tap Allow on the USB debugging prompt."
            )
        else:
            self.phone_label.configure(text="● No phone connected", foreground="#cf222e")
            self.phone_hint.configure(text="Connect the Pixel with a USB cable (USB debugging on).")
        if self.worker is None:
            self.start_button.configure(state="normal" if self.phone_ready else "disabled")

    def start(self) -> None:
        self.stop.clear()
        self.start_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.bar.configure(value=0, maximum=1)
        self.log("Starting…")

        def work() -> None:
            try:
                pc_index.run(
                    on_progress=lambda p: self.events.put(("progress", p)),
                    on_log=lambda text: self.events.put(("log", text)),
                    stop=self.stop,
                )
            except Exception as e:
                self.events.put(("log", f"Error: {e}"))
                self.events.put(("progress", Progress("Failed – see log")))
            finally:
                self.events.put(("finished", None))

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def request_stop(self) -> None:
        self.stop.set()
        self.stop_button.configure(state="disabled")
        self.stage_label.configure(text="Stopping – saving what's done to the phone…")

    def show_progress(self, p: Progress) -> None:
        self.stage_label.configure(text=p.stage)
        if p.total:
            self.bar.configure(maximum=p.total, value=p.done)
            percent = 100 * p.done / p.total
            skipped = f" · {p.skipped} skipped" if p.skipped else ""
            self.count_label.configure(text=f"{p.done:,} of {p.total:,} photos ({percent:.0f}%){skipped}")
        if p.rate:
            self.speed_label.configure(text=f"{p.rate:.1f} photos per second")
            self.time_label.configure(
                text=f"Elapsed {duration(p.elapsed_seconds)} · about {duration(p.eta_seconds)} left"
            )
        elif p.stage in ("Done", "Stopped") and p.elapsed_seconds:
            self.time_label.configure(text=f"Took {duration(p.elapsed_seconds)}")
        if p.current:
            self.current_label.configure(text=p.current.removeprefix("/storage/emulated/0/"))
        if p.preview is not None:
            self.preview_image = ImageTk.PhotoImage(p.preview)
            self.preview.configure(image=self.preview_image, width=0)

    def pump(self) -> None:
        latest: Progress | None = None
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "progress":
                    latest = payload
                elif kind == "log":
                    self.log(payload)
                elif kind == "phone":
                    self.show_phone(*payload)
                elif kind == "finished":
                    if latest:
                        self.show_progress(latest)
                        latest = None
                    self.worker = None
                    self.stop_button.configure(state="disabled")
                    self.start_button.configure(state="normal" if self.phone_ready else "disabled")
        except queue.Empty:
            pass
        if latest:
            self.show_progress(latest)
        self.root.after(POLL_MS, self.pump)

    def on_close(self) -> None:
        if self.worker is not None:
            self.request_stop()
            self.log("Finishing the current batch before closing…")
            self.root.after(500, self.close_when_idle)
        else:
            self.root.destroy()

    def close_when_idle(self) -> None:
        if self.worker is None:
            self.root.destroy()
        else:
            self.root.after(500, self.close_when_idle)


def main() -> None:
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", 1.3)
        ttk.Style().theme_use("vista")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
