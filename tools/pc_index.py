"""Indexes the phone's photos on this PC and hands the embeddings back to PhotoFind.

Usage (phone connected over USB, PhotoFind installed):
    tools\\.venv\\Scripts\\python.exe tools\\pc_index.py      command line
    tools\\PhotoFind-PC.bat                                  setup window

Photos are streamed off the phone, embedded with the same EmbeddingGemma 2 model the phone
uses (GPU when it can load, otherwise CPU), and imported batch by batch. Each finished
embedding is also written to a local spool, so pausing or unplugging the phone continues
from the same place: anything already saved on the phone is not done again.
"""

import argparse
import base64
import io
import json
import os
import queue
import shutil
import subprocess
import tarfile
import threading
import time
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import pillow_heif
from PIL import Image, ImageOps

pillow_heif.register_heif_opener()

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "models" / "embeddinggemma-2-text-vision-440m.litertlm"
FACE_MODELS = ("det_500m.onnx", "w600k_mbf.onnx")
WORK = ROOT / "models" / "pc_index"
SPOOL = WORK / "spool.tsv"
SESSION = WORK / "session.json"
PACKAGE = "com.photofind"
PHONE_DIR = "/sdcard/Download/PhotoFind"
PHONE_DOWNLOAD = "/sdcard/Download"
RECEIVER = "com.photofind/.sync.PcSyncReceiver"
LIST_ON_PHONE = "/data/local/tmp/photofind_batch.txt"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

# Must match decodeForEmbedding's maxSide in the app.
MAX_SIDE = 768


class PhoneDisconnected(RuntimeError):
    """The USB device went away. Work already spooled is still on disk."""


@dataclass
class Progress:
    stage: str
    done: int = 0
    total: int = 0
    skipped: int = 0
    rate: float = 0.0
    eta_seconds: float = 0.0
    elapsed_seconds: float = 0.0
    current: str = ""
    preview: Image.Image | None = None
    backend: str = ""


def find_adb() -> str:
    """adb from PHOTOFIND_ADB, the suite's copy, the Android SDK, or PATH."""
    env = os.environ.get("PHOTOFIND_ADB")
    if env and Path(env).is_file():
        return env
    candidates = [
        Path(__file__).resolve().parent / "platform-tools" / "adb.exe",
        Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe",
    ]
    for path in candidates:
        if path.is_file():
            return str(path)
    found = shutil.which("adb")
    return found or str(candidates[1])


def is_disconnect(text: str) -> bool:
    lowered = text.lower()
    return any(
        needle in lowered
        for needle in (
            "no devices/emulators",
            "device offline",
            "device not found",
            "error: closed",
            "protocol fault",
        )
    )


def adb(*args: str, check: bool = True, timeout: float | None = 120) -> str:
    exe = find_adb()
    if not Path(exe).is_file():
        raise FileNotFoundError(exe)
    try:
        result = subprocess.run(
            [exe, *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            creationflags=NO_WINDOW,
        )
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f"adb {' '.join(args)} timed out") from e
    combined = f"{result.stdout}\n{result.stderr}"
    if result.returncode != 0 and is_disconnect(combined):
        raise PhoneDisconnected(result.stderr.strip() or "Phone disconnected")
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def parse_devices(text: str) -> str:
    """'device', 'multiple', 'unauthorized', 'offline' or 'missing'."""
    states = []
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            states.append(parts[1])
    ready = [state for state in states if state == "device"]
    if len(ready) > 1:
        return "multiple"
    if ready:
        return "device"
    return states[0] if states else "missing"


def phone_status() -> str:
    try:
        return parse_devices(adb("devices", check=False))
    except (FileNotFoundError, PhoneDisconnected):
        return "missing"


def phone_model() -> str:
    return adb("shell", "getprop", "ro.product.model", check=False).strip() or "Phone"


def photofind_installed() -> bool:
    return PACKAGE in adb("shell", "pm", "list", "packages", PACKAGE, check=False)


def phone_ready() -> bool:
    return phone_status() == "device" and photofind_installed()


