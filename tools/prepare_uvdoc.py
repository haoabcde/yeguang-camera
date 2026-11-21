"""Extract UVDoc's unchanged 45x31 grid predictor for bounded Android inference.

Requires onnx==1.20.1. No conversion, quantization, training, or weight changes.
The official full-resolution GridSample is replaced in BookDewarp.java with
align-corners grid interpolation and bounded OpenCV remap strips.
"""
import argparse
import hashlib
from pathlib import Path
from urllib.request import urlopen

import onnx

REVISION = "3bcf535371727d11e783101f79a504c68848aae3"
URL = f"https://huggingface.co/PaddlePaddle/UVDoc_onnx/resolve/{REVISION}/inference.onnx"
SOURCE_SHA256 = "54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6"
OUTPUT_SHA256 = "ac224193ca850105b08a8ac249b4e088200d283265169a7f87338d056eb4f569"
GRID_NAME = "p2o.pd_op.add.41.0"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("build/rc12-book/uvdoc.onnx"))
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/vision/uvdoc.onnx"))
    args = parser.parse_args()
    if not args.source.exists():
        args.source.parent.mkdir(parents=True, exist_ok=True)
        with urlopen(URL, timeout=60) as response:
            args.source.write_bytes(response.read())
    actual = hashlib.sha256(args.source.read_bytes()).hexdigest()
    if actual != SOURCE_SHA256:
        raise ValueError(f"Official source SHA-256 mismatch: {actual}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.utils.extract_model(str(args.source), str(args.output), ["image"], [GRID_NAME])
    model = onnx.load(args.output)
    # Keep the original align-corners Resize node. At this fixed input size it is
    # identity. The app performs the identical input sampling before inference.
    for value, dimensions in [(model.graph.input[0], [1, 3, 712, 488]),
                              (model.graph.output[0], [1, 2, 45, 31])]:
        for dim, dimension in zip(value.type.tensor_type.shape.dim, dimensions):
            dim.ClearField("dim_param")
            dim.dim_value = dimension
    onnx.checker.check_model(model)
    onnx.save(model, args.output)
    actual = hashlib.sha256(args.output.read_bytes()).hexdigest()
    if actual != OUTPUT_SHA256:
        raise ValueError(f"Derived model SHA-256 mismatch ({actual}); use onnx==1.20.1")
    print(f"{args.output}: {args.output.stat().st_size} bytes")
    print("SHA-256", actual)


if __name__ == "__main__":
    main()
