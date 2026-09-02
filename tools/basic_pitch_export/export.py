"""
Basic Pitch (Spotify's ICASSP 2022 polyphonic transcription model) already ships a
pre-converted ONNX file inside the `basic-pitch` PyPI package -- no export/conversion needed,
unlike Demucs. This just locates it and copies it out for publishing.

    uv venv --python 3.11 venv
    uv pip install -p venv/bin/python basic-pitch onnx onnxruntime
    venv/bin/python export.py   # -> basic_pitch_icassp_2022.onnx
    venv/bin/python verify.py   # confirms output shapes/order match what BasicPitchTranscriber expects
"""
import shutil
from pathlib import Path

import basic_pitch

OUTPUT_PATH = "basic_pitch_icassp_2022.onnx"


def main():
    source = Path(basic_pitch.__file__).parent / "saved_models" / "icassp_2022" / "nmp.onnx"
    shutil.copy(source, OUTPUT_PATH)
    print(f"Copied {source} -> {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
