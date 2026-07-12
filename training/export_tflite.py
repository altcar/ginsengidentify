"""
export_tflite.py — Keras 模型转 TFLite（float16 + 动态范围量化备选）

输出: model/ginseng_model.tflite        (默认 float16 量化)
       model/ginseng_model_full.tflite  (float32，对照)

Android 端集成使用 ginseng_model.tflite。
"""
import argparse
from pathlib import Path

import tensorflow as tf

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "model"


def export(keras_path: Path, tflite_path: Path, quantize: str = "float16"):
    model = tf.keras.models.load_model(str(keras_path))
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    if quantize == "float16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    elif quantize == "int8":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        # 注: 真正的 int8 全量化需要代表集，这里走 dynamic range
    else:
        converter.optimizations = []
    tflite_model = converter.convert()
    tflite_path.write_bytes(tflite_model)
    print(f"[{quantize}] {tflite_path} -> {len(tflite_model)/1024:.1f} KB")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input", default=str(MODEL / "ginseng_model.keras"),
    )
    parser.add_argument("--output", default=str(MODEL / "ginseng_model.tflite"))
    parser.add_argument("--quantize", default="float16",
                        choices=["none", "float16", "int8"])
    args = parser.parse_args()
    export(Path(args.input), Path(args.output), args.quantize)
    print("\n完成。把 model/ginseng_model.tflite + labels.txt 拷到 android app 的 assets 即可。")


if __name__ == "__main__":
    main()
