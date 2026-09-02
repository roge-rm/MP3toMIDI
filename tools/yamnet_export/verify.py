"""Verifies yamnet.onnx numerically matches the real TF-Hub YAMNet model on real audio."""
import numpy as np
import onnxruntime as ort
import tensorflow_hub as hub

SAMPLE_RATE = 16000


def load_test_audio():
    """A real short audio clip, not synthetic -- read a few seconds of a real drum stem PCM
    captured earlier in this project (interleaved stereo float32 @ 44100Hz) and downsample."""
    import struct
    path = "/tmp/claude-1000/-home-dan-AndroidStudioProjects-MP3toMIDI/3617a944-1e3c-4898-8978-5b7fa7aa0176/scratchpad/debug_drums.pcm"
    raw = np.fromfile(path, dtype="<f4")
    stereo = raw.reshape(-1, 2)
    mono = stereo.mean(axis=1)
    # take a 10-second clip from 30s in (well into the song, not silence)
    sr_in = 44100
    start = 30 * sr_in
    clip = mono[start:start + 10 * sr_in]
    # simple linear resample 44100 -> 16000
    n_out = int(len(clip) * SAMPLE_RATE / sr_in)
    x_old = np.linspace(0, 1, len(clip), endpoint=False)
    x_new = np.linspace(0, 1, n_out, endpoint=False)
    resampled = np.interp(x_new, x_old, clip).astype(np.float32)
    return resampled


def main():
    waveform = load_test_audio()
    print(f"Test waveform: {len(waveform)} samples ({len(waveform)/SAMPLE_RATE:.2f}s)")

    print("Running real TF-Hub YAMNet...")
    model = hub.load("https://tfhub.dev/google/yamnet/1")
    scores_tf, embeddings_tf, spectrogram_tf = model(waveform)
    scores_tf = scores_tf.numpy()
    print(f"  TF output_0 (scores) shape: {scores_tf.shape}")

    print("Running ONNX export...")
    session = ort.InferenceSession("yamnet.onnx")
    outputs = session.run(None, {"waveform": waveform})
    scores_onnx = outputs[0]
    print(f"  ONNX output_0 (scores) shape: {scores_onnx.shape}")

    assert scores_tf.shape == scores_onnx.shape, "shape mismatch between TF and ONNX outputs"

    diff = np.abs(scores_tf - scores_onnx)
    print(f"\nScore diff: max={diff.max():.6f} mean={diff.mean():.6f}")

    # Compare top-5 predicted classes per frame (aggregated) to make sure the ranking survives,
    # not just the raw numeric closeness.
    mean_tf = scores_tf.mean(axis=0)
    mean_onnx = scores_onnx.mean(axis=0)
    top5_tf = np.argsort(mean_tf)[::-1][:5]
    top5_onnx = np.argsort(mean_onnx)[::-1][:5]
    print(f"Top-5 classes (TF):   {top5_tf.tolist()}")
    print(f"Top-5 classes (ONNX): {top5_onnx.tolist()}")

    assert diff.max() < 1e-3, f"ONNX export diverges from TF-Hub reference: max diff {diff.max()}"
    assert list(top5_tf) == list(top5_onnx), "top-5 class ranking differs between TF and ONNX"
    print("\nOK: ONNX export matches the real TF-Hub YAMNet model.")


if __name__ == "__main__":
    main()
