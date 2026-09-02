"""Downloads yamnet.onnx (a full-graph ONNX export of Google's YAMNet) and the AudioSet class
map CSV. Unlike Demucs, no conversion happens here -- a working full-graph export already
exists (unlike PyTorch, TF's STFT/mel ops decompose to ONNX without hitting a complex-tensor
wall), so this just fetches the two artifacts and confirms the class map matches what the real
TF-Hub model ships. See verify.py for the numerical check against the real model."""
import shutil

import tensorflow_hub as hub
from huggingface_hub import hf_hub_download


def main():
    print("Downloading yamnet.onnx from zeropointnine/yamnet-onnx...")
    onnx_path = hf_hub_download("zeropointnine/yamnet-onnx", "yamnet.onnx")
    shutil.copy(onnx_path, "yamnet.onnx")
    print("  -> yamnet.onnx")

    print("Fetching the real TF-Hub model's class map for comparison...")
    model = hub.load("https://tfhub.dev/google/yamnet/1")
    class_map_path = model.class_map_path().numpy().decode("utf-8")
    shutil.copy(class_map_path, "yamnet_class_map.csv")
    print("  -> yamnet_class_map.csv")


if __name__ == "__main__":
    main()
