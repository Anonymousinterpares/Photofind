"""Indexes the phone's photos on this PC's GPU and hands the embeddings back to PhotoFind.

Usage (phone connected over USB, PhotoFind installed):
    tools\\.venv\\Scripts\\python.exe tools\\pc_index.py      command line
    tools\\PhotoFind-PC.bat                                  window (pc_gui.py)

Photos are streamed off the phone in batches, embedded with the same EmbeddingGemma 2 model file
the phone uses, and imported batch by batch, so an interrupted run resumes where it stopped.
"""

import argparse
import base64
import io
import queue
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
WORK = ROOT / "models" / "pc_index"
ADB = str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe")
PHONE_DIR = "/sdcard/Download/PhotoFind"
RECEIVER = "com.photofind/.sync.PcSyncReceiver"
LIST_ON_PHONE = "/data/local/tmp/photofind_batch.txt"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

# Must match decodeForEmbedding's maxSide in the app.
MAX_SIDE = 768


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


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        [ADB, *args], capture_output=True, text=True, encoding="utf-8", creationflags=NO_WINDOW
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def phone_status() -> str:
    """'device', 'unauthorized', 'offline' or 'missing'."""
    try:
        lines = adb("devices", check=False).splitlines()[1:]
    except FileNotFoundError:
        return "missing"
    states = [line.split()[1] for line in lines if len(line.split()) >= 2]
    if "device" in states:
        return "device"
    return states[0] if states else "missing"


def phone_model() -> str:
    return adb("shell", "getprop", "ro.product.model", check=False).strip()


def photofind_installed() -> bool:
    return "com.photofind" in adb("shell", "pm", "list", "packages", "com.photofind", check=False)


def broadcast(action: str, *extras: str) -> None:
    adb("shell", "am", "broadcast", "-a", action, "-n", RECEIVER, *extras)


def wait_for_phone_file(name: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
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


def resume_phone() -> None:
    adb("shell", f"rm -f '{PHONE_DIR}'/*.ok '{PHONE_DIR}/manifest.tsv' {LIST_ON_PHONE}", check=False)
    broadcast("com.photofind.RESUME")


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


def stream_batch(
    paths: list[str],
    pool: ThreadPoolExecutor,
    out: "queue.Queue[tuple[str, Future] | None]",
    stop: threading.Event,
) -> None:
    """Streams the batch off the phone as one tar and queues a decode job per photo."""
    listing = WORK / "batch.txt"
    listing.write_bytes(("\n".join(paths) + "\n").encode("utf-8"))
    adb("push", str(listing), LIST_ON_PHONE)
    proc = subprocess.Popen(
        [ADB, "exec-out", f"tar -cf - -T {LIST_ON_PHONE} 2>/dev/null"],
        stdout=subprocess.PIPE,
        creationflags=NO_WINDOW,
    )
    try:
        with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
            for member in archive:
                if stop.is_set():
                    break
                if not member.isfile():
                    continue
                data = archive.extractfile(member).read()
                out.put(("/" + member.name, pool.submit(prepare, data)))
    finally:
        proc.kill()
        proc.wait()
        out.put(None)


def run(
    on_progress: Callable[[Progress], None],
    on_log: Callable[[str], None],
    stop: threading.Event,
    batch_size: int = 400,
    limit: int = 0,
) -> Progress:
    """Indexes everything the phone still needs; returns the final progress."""
    from litert_lm import Backend, Content, EmbeddingEngine, EmbeddingOptions

    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / "cache").mkdir(exist_ok=True)

    on_progress(Progress("Asking PhotoFind which photos it still needs…"))
    manifest = export_manifest()
    if limit:
        manifest = manifest[:limit]
    on_log(f"{len(manifest)} photos to index")
    if not manifest:
        resume_phone()
        result = Progress("Phone is up to date")
        on_progress(result)
        return result

    on_progress(Progress("Loading the model on the GPU…", total=len(manifest)))
    gpu = Backend.GPU()
    engine = EmbeddingEngine(str(MODEL), backend=gpu, vision_backend=gpu, cache_dir=str(WORK / "cache"))
    options = EmbeddingOptions(normalize=True)

    started = time.monotonic()
    progress = Progress("Indexing", total=len(manifest))
    try:
        with ThreadPoolExecutor(max_workers=6) as pool:
            for batch_no, start in enumerate(range(0, len(manifest), batch_size), start=1):
                if stop.is_set():
                    break
                batch = manifest[start : start + batch_size]
                by_key = {row[0]: row for row in batch}
                jobs: "queue.Queue[tuple[str, Future] | None]" = queue.Queue(maxsize=48)
                reader = threading.Thread(
                    target=stream_batch, args=(list(by_key), pool, jobs, stop), daemon=True
                )
                reader.start()

                lines = []
                while (job := jobs.get()) is not None:
                    key, future = job
                    row = by_key.get(key)
                    if row is None or stop.is_set():
                        continue
                    try:
                        image, preview = future.result()
                        vector = engine.compute_embedding(Content.ImageBytes(image), options).embedding
                    except Exception as e:  # unreadable or unsupported file: the phone will retry it
                        on_log(f"Skipped {key}: {e}")
                        progress.skipped += 1
                        continue
                    packed = base64.b64encode(np.asarray(vector, dtype="<f4").tobytes()).decode("ascii")
                    lines.append("\t".join([*row[:5], packed]))

                    elapsed = time.monotonic() - started
                    in_flight = progress.done + len(lines)
                    progress.rate = in_flight / elapsed if elapsed else 0
                    progress.eta_seconds = (len(manifest) - in_flight) / progress.rate if progress.rate else 0
                    progress.elapsed_seconds = elapsed
                    progress.current = key
                    progress.preview = preview
                    on_progress(Progress(**{**progress.__dict__, "done": in_flight}))
                reader.join()

                if lines:
                    progress.stage = f"Sending batch {batch_no} to the phone…"
                    on_progress(Progress(**{**progress.__dict__, "preview": None}))
                    name = f"batch-{batch_no:04d}.tsv"
                    local = WORK / name
                    local.write_text("\n".join(lines) + "\n", encoding="utf-8")
                    adb("push", str(local), f"{PHONE_DIR}/{name}")
                    broadcast("com.photofind.IMPORT", "--es", "file", name)
                    wait_for_phone_file(f"{name}.ok", timeout=120)
                    local.unlink()
                    progress.done += len(lines)
                    progress.stage = "Indexing"
                    on_log(f"Batch {batch_no}: {progress.done}/{len(manifest)} saved on the phone")
    finally:
        engine.close()
        resume_phone()

    progress.elapsed_seconds = time.monotonic() - started
    progress.stage = "Stopped" if stop.is_set() else "Done"
    progress.preview = None
    on_progress(progress)
    on_log(
        f"{progress.stage}: {progress.done} photos indexed in {progress.elapsed_seconds / 60:.1f} min"
        f" ({progress.skipped} skipped)."
    )
    return progress


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
            print(f"  {p.done}/{p.total}  {p.rate:.1f} photos/s  ~{p.eta_seconds / 60:.0f} min left")

    run(show, print, threading.Event(), batch_size=args.batch, limit=args.limit)


if __name__ == "__main__":
    main()
