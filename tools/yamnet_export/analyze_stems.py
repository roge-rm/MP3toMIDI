"""Runs yamnet.onnx on each captured real stem PCM and prints the top-scoring classes, to
calibrate TimbreClassifier's AudioSet-class -> GM-program mapping and confidence threshold
against real audio rather than guessing."""
import sys

import numpy as np
import onnxruntime as ort

SAMPLE_RATE_IN = 44100
SAMPLE_RATE_OUT = 16000


def load_class_names():
    names = {}
    with open("yamnet_class_map.csv") as f:
        next(f)
        for line in f:
            idx, mid, name = line.rstrip("\n").split(",", 2)
            names[int(idx)] = name.strip('"')
    return names


def load_mono_16k(path):
    raw = np.fromfile(path, dtype="<f4")
    stereo = raw.reshape(-1, 2)
    mono = stereo.mean(axis=1)
    n_out = int(len(mono) * SAMPLE_RATE_OUT / SAMPLE_RATE_IN)
    x_old = np.linspace(0, 1, len(mono), endpoint=False)
    x_new = np.linspace(0, 1, n_out, endpoint=False)
    return np.interp(x_new, x_old, mono).astype(np.float32)


def main():
    class_names = load_class_names()
    session = ort.InferenceSession("yamnet.onnx")

    for path in sys.argv[1:]:
        label = path.split("debug_")[-1].replace(".pcm", "")
        waveform = load_mono_16k(path)
        print(f"\n=== {label} ({len(waveform)/SAMPLE_RATE_OUT:.1f}s @ 16kHz) ===")

        outputs = session.run(None, {"waveform": waveform})
        scores = outputs[0]  # [N, 521]
        mean_scores = scores.mean(axis=0)
        max_scores = scores.max(axis=0)

        top_mean = np.argsort(mean_scores)[::-1][:15]
        print("Top 15 by mean score across the whole stem:")
        for idx in top_mean:
            print(f"  {mean_scores[idx]:.4f}  (max {max_scores[idx]:.4f})  {class_names[idx]}")


if __name__ == "__main__":
    main()
