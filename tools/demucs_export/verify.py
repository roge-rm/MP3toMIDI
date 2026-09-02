"""
Checks htdemucs_6s.onnx against the original PyTorch model on random input, so a future
re-export (different demucs/torch version, etc.) can be confirmed not to have silently
broken numerics before it ships. Run after export.py, from the same venv.
"""
import numpy as np
import onnxruntime as ort
import torch
from demucs.pretrained import get_model

from onnx_patches import patch_htdemucs_for_onnx_export

MODEL_NAME = "htdemucs_6s"
ONNX_PATH = "htdemucs_6s.onnx"
TOLERANCE = 2e-3  # float32 conv-DFT vs FFT accumulation-order noise floor, see real_stft.py


def main():
    torch.manual_seed(0)

    bag = get_model(MODEL_NAME)
    model = bag.models[0]
    model.eval()
    patch_htdemucs_for_onnx_export(model)

    segment_length = int(model.samplerate * model.segment)
    x = torch.randn(1, model.audio_channels, segment_length)

    with torch.no_grad():
        torch_out = model(x).numpy()

    session = ort.InferenceSession(ONNX_PATH, providers=["CPUExecutionProvider"])
    onnx_out = session.run(None, {"mixture": x.numpy()})[0]

    diff = np.abs(torch_out - onnx_out)
    print(f"max abs diff:  {diff.max():.6f}")
    print(f"mean abs diff: {diff.mean():.6f}")
    print(f"output scale (mean abs): {np.abs(torch_out).mean():.6f}")

    assert np.allclose(torch_out, onnx_out, atol=TOLERANCE), "ONNX output diverges from PyTorch"
    print("OK")


if __name__ == "__main__":
    main()