def storage_allowed() -> bool:
    text = adb("shell", "appops", "get", PACKAGE, "MANAGE_EXTERNAL_STORAGE", check=False).lower()
    return "allow" in text


def parse_status(text: str) -> tuple[str, int, int] | None:
    line = ""
    for candidate in text.splitlines():
        if candidate.strip():
            line = candidate.strip()
    parts = line.split("\t")
    if len(parts) < 3:
        return None
    try:
        return parts[0], int(parts[1]), int(parts[2])
    except ValueError:
        return None


def phone_work_status() -> tuple[str, int, int] | None:
    return parse_status(adb("shell", "cat", f"{PHONE_DIR}/status.txt", check=False))


def broadcast(action: str, *extras: str) -> None:
    adb("shell", "am", "broadcast", "-a", action, "-n", RECEIVER, *extras)


def remote_size(path: str) -> int | None:
    text = adb("shell", "stat", "-c", "%s", path, check=False).strip()
    return int(text) if text.isdigit() else None


def model_files() -> list[Path]:
    return [MODEL, *(ROOT / "models" / "faces" / name for name in FACE_MODELS)]


def model_on_phone(name: str, size: int) -> bool:
    for path in (
        f"{PHONE_DOWNLOAD}/{name}",
        f"/sdcard/Android/data/{PACKAGE}/files/{name}",
    ):
        if remote_size(path) == size:
            return True
    listed = adb(
        "shell", "run-as", PACKAGE, "stat", "-c", "%s", f"files/{name}", check=False
    ).strip()
    return listed.isdigit() and int(listed) == size


def apk_path() -> Path | None:
    candidates = [
        ROOT / "tools" / "dist" / "PhotoFind.apk",
        ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
        ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk",
    ]
    found = [path for path in candidates if path.is_file() and path.stat().st_size > 0]
    return max(found, key=lambda path: path.stat().st_mtime) if found else None


def pc_checks() -> list[tuple[str, bool, str]]:
    """What the PC install needs before the phone can be set up. (label, ok, detail)."""
    model_ok = MODEL.is_file() and MODEL.stat().st_size > 0
    faces_ok = all((ROOT / "models" / "faces" / name).is_file() for name in FACE_MODELS)
    adb_path = find_adb()
    adb_ok = Path(adb_path).is_file()
    apk = apk_path()
    return [
        ("Scanning model", model_ok, str(MODEL) if model_ok else f"Missing {MODEL.name}"),
        ("Face models", faces_ok, "det_500m.onnx and w600k_mbf.onnx"),
        ("USB tool (adb)", adb_ok, adb_path if adb_ok else "Not found — run Install PhotoFind.bat"),
        ("Phone app package", apk is not None, str(apk) if apk else "Not built yet"),
    ]


def pc_ready() -> bool:
    return all(ok for _, ok, _ in pc_checks())


