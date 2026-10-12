"""Indexes the phone's photos on this PC's GPU and hands the embeddings back to PhotoFind.

Usage (phone connected over USB, PhotoFind installed):
    tools\\.venv\\Scripts\\python.exe tools\\pc_index.py

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
from pathlib import Path

import numpy as np
import pillow_heif
from litert_lm import Backend, Content, EmbeddingEngine, EmbeddingOptions
from PIL import Image, ImageOps

pillow_heif.register_heif_opener()

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "models" / "embeddinggemma-2-text-vision-440m.litertlm"
WORK = ROOT / "models" / "pc_index"
ADB = str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe")
PHONE_DIR = "/sdcard/Download/PhotoFind"
RECEIVER = "com.photofind/.sync.PcSyncReceiver"
LIST_ON_PHONE = "/data/local/tmp/photofind_batch.txt"

# Must match decodeForEmbedding's maxSide in the app.
MAX_SIDE = 768


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8")
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


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
    adb("shell", f"mkdir -p '{PHONE_DIR}' && rm -f '{PHONE_DIR}/manifest.tsv' '{PHONE_DIR}'/*.ok")
    broadcast("com.photofind.EXPORT")
    wait_for_phone_file("manifest.tsv", timeout=300)
    local = WORK / "manifest.tsv"
    adb("pull", f"{PHONE_DIR}/manifest.tsv", str(local))
    lines = local.read_text(encoding="utf-8").splitlines()
    return [line.split("\t") for line in lines if line.strip()]


def prepare(data: bytes) -> bytes:
    """Upright, downscaled PNG, mirroring the app's decodeForEmbedding."""
    image = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")
    scale = MAX_SIDE / max(image.size)
    if scale < 1:
        size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
        image = image.resize(size, Image.Resampling.LANCZOS)
    out = io.BytesIO()
    image.save(out, format="PNG", compress_level=1)
    return out.getvalue()


def stream_batch(paths: list[str], pool: ThreadPoolExecutor, out: "queue.Queue[tuple[str, Future] | None]") -> None:
    """Streams the batch off the phone as one tar and queues a decode job per photo."""
    listing = WORK / "batch.txt"
    listing.write_bytes(("\n".join(paths) + "\n").encode("utf-8"))
    adb("push", str(listing), LIST_ON_PHONE)
    proc = subprocess.Popen(
        [ADB, "exec-out", f"tar -cf - -T {LIST_ON_PHONE} 2>/dev/null"],
        stdout=subprocess.PIPE,
    )
    try:
        with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
            for member in archive:
                if not member.isfile():
                    continue
                data = archive.extractfile(member).read()
                out.put(("/" + member.name, pool.submit(prepare, data)))
    finally:
        proc.wait()
        out.put(None)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--batch", type=int, default=400, help="photos per import batch")
    parser.add_argument("--limit", type=int, default=0, help="only index this many photos (for testing)")
    args = parser.parse_args()

    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / "cache").mkdir(exist_ok=True)

    print("Asking PhotoFind for the photos it still needs…")
    manifest = export_manifest()
    if args.limit:
        manifest = manifest[: args.limit]
    print(f"{len(manifest)} photos to index")
    if not manifest:
        broadcast("com.photofind.RESUME")
        return

    gpu = Backend.GPU()
    engine = EmbeddingEngine(str(MODEL), backend=gpu, vision_backend=gpu, cache_dir=str(WORK / "cache"))
    options = EmbeddingOptions(normalize=True)

    started = time.monotonic()
    done = failed = 0
    with ThreadPoolExecutor(max_workers=6) as pool:
        for batch_no, start in enumerate(range(0, len(manifest), args.batch), start=1):
            batch = manifest[start : start + args.batch]
            by_key = {row[0]: row for row in batch}
            jobs: "queue.Queue[tuple[str, Future] | None]" = queue.Queue(maxsize=48)
            reader = threading.Thread(target=stream_batch, args=(list(by_key), pool, jobs), daemon=True)
            reader.start()

            lines = []
            while (job := jobs.get()) is not None:
                key, future = job
                row = by_key.get(key)
                if row is None:
                    continue
                try:
                    image = future.result()
                    vector = engine.compute_embedding(Content.ImageBytes(image), options).embedding
                except Exception as e:  # unreadable or unsupported file: the phone will retry it
                    print(f"  skipped {key}: {e}")
                    failed += 1
                    continue
                packed = base64.b64encode(np.asarray(vector, dtype="<f4").tobytes()).decode("ascii")
                lines.append("\t".join([*row[:5], packed]))
            reader.join()

            name = f"batch-{batch_no:04d}.tsv"
            local = WORK / name
            local.write_text("\n".join(lines) + "\n", encoding="utf-8")
            adb("push", str(local), f"{PHONE_DIR}/{name}")
            broadcast("com.photofind.IMPORT", "--es", "file", name)
            wait_for_phone_file(f"{name}.ok", timeout=120)
            local.unlink()

            done += len(lines)
            elapsed = time.monotonic() - started
            rate = done / elapsed if elapsed else 0
            remaining = (len(manifest) - start - len(batch)) / rate if rate else 0
            print(
                f"batch {batch_no}: {done}/{len(manifest)} imported, {rate:.1f} photos/s, "
                f"~{remaining / 60:.0f} min left"
            )

    engine.close()
    adb("shell", f"rm -f '{PHONE_DIR}'/*.ok '{PHONE_DIR}/manifest.tsv' {LIST_ON_PHONE}", check=False)
    broadcast("com.photofind.RESUME")
    minutes = (time.monotonic() - started) / 60
    print(f"Done: {done} photos indexed on the PC in {minutes:.1f} min ({failed} skipped).")


if __name__ == "__main__":
    main()
