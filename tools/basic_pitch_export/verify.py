"""
Confirms the exported ONNX model's input/output shapes and output ordering match what
BasicPitchTranscriber.kt assumes -- run this after any re-export (e.g. a future basic-pitch
version) before trusting it, since the output order (onset, note, contour) is inferred from
basic_pitch/inference.py's name mapping, not from the ONNX graph itself.
"""
import numpy as np
import onnxruntime as ort

MODEL_PATH = "basic_pitch_icassp_2022.onnx"
AUDIO_N_SAMPLES = 43844  # basic_pitch.constants.AUDIO_N_SAMPLES
INPUT_NAME = "serving_default_input_2:0"


def main():
    session = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])

    inputs = session.get_inputs()
    assert len(inputs) == 1, inputs
    assert inputs[0].name == INPUT_NAME, inputs[0].name
    assert inputs[0].shape[1:] == [AUDIO_N_SAMPLES, 1], inputs[0].shape
    print(f"input ok: {inputs[0].name} {inputs[0].shape}")

    outputs = session.get_outputs()
    assert [o.name for o in outputs] == [
        "StatefulPartitionedCall:2",
        "StatefulPartitionedCall:1",
        "StatefulPartitionedCall:0",
    ], [o.name for o in outputs]
    assert outputs[0].shape[1:] == [172, 88], outputs[0].shape  # onset
    assert outputs[1].shape[1:] == [172, 88], outputs[1].shape  # note/frame
    assert outputs[2].shape[1:] == [172, 264], outputs[2].shape  # contour
    print("output order ok: [onset(172,88), note(172,88), contour(172,264)]")

    x = np.random.default_rng(0).standard_normal((1, AUDIO_N_SAMPLES, 1), dtype=np.float32)
    onset, note, contour = session.run(None, {INPUT_NAME: x})
    for name, arr in [("onset", onset), ("note", note), ("contour", contour)]:
        assert 0.0 <= arr.min() and arr.max() <= 1.0, (name, arr.min(), arr.max())
    print("output ranges ok (all within [0, 1], consistent with sigmoid activations)")
    print("OK")


if __name__ == "__main__":
    main()
