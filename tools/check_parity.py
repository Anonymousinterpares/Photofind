"""Compares phone-made embeddings with PC-made ones for the same photos, and times the PC."""

import io
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import pillow_heif
from litert_lm import Backend, Content, EmbeddingEngine, EmbeddingOptions
from PIL import Image, ImageOps

pillow_heif.register_heif_opener()

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "models" / "embeddinggemma-2-text-vision-440m.litertlm"
WORK = ROOT / "models" / "check"
ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"


def prepare(path: Path, max_side: int) -> bytes:
    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    scale = max_side / max(image.size)
    if scale < 1:
        image = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.Resampling.LANCZOS,
        )
    out = io.BytesIO()
    image.save(out, format="PNG")
    return out.getvalue()


def main() -> None:
    max_side = int(sys.argv[1]) if len(sys.argv) > 1 else 1024
    db = sqlite3.connect(WORK / "phone.db")
    rows = db.execute(
        "SELECT key, embedding FROM photos WHERE embedding IS NOT NULL "
        "ORDER BY (key LIKE '%/DCIM/%') DESC, date_taken DESC LIMIT 8"
    ).fetchall()

    local = []
    for key, blob in rows:
        target = WORK / Path(key).name
        if not target.exists():
            subprocess.run([str(ADB), "pull", key, str(target)], check=True, capture_output=True)
        local.append((key, target, np.frombuffer(blob, dtype="<f4")))

    gpu = Backend.GPU()
    (WORK / "cache").mkdir(exist_ok=True)
    start = time.perf_counter()
    engine = EmbeddingEngine(str(MODEL), backend=gpu, vision_backend=gpu, cache_dir=str(WORK / "cache"))
    print(f"engine ready in {time.perf_counter() - start:.1f}s")
    options = EmbeddingOptions(normalize=True)

    prepared = [prepare(path, max_side) for _, path, _ in local]
    engine.compute_embedding(Content.ImageBytes(prepared[0]), options)  # warm-up

    timings = []
    for (key, _, phone), data in zip(local, prepared):
        t = time.perf_counter()
        pc = np.asarray(engine.compute_embedding(Content.ImageBytes(data), options).embedding, dtype="f4")
        timings.append(time.perf_counter() - t)
        cosine = float(np.dot(pc, phone) / (np.linalg.norm(pc) * np.linalg.norm(phone)))
        print(f"cosine {cosine:.4f}  dim {pc.size}/{phone.size}  {Path(key).parent.name}/{Path(key).name}")
    print(f"PC GPU: {1000 * np.mean(timings):.0f} ms per photo (embedding only)")

    phone_vecs = np.stack([p for _, _, p in local])
    off = phone_vecs @ phone_vecs.T
    print(f"for scale, phone-vs-phone cosine between different photos: mean {off[~np.eye(len(local), dtype=bool)].mean():.3f}")


if __name__ == "__main__":
    main()