def read_session() -> dict:
    try:
        return json.loads(SESSION.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def mark_session(mode: str, complete: bool) -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    SESSION.write_text(json.dumps({"mode": mode, "complete": complete}), encoding="utf-8")


def pc_unfinished() -> bool:
    if spool_lines():
        return True
    session = read_session()
    return session.get("mode") == "pc" and not session.get("complete", True)


def spool_lines() -> list[str]:
    if not SPOOL.is_file():
        return []
    return [line for line in SPOOL.read_text(encoding="utf-8").splitlines() if line.strip()]


def spool_append(line: str) -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    with SPOOL.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(line + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def spool_replace(lines: list[str]) -> None:
    if not lines:
        SPOOL.unlink(missing_ok=True)
        return
    WORK.mkdir(parents=True, exist_ok=True)
    tmp = SPOOL.with_suffix(".tmp")
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    tmp.replace(SPOOL)


def build_apk(on_log: Callable[[str], None]) -> Path:
    on_log("Building the phone app. This can take a few minutes…")
    proc = subprocess.Popen(
        [str(ROOT / "gradlew.bat"), ":app:assembleDebug"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        creationflags=NO_WINDOW,
    )
    assert proc.stdout is not None
    for line in proc.stdout:
        text = line.strip()
        if text.startswith(("> Task", "e:", "w:", "BUILD", "FAILURE")) or "FAILED" in text:
            on_log(text)
    if proc.wait() != 0:
        raise RuntimeError("The phone app failed to build.")
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk.is_file():
        raise RuntimeError("The build finished but the APK is missing.")
    dest = ROOT / "tools" / "dist" / "PhotoFind.apk"
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, dest)
    on_log("Phone app package is ready.")
    return dest


def install_app(on_log: Callable[[str], None]) -> None:
    apk = apk_path()
    if apk is None:
        raise RuntimeError("The phone app has not been built yet.")
    on_log(f"Installing PhotoFind…")
    adb("install", "-r", "-d", "-t", str(apk), timeout=300)
    if not photofind_installed():
        raise RuntimeError("Install finished, but the phone does not list PhotoFind.")
    on_log("PhotoFind is installed on the phone.")


def push_models(on_log: Callable[[str], None]) -> None:
    missing = [path.name for path in model_files() if not path.is_file()]
    if missing:
        raise RuntimeError("Missing on this PC: " + ", ".join(missing))
    for local in model_files():
        size = local.stat().st_size
        if model_on_phone(local.name, size):
            on_log(f"{local.name} is already on the phone.")
            continue
        on_log(f"Copying {local.name} to the phone ({size / 1_000_000:.0f} MB)…")
        adb("push", str(local), f"{PHONE_DOWNLOAD}/{local.name}", timeout=1800)
        on_log(f"{local.name} copied.")


def grant_access(on_log: Callable[[str], None]) -> bool:
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.READ_MEDIA_IMAGES", check=False)
    adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow", check=False)
    allowed = storage_allowed()
    on_log(
        "The phone can read every photo folder."
        if allowed
        else "PhotoFind still needs permission to read all files. Turn that on when the phone asks."
    )
    return allowed


def open_all_files_settings() -> None:
    adb(
        "shell",
        "am",
        "start",
        "-a",
        "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
        "-d",
        f"package:{PACKAGE}",
    )


def launch_app() -> None:
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")


def pause_phone() -> None:
    broadcast("com.photofind.PAUSE")


def resume_phone() -> None:
    adb("shell", f"rm -f '{PHONE_DIR}'/*.ok '{PHONE_DIR}/manifest.tsv' {LIST_ON_PHONE}", check=False)
    broadcast("com.photofind.RESUME")


def hand_over_to_phone(on_log: Callable[[str], None]) -> None:
    """Lets the phone scan, after delivering embeddings the PC already finished."""
    if spool_lines():
        on_log("Sending photos already scanned on this PC…")
        flush_spool(on_log)
    on_log("The phone is scanning. You can unplug the cable.")
    resume_phone()
    launch_app()
    mark_session("phone", True)


def wait_for_phone_file(name: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if phone_status() != "device":
            raise PhoneDisconnected(f"Phone disconnected while waiting for {name}")
        if adb("shell", f"ls '{PHONE_DIR}/{name}' 2>/dev/null", check=False).strip():
            return
        error = adb("shell", f"cat '{PHONE_DIR}/error.txt' 2>/dev/null", check=False).strip()
        if error:
            adb("shell", f"rm -f '{PHONE_DIR}/error.txt'", check=False)
            raise RuntimeError(f"PhotoFind reported an error:\n{error}")
        time.sleep(0.5)
    raise TimeoutError(f"PhotoFind did not produce {name} within {timeout:.0f}s")


def export_manifest() -> list[list[str]]:
    """Pauses on-phone indexing and returns the photos the phone still needs."""
    adb("shell", f"mkdir -p '{PHONE_DIR}' && rm -f '{PHONE_DIR}/manifest.tsv' '{PHONE_DIR}'/*.ok")
    broadcast("com.photofind.EXPORT")
    wait_for_phone_file("manifest.tsv", timeout=300)
    local = WORK / "manifest.tsv"
    adb("pull", f"{PHONE_DIR}/manifest.tsv", str(local))
    lines = local.read_text(encoding="utf-8").splitlines()
    return [line.split("\t") for line in lines if line.strip()]


def flush_spool(on_log: Callable[[str], None], batch_size: int = 400) -> int:
    """Imports spooled embeddings. Sending the same photo twice just replaces it."""
    sent = 0
    while True:
        lines = spool_lines()
        if not lines:
            return sent
        chunk, rest = lines[:batch_size], lines[batch_size:]
        name = f"batch-{time.strftime('%Y%m%d-%H%M%S')}-{sent:05d}.tsv"
        local = WORK / name
        local.write_text("\n".join(chunk) + "\n", encoding="utf-8")
        try:
            adb("push", str(local), f"{PHONE_DIR}/{name}")
            broadcast("com.photofind.IMPORT", "--es", "file", name)
            wait_for_phone_file(f"{name}.ok", timeout=180)
        finally:
            local.unlink(missing_ok=True)
        spool_replace(rest)
        sent += len(chunk)
        on_log(f"Saved {sent} scanned photo{'s' if sent != 1 else ''} on the phone")
    return sent


def prepare(data: bytes) -> tuple[bytes, Image.Image]:
    """Upright, downscaled PNG (mirroring the app's decodeForEmbedding) plus a small preview."""
    image = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")
    scale = MAX_SIDE / max(image.size)
    if scale < 1:
        size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
        image = image.resize(size, Image.Resampling.LANCZOS)
    out = io.BytesIO()
    image.save(out, format="PNG", compress_level=1)
    preview = image.copy()
    preview.thumbnail((320, 320))
    return out.getvalue(), preview


def _put(out: "queue.Queue[tuple | None]", item, stop: threading.Event, pause: threading.Event) -> None:
    """Queue an item unless the scan is stopping. A full queue must not deadlock the reader."""
    while not stop.is_set():
        try:
            out.put(item, timeout=0.5)
            return
        except queue.Full:
            if pause.is_set() and item is not None:
                return
    if item is None:
        try:
            out.put_nowait(None)
        except queue.Full:
            pass


def stream_batch(
    paths: list[str],
    pool: ThreadPoolExecutor,
    out: "queue.Queue[tuple | None]",
    stop: threading.Event,
    pause: threading.Event,
) -> None:
    """Streams the batch off the phone as one tar and queues a decode job per photo."""
    proc: subprocess.Popen | None = None
    try:
        listing = WORK / "batch.txt"
        listing.write_bytes(("\n".join(paths) + "\n").encode("utf-8"))
        adb("push", str(listing), LIST_ON_PHONE)
        proc = subprocess.Popen(
            [find_adb(), "exec-out", f"tar -cf - -T {LIST_ON_PHONE} 2>/dev/null"],
            stdout=subprocess.PIPE,
            creationflags=NO_WINDOW,
        )
        with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
            for member in archive:
                if stop.is_set() or pause.is_set():
                    break
                if not member.isfile():
                    continue
                extracted = archive.extractfile(member)
                if extracted is None:
                    continue
                _put(out, ("/" + member.name, pool.submit(prepare, extracted.read())), stop, pause)
    except PhoneDisconnected as e:
        _put(out, ("error", e), stop, pause)
    except Exception as e:
        if phone_status() != "device":
            _put(out, ("error", PhoneDisconnected(str(e))), stop, pause)
        else:
            _put(out, ("error", e), stop, pause)
    finally:
        if proc is not None:
            proc.kill()
            proc.wait()
        _put(out, None, stop, pause)


def load_engine(on_log: Callable[[str], None]):
    from litert_lm import Backend, EmbeddingEngine

    cache = str(WORK / "cache")
    (WORK / "cache").mkdir(parents=True, exist_ok=True)
    errors = []
    for name, backend in (("GPU", Backend.GPU()), ("CPU", Backend.CPU())):
        try:
            on_log("Loading the model on the GPU…" if name == "GPU" else "Loading the model on the CPU…")
            engine = EmbeddingEngine(
                str(MODEL), backend=backend, vision_backend=backend, cache_dir=cache
            )
            if name == "CPU" and errors:
                on_log("The graphics card can't run this model, so the processor is doing it.")
            return engine, name
        except Exception as e:
            errors.append(f"{name}: {e}")
            on_log(f"Couldn't load the model on the {name}: {e}")
    raise RuntimeError("Couldn't load the model.\n" + "\n".join(errors))


def open_cpu_engine():
    from litert_lm import Backend, EmbeddingEngine

    return EmbeddingEngine(
        str(MODEL),
        backend=Backend.CPU(),
        vision_backend=Backend.CPU(),
        cache_dir=str(WORK / "cache"),
    )


def run(
    on_progress: Callable[[Progress], None],
    on_log: Callable[[str], None],
    stop: threading.Event,
    pause: threading.Event | None = None,
    batch_size: int = 400,
    limit: int = 0,
) -> Progress:
    """Indexes everything the phone still needs. Pause or a lost cable both continue later."""
    from litert_lm import Content, EmbeddingOptions

    if pause is None:
        pause = threading.Event()
    if not MODEL.is_file():
        raise RuntimeError(f"Model not found:\n{MODEL}")
    WORK.mkdir(parents=True, exist_ok=True)
    mark_session("pc", False)

    on_progress(Progress("Loading the model…"))
    engine, backend = load_engine(on_log)
    options = EmbeddingOptions(normalize=True)
    on_log(
        f"The model is running on the {backend}. "
        "You can pause, or unplug the phone — plug it back in and the scan continues."
    )
    gpu_dropped = False

    started = time.monotonic()
    saved = 0
    skipped = 0
    total = 0
    listed = False
    remaining: list[list[str]] = []
    progress = Progress("Indexing", backend=backend)
    told_wait = False
    gpu_dropped = False

    def snapshot(stage: str, current: str = "", preview: Image.Image | None = None) -> Progress:
        done = saved + skipped + len(spool_lines())
        elapsed = time.monotonic() - started
        rate = done / elapsed if elapsed else 0.0
        left = max(0, total - done)
        return Progress(
            stage=stage,
            done=done,
            total=total,
            skipped=skipped,
            rate=rate,
            eta_seconds=(left / rate) if rate else 0.0,
            elapsed_seconds=elapsed,
            current=current,
            preview=preview,
            backend=backend,
        )

    def embed(image: bytes):
        """GPU stays in use unless a photo it can't scan is one the CPU can."""
        nonlocal engine, backend, gpu_dropped
        try:
            return engine.compute_embedding(Content.ImageBytes(image), options).embedding
        except Exception as first:
            if backend != "GPU" or gpu_dropped:
                raise
            cpu = open_cpu_engine()
            try:
                vector = cpu.compute_embedding(Content.ImageBytes(image), options).embedding
            except Exception:
                try:
                    cpu.close()
                except Exception:
                    pass
                raise first
            gpu_dropped = True
            try:
                engine.close()
            except Exception:
                pass
            engine = cpu
            backend = "CPU"
            progress.backend = "CPU"
            on_log("The graphics card couldn't scan a photo, so this continues on the processor.")
            return vector

    def consume(batch: list[list[str]], finished: set[str]) -> None:
        nonlocal skipped
        by_key = {row[0]: row for row in batch}
        jobs: "queue.Queue[tuple | None]" = queue.Queue(maxsize=48)
        reader = threading.Thread(
            target=stream_batch, args=(list(by_key), pool, jobs, stop, pause), daemon=True
        )
        reader.start()
        pending_error = None
        try:
            while True:
                try:
                    job = jobs.get(timeout=0.5)
                except queue.Empty:
                    if not reader.is_alive() and jobs.empty():
                        break
                    continue
                if job is None:
                    break
                if job[0] == "error":
                    pending_error = job[1]
                    continue
                key, future = job
                row = by_key.get(key)
                if row is None:
                    continue
                try:
                    image, preview = future.result()
                    vector = embed(image)
                except Exception as e:
                    on_log(f"Skipped {key}: {e}")
                    skipped += 1
                    finished.add(key)
                    on_progress(snapshot("Indexing", current=key))
                    continue
                packed = base64.b64encode(np.asarray(vector, dtype="<f4").tobytes()).decode("ascii")
                spool_append("\t".join([*row[:5], packed]))
                finished.add(key)
                on_progress(snapshot("Indexing", current=key, preview=preview))
        finally:
            reader.join()
        if pending_error is not None:
            raise pending_error

    try:
        with ThreadPoolExecutor(max_workers=6) as pool:
            while not stop.is_set():
                if pause.is_set():
                    try:
                        if phone_ready():
                            saved += flush_spool(on_log, batch_size)
                    except PhoneDisconnected:
                        on_log("The phone was unplugged. Plug it back in and the scan continues.")
                    on_progress(snapshot("Paused"))
                    while pause.is_set() and not stop.is_set():
                        time.sleep(0.3)
                    continue
                if not phone_ready():
                    if not told_wait:
                        told_wait = True
                        status = phone_status()
                        if status == "unauthorized":
                            on_log("Unlock the phone and tap Allow on the USB debugging prompt.")
                        elif status == "multiple":
                            on_log("More than one phone is connected. Unplug the others.")
                        elif status == "device":
                            on_log("PhotoFind is not installed on the phone.")
                        else:
                            on_log(
                                "The phone is unplugged. Plug it back in — "
                                "photos already saved are not scanned again."
                            )
                    on_progress(snapshot("Waiting for the phone"))
                    time.sleep(2)
                    continue
                told_wait = False
                finished: set[str] = set()
                try:
                    saved += flush_spool(on_log, batch_size)
                    if not listed:
                        on_progress(snapshot("Asking PhotoFind which photos it still needs…"))
                        manifest = export_manifest()
                        if limit:
                            manifest = manifest[:limit]
                        remaining = manifest
                        listed = True
                        total = len(remaining) + saved + skipped
                        on_log(
                            f"{len(remaining)} photos to index"
                            + (f" ({saved} already saved)" if saved else "")
                        )
                    if not remaining:
                        break
                    consume(remaining[:batch_size], finished)
                    on_progress(snapshot("Indexing"))
                except PhoneDisconnected:
                    told_wait = False
                    on_log("The phone was unplugged. Plug it back in and the scan continues.")
                finally:
                    if finished:
                        remaining = [row for row in remaining if row[0] not in finished]

        if phone_ready() and spool_lines():
            try:
                saved += flush_spool(on_log, batch_size)
            except PhoneDisconnected:
                on_log("Couldn't send the last photos. They'll go across when the phone is back.")

        progress = snapshot("Indexing")
        if stop.is_set() or remaining:
            progress.stage = "Paused" if stop.is_set() else "Waiting for the phone"
            mark_session("pc", False)
            on_progress(progress)
            on_log("Paused. Open PhotoFind again to continue from this photo.")
            return progress

        try:
            resume_phone()
        except PhoneDisconnected:
            mark_session("pc", False)
            progress.stage = "Waiting for the phone"
            on_progress(progress)
            on_log("All photos are saved. Plug the phone back in and continue, so it can look for faces.")
            return progress
        mark_session("pc", True)
        progress.stage = "Done" if saved or skipped else "Phone is up to date"
        progress.preview = None
        on_progress(progress)
        if skipped:
            on_log(f"Done. {skipped} photos could not be read; the phone will try those.")
        elif saved:
            on_log("Done. The phone is looking for faces in the new photos.")
        else:
            on_log("The phone is up to date. It will look for any faces still missing.")
        return progress
    finally:
        engine.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--batch", type=int, default=400, help="photos per import batch")
    parser.add_argument("--limit", type=int, default=0, help="only index this many photos (for testing)")
    args = parser.parse_args()

    last_line = [0.0]

    def show(p: Progress) -> None:
        if p.stage != "Indexing":
            print(p.stage)
        elif time.monotonic() - last_line[0] > 5:
            last_line[0] = time.monotonic()
            where = f" on {p.backend}" if p.backend else ""
            print(f"  {p.done}/{p.total}  {p.rate:.1f} photos/s{where}  ~{p.eta_seconds / 60:.0f} min left")

    run(show, print, threading.Event(), batch_size=args.batch, limit=args.limit)


if __name__ == "__main__":
    main()
