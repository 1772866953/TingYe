from __future__ import annotations

import hashlib
import json
import os
import struct
import zipfile
from pathlib import Path

FORMAT = "tingye-sentence-sync"
SCHEMA = 1
SAMPLE = 65536


def fingerprint(path: str | Path) -> str:
    path = Path(path)
    size = path.stat().st_size
    digest = hashlib.sha256(f"tingye-audio-v1\n{size}\n".encode("ascii"))
    positions = sorted({0, max(0, (size - SAMPLE) // 2), max(0, size - SAMPLE)})
    with path.open("rb") as stream:
        for position in positions:
            stream.seek(position)
            data = stream.read(SAMPLE)
            digest.update(struct.pack("<QI", position, len(data)))
            digest.update(data)
    return digest.hexdigest()


def export(path: str | Path, book, tracks: list[dict], rows, complete: bool, total_sentences: int,verification:dict|None=None) -> dict:
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    count = 0
    covered = 0
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            with archive.open("sentences.jsonl", "w") as output:
                for row in rows:
                    output.write((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
                    count += 1
                    covered += row["n1"] - row["n0"]
            manifest = dict(format=FORMAT, version=SCHEMA, canonical="utf16-bmp-letter-digit-lower-v1",
                            book=dict(title=book.title, text_sha256=book.digest, characters=len(book.text)),
                            tracks=[{k: v for k, v in t.items() if k != "path"} for t in tracks],
                            complete=complete, sentence_count=count, total_sentences=total_sentences,
                            coverage=min(1., covered / max(1, len(book.text))),
                            engine="SenseVoiceSmall/CTC-clause-v2",segmentation="punctuation-v2")
            if verification is not None:manifest['verification']=verification
            archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, separators=(",", ":")))
        os.replace(temporary, path)
        return manifest
    finally:
        temporary.unlink(missing_ok=True)
