"""PhotoFind setup: install the phone app, then scan on this PC or on the phone."""

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

STEPS = (
    ("computer", "This PC"),
    ("phone", "Phone"),
    ("install", "Install"),
    ("choose", "Where"),
    ("work", "Scan"),
)


def duration(seconds: float) -> str:
    seconds = max(0, int(seconds))
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
        self.events: "queue.Queue[tuple]" = queue.Queue()
        self.stop = threading.Event()
        self.pause = threading.Event()
        self.worker: threading.Thread | None = None
        self.probing = False
        self.switching = False
        self.step = "computer"
        self.mode: str | None = None
        self.install_state = "idle"
        self.phone = ("missing", None, False, False)  # status, model, installed, storage
        self.phone_paused = False
        self.preview_image: ImageTk.PhotoImage | None = None
        self.next_btn: ttk.Button | None = None
        self.pause_btn: ttk.Button | None = None
        self.hint_label: ttk.Label | None = None
        self.stage_label: ttk.Label | None = None
        self.count_label: ttk.Label | None = None
        self.speed_label: ttk.Label | None = None
        self.time_label: ttk.Label | None = None
        self.current_label: ttk.Label | None = None
        self.preview: ttk.Label | None = None
        self.bar: ttk.Progressbar | None = None

        root.title("PhotoFind")
        root.geometry("840x720")
        root.minsize(720, 600)
        root.protocol("WM_DELETE_WINDOW", self.on_close)

        main = ttk.Frame(root, padding=14)
        main.pack(fill="both", expand=True)
        self.crumbs = ttk.Frame(main)
        self.crumbs.pack(fill="x")
        self.phone_line = ttk.Label(main, text="Looking for a phone…", font=("Segoe UI", 11))
        self.phone_line.pack(anchor="w", pady=(8, 0))
        self.body = ttk.Frame(main)
        self.body.pack(fill="both", expand=True, pady=(12, 0))

        log_frame = ttk.LabelFrame(main, text="Log", padding=6)
        log_frame.pack(fill="both", expand=False, pady=(10, 0))
        self.log_box = scrolledtext.ScrolledText(
            log_frame, height=7, font=("Consolas", 9), state="disabled"
        )
        self.log_box.pack(fill="both", expand=True)
        self.buttons = ttk.Frame(main)
        self.buttons.pack(fill="x", pady=(10, 0))

        self.log("PhotoFind is ready on this PC. The steps below put it on the phone.")
        self.show_step("computer")
        self.root.after(POLL_MS, self.pump)
        self.root.after(200, self.check_phone)

    def log(self, text: str) -> None:
        self.log_box.configure(state="normal")
        self.log_box.insert("end", f"{time.strftime('%H:%M:%S')}  {text}\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def show_step(self, step: str) -> None:
        self.step = step
        self.next_btn = None
        self.pause_btn = None
        self.hint_label = None
        self.stage_label = None
        for child in self.crumbs.winfo_children():
            child.destroy()
        for child in self.body.winfo_children():
            child.destroy()
        for child in self.buttons.winfo_children():
            child.destroy()
        for name, label in STEPS:
            style = ("Segoe UI", 10, "bold") if name == step else ("Segoe UI", 10)
            color = "#1a1a1a" if name == step else "#888"
            ttk.Label(self.crumbs, text=label, font=style, foreground=color).pack(side="left", padx=(0, 14))
        getattr(self, f"step_{step}")()

    def heading(self, text: str) -> None:
        ttk.Label(self.body, text=text, font=("Segoe UI", 16, "bold")).pack(anchor="w")

    def explain(self, text: str) -> None:
        ttk.Label(self.body, text=text, wraplength=780, justify="left").pack(anchor="w", pady=(8, 0))

    def button(self, text: str, command, state: str = "normal") -> ttk.Button:
        btn = ttk.Button(self.buttons, text=text, command=command, state=state)
        btn.pack(side="left", padx=(0, 8))
        return btn

    def step_computer(self) -> None:
        self.heading("PhotoFind is on this PC")
        self.explain(
            "Next, prepare the phone. This window installs the app over the USB cable. "
            "USB debugging is enough — the phone can stay on charging, with file transfer off."
        )
        if pc_index.pc_unfinished():
            self.explain(
                "A scan on this PC was interrupted. Connect the phone and continue — "
                "photos already saved are not scanned again."
            )
        box = ttk.Frame(self.body)
        box.pack(anchor="w", pady=(12, 0))
        ready = True
        apk_missing = False
        for label, ok, detail in pc_index.pc_checks():
            ready = ready and ok
            if label.startswith("Phone app") and not ok:
                apk_missing = True
            mark = "●" if ok else "○"
            color = "#1a7f37" if ok else "#9a6700"
            row = ttk.Frame(box)
            row.pack(anchor="w", pady=2)
            ttk.Label(row, text=mark, foreground=color, font=("Segoe UI", 12)).pack(side="left")
            ttk.Label(row, text=f"  {label}", font=("Segoe UI", 11)).pack(side="left")
            ttk.Label(row, text=f"   {detail}", foreground="#666").pack(side="left")
        if not ready and not apk_missing:
            self.explain(
                "Something above is missing. The scanning model belongs in the models folder "
                f"as {pc_index.MODEL.name}. Run Install PhotoFind.bat if the USB tool is missing."
            )
        self.button("Set up the phone", lambda: self.show_step("phone"), "normal" if ready else "disabled")
        if apk_missing:
            self.button("Build the phone app", self.start_build)
        if pc_index.pc_unfinished():
            self.button("Continue the scan", lambda: self.pick("pc"))

    def step_phone(self) -> None:
        self.heading("Prepare a new phone")
        self.explain(
            "Use this for a phone that does not have PhotoFind yet. "
            "A phone that already has it only needs the cable and USB debugging — then press Install, which updates it.\n\n"
            "1. On the phone, open Settings and search for “Build number”. "
            "On a Pixel it is under About phone. On some other phones it is under About phone → Software information.\n"
            "2. Tap Build number seven times, until it says you are a developer. Enter your PIN if asked.\n"
            "3. Search Settings for “USB debugging” and turn it on. "
            "On a Pixel it lives in System → Developer options.\n"
            "4. Plug the phone into this PC. You can leave USB on “No data transfer”.\n"
            "5. When the phone asks “Allow USB debugging?”, tap Allow.\n\n"
            "The full guide is README.md, next to Install PhotoFind.bat."
        )
        self.hint_label = ttk.Label(self.body, text=self.phone_hint(), wraplength=780, justify="left")
        self.hint_label.pack(anchor="w", pady=(8, 0))
        status = self.phone[0]
        self.next_btn = self.button(
            "Install on this phone",
            self.go_install,
            "normal" if status == "device" else "disabled",
        )
        self.button("Back", lambda: self.show_step("computer"))

    def phone_hint(self) -> str:
        status, model, installed, _storage = self.phone
        if status == "device":
            extra = " PhotoFind is already on it and will be updated." if installed else ""
            return f"{model} is connected.{extra}"
        if status == "unauthorized":
            return "The phone is connected but not allowed yet. Unlock it and tap Allow."
        if status == "multiple":
            return "More than one phone is connected. Unplug the others."
        if status == "offline":
            return "The phone stopped responding. Unplug it, plug it back in, and unlock the screen."
        return "No phone yet. Connect it when USB debugging is on."

    def step_install(self) -> None:
        self.heading("Install PhotoFind on the phone")
        self.explain(
            "The app and the models are copied to the phone. If a permission screen opens, "
            "turn on access to all files."
        )
        if self.phone[0] != "device" and self.install_state != "running":
            self.explain("Connect the phone first.")
        elif self.install_state == "running":
            self.explain("Installing. Copying the model can take a few minutes.")
        self.button("Back", lambda: self.show_step("phone"), "disabled" if self.install_state == "running" else "normal")
        if self.install_state == "need_storage":
            self.explain("On the phone, turn on “Allow access to manage all files”, then press Check again.")
            self.button("Open phone settings", self.open_storage_settings)
            self.button("Check again", self.recheck_storage)
        elif self.install_state == "ready":
            self.explain("PhotoFind is installed and can read the photos.")
            self.button("Choose where to scan", lambda: self.show_step("choose"))
            self.button("Install again", self.start_install)
        elif self.install_state == "error":
            self.button("Try again", self.start_install)

    def step_choose(self) -> None:
        self.heading("Where should the photos be scanned?")
        self.explain(
            "On this PC — the same model runs here, on the graphics card when it can, "
            "otherwise on the processor. You can pause. If you unplug the phone, "
            "plug it back in and the scan continues from the same photo."
        )
        self.button("Scan on this PC", lambda: self.pick("pc"))
        self.explain(
            "On the phone — the phone scans its own photos, so you can unplug once it has started. "
            "Pause from here needs the cable. An interrupted scan continues from the last photo it saved."
        )
        self.button("Scan on the phone", lambda: self.pick("phone"))
        self.button("Back", lambda: self.show_step("install"))

    def step_work(self) -> None:
        title = "Scanning on this PC" if self.mode == "pc" else "Scanning on the phone"
        self.heading(title if self.mode else "Scanning")
        self.stage_label = ttk.Label(self.body, text="Starting…", font=("Segoe UI", 12, "bold"))
        self.stage_label.pack(anchor="w", pady=(10, 0))
        self.count_label = ttk.Label(self.body, text="", font=("Segoe UI", 11))
        self.count_label.pack(anchor="w", pady=(6, 0))
        self.speed_label = ttk.Label(self.body, text="")
        self.speed_label.pack(anchor="w")
        self.time_label = ttk.Label(self.body, text="")
        self.time_label.pack(anchor="w")
        self.current_label = ttk.Label(self.body, text="", foreground="#666", wraplength=560)
        self.current_label.pack(anchor="w", pady=(6, 0))
        self.bar = ttk.Progressbar(self.body, mode="determinate")
        self.bar.pack(fill="x", pady=(10, 0))
        self.preview = ttk.Label(self.body)
        self.preview.pack(anchor="w", pady=(8, 0))
        self.pause_btn = self.button("Pause", self.toggle_pause)
        self.button("Choose again", self.back_to_choose)

    def start_build(self) -> None:
        self.log("Building the phone app…")

        def work() -> None:
            try:
                pc_index.build_apk(lambda text: self.events.put(("log", text)))
            except Exception as e:
                self.events.put(("log", f"Error: {e}"))
            self.events.put(("built", None))

        threading.Thread(target=work, daemon=True).start()

    def go_install(self) -> None:
        self.show_step("install")
        if self.install_state == "idle":
            self.start_install()

    def start_install(self) -> None:
        if self.phone[0] != "device":
            self.log("Connect the phone before installing.")
            return
        self.install_state = "running"
        self.log("Installing PhotoFind on the phone…")
        if self.step == "install":
            self.show_step("install")

        def work() -> None:
            try:
                note = lambda text: self.events.put(("log", text))
                pc_index.install_app(note)
                pc_index.push_models(note)
                allowed = pc_index.grant_access(note)
                if not allowed:
                    try:
                        pc_index.open_all_files_settings()
                        note("Opened the all-files setting on the phone.")
                    except Exception as e:
                        note(str(e))
                self.events.put(("installed", allowed))
            except Exception as e:
                self.events.put(("install_error", str(e)))

        threading.Thread(target=work, daemon=True).start()

    def recheck_storage(self) -> None:
        def work() -> None:
            try:
                allowed = pc_index.grant_access(lambda text: self.events.put(("log", text)))
                self.events.put(("installed", allowed))
            except Exception as e:
                self.events.put(("install_error", str(e)))

        threading.Thread(target=work, daemon=True).start()

    def open_storage_settings(self) -> None:
        def work() -> None:
            try:
                pc_index.open_all_files_settings()
            except Exception as e:
                self.events.put(("log", f"Error: {e}"))

        threading.Thread(target=work, daemon=True).start()

    def pick(self, mode: str) -> None:
        if self.switching:
            return
        if (
            mode == "pc"
            and self.mode == "pc"
            and self.worker is not None
            and self.worker.is_alive()
        ):
            self.pause.clear()
            self.show_step("work")
            return
        self.switching = True
        self.mode = mode
        self.show_step("work")
        threading.Thread(target=lambda: self.switch_to(mode), daemon=True).start()

    def switch_to(self, mode: str) -> None:
        self.stop.set()
        worker = self.worker
        if worker is not None:
            worker.join()
        self.worker = None
        self.stop = threading.Event()
        self.pause = threading.Event()
        self.events.put(("begin", mode))

    def begin(self, mode: str) -> None:
        self.switching = False
        self.mode = mode
        if self.step != "work":
            return
        if mode == "pc":
            self.start_pc()
        else:
            self.start_phone()

    def start_pc(self) -> None:
        stop = self.stop
        pause = self.pause
        self.log("Starting the scan on this PC…")

        def work() -> None:
            try:
                pc_index.run(
                    on_progress=lambda p: self.events.put(("progress", p)),
                    on_log=lambda text: self.events.put(("log", text)),
                    stop=stop,
                    pause=pause,
                )
            except Exception as e:
                self.events.put(("log", f"Error: {e}"))
                self.events.put(("progress", Progress("Failed — see the log")))
            finally:
                self.events.put(("finished", None))

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def start_phone(self) -> None:
        self.phone_paused = False
        self.log("Starting the scan on the phone…")

        def work() -> None:
            try:
                pc_index.hand_over_to_phone(lambda text: self.events.put(("log", text)))
            except Exception as e:
                self.events.put(("log", f"Error: {e}"))
            self.events.put(("phone_started", None))

        threading.Thread(target=work, daemon=True).start()

    def toggle_pause(self) -> None:
        if self.mode == "phone":
            if self.phone[0] != "device":
                self.log("Plug the phone in to pause or resume it.")
                return
            target = pc_index.resume_phone if self.phone_paused else pc_index.pause_phone
            self.phone_paused = not self.phone_paused
            if self.pause_btn is not None:
                self.pause_btn.configure(text="Resume" if self.phone_paused else "Pause")

            def work() -> None:
                try:
                    target()
                    if not self.phone_paused:
                        pc_index.launch_app()
                except Exception as e:
                    self.events.put(("log", f"Error: {e}"))

            threading.Thread(target=work, daemon=True).start()
            return
        if self.pause.is_set():
            self.pause.clear()
            if self.pause_btn is not None:
                self.pause_btn.configure(text="Pause")
            self.log("Resuming.")
        else:
            self.pause.set()
            if self.pause_btn is not None:
                self.pause_btn.configure(text="Resume")
            if self.stage_label is not None:
                self.stage_label.configure(text="Pausing — saving what’s already scanned…")

    def back_to_choose(self) -> None:
        if self.mode == "pc" and self.worker is not None and self.worker.is_alive():
            self.pause.set()
            self.log("Pausing. Choose again when you’re ready — progress stays saved.")
        self.show_step("choose")

    def check_phone(self) -> None:
        if not self.probing:
            self.probing = True

            def probe() -> None:
                try:
                    status = pc_index.phone_status()
                    model = installed = storage = work = None
                    if status == "device":
                        model = pc_index.phone_model()
                        installed = pc_index.photofind_installed()
                        if installed:
                            storage = pc_index.storage_allowed()
                            if self.mode == "phone" and self.step == "work":
                                work = pc_index.phone_work_status()
                    self.events.put(("phone", (status, model, installed, storage, work)))
                except Exception as e:
                    self.events.put(("log", f"Phone check failed: {e}"))
                finally:
                    self.events.put(("probed", None))

            threading.Thread(target=probe, daemon=True).start()
        self.root.after(PHONE_CHECK_MS, self.check_phone)

    def apply_phone(self, status, model, installed, storage, work) -> None:
        self.phone = (status, model, bool(installed), bool(storage))
        if status == "device" and installed:
            text, color = f"● {model} connected, PhotoFind installed", "#1a7f37"
        elif status == "device":
            text, color = f"● {model} connected", "#9a6700"
        elif status == "unauthorized":
            text, color = "● Phone not allowed yet — tap Allow", "#9a6700"
        elif status == "multiple":
            text, color = "● More than one phone is connected", "#9a6700"
        elif status == "offline":
            text, color = "● Phone not responding", "#9a6700"
        else:
            text, color = "● No phone connected", "#cf222e"
        self.phone_line.configure(text=text, foreground=color)
        if self.step == "phone" and self.hint_label is not None:
            self.hint_label.configure(text=self.phone_hint())
        if self.step == "phone" and self.next_btn is not None:
            self.next_btn.configure(state="normal" if status == "device" else "disabled")
        if self.step == "install" and self.install_state == "need_storage" and storage:
            self.install_state = "ready"
            self.show_step("install")
        if self.step == "work" and self.mode == "phone":
            self.apply_phone_work(work)

    def apply_phone_work(self, work) -> None:
        if self.stage_label is None or self.pause_btn is None or self.bar is None or self.count_label is None:
            return
        if self.phone[0] != "device":
            self.stage_label.configure(text="Phone unplugged")
            self.count_label.configure(
                text="The phone keeps scanning. Plug it back in to see progress or to pause."
            )
            self.pause_btn.configure(state="disabled")
            return
        self.pause_btn.configure(state="normal")
        if work is None:
            return
        stage, done, total = work
        labels = {
            "indexing": "Scanning photos on the phone",
            "faces": "Looking for faces on the phone",
            "paused": "Paused on the phone",
            "pc": "This PC took over the scan",
        }
        self.phone_paused = stage == "paused"
        self.stage_label.configure(text=labels.get(stage, stage))
        self.pause_btn.configure(text="Resume" if self.phone_paused else "Pause")
        if total:
            self.bar.configure(maximum=total, value=min(done, total))
            self.count_label.configure(text=f"{done:,} of {total:,} photos")
            if done >= total:
                self.stage_label.configure(text="This pass finished on the phone")
        elif stage == "indexing":
            self.count_label.configure(text="Looking through the folders…")

    def show_progress(self, p: Progress) -> None:
        if self.step != "work" or self.stage_label is None or self.bar is None:
            return
        title = p.stage
        if p.backend and p.stage == "Indexing":
            title = f"Scanning on the {p.backend}"
        self.stage_label.configure(text=title)
        if p.total and self.count_label is not None:
            percent = 100 * p.done / p.total
            skipped = f" · {p.skipped} skipped" if p.skipped else ""
            self.count_label.configure(text=f"{p.done:,} of {p.total:,} photos ({percent:.0f}%){skipped}")
            self.bar.configure(maximum=p.total, value=min(p.done, p.total))
        if self.speed_label is not None and self.time_label is not None:
            if p.rate:
                self.speed_label.configure(text=f"{p.rate:.1f} photos per second")
                self.time_label.configure(
                    text=f"Elapsed {duration(p.elapsed_seconds)} · about {duration(p.eta_seconds)} left"
                )
            elif p.stage in ("Done", "Paused", "Phone is up to date") and p.elapsed_seconds:
                self.time_label.configure(text=f"Elapsed {duration(p.elapsed_seconds)}")
        if self.current_label is not None and p.current:
            self.current_label.configure(text=p.current.removeprefix("/storage/emulated/0/"))
        if p.preview is not None and self.preview is not None:
            self.preview_image = ImageTk.PhotoImage(p.preview)
            self.preview.configure(image=self.preview_image)
        if self.pause_btn is not None and p.stage in ("Paused", "Indexing", "Waiting for the phone"):
            self.pause_btn.configure(text="Resume" if self.pause.is_set() or p.stage == "Paused" else "Pause")

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
                    self.apply_phone(*payload)
                elif kind == "probed":
                    self.probing = False
                elif kind == "installed":
                    self.install_state = "ready" if payload else "need_storage"
                    if self.step == "install":
                        self.show_step("install")
                elif kind == "install_error":
                    self.install_state = "error"
                    self.log(f"Error: {payload}")
                    if self.step == "install":
                        self.show_step("install")
                elif kind == "built":
                    if self.step == "computer":
                        self.show_step("computer")
                elif kind == "begin":
                    self.begin(payload)
                elif kind == "phone_started":
                    if self.stage_label is not None and self.step == "work":
                        self.stage_label.configure(text="Scanning on the phone")
                elif kind == "finished":
                    if latest is not None:
                        self.show_progress(latest)
                        latest = None
                    self.worker = None
                    if self.pause_btn is not None:
                        self.pause_btn.configure(state="disabled")
        except queue.Empty:
            pass
        if latest is not None:
            self.show_progress(latest)
        self.root.after(POLL_MS, self.pump)

    def on_close(self) -> None:
        if self.worker is not None and self.worker.is_alive():
            self.stop.set()
            self.log("Saving progress before closing…")
            self.root.after(400, self.close_when_idle)
        else:
            self.root.destroy()

    def close_when_idle(self) -> None:
        if self.worker is None or not self.worker.is_alive():
            self.root.destroy()
        else:
            self.root.after(400, self.close_when_idle)


def main() -> None:
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", 1.25)
        ttk.Style().theme_use("vista")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
