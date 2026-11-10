"""
export_tflite.py — Keras -> TFLite (float16)

关键点:
  - 重新接一张「只用推理层」的图（不带上数据增强），保证 TFLite 图干净、
    不会因为 random op 转换失败，也避免把训练态算子带到端上
  - 权重和训练时完全共享，不做任何重新训练
  - 明确只允许 TFLITE_BUILTINS，转换阶段就暴露不支持的算子，而不是等到
    手机上一跑就崩
  - 输入约定: float32 [1,224,224,3]，RGB，范围 [0,255]
    （preprocess_input 已经烧进图里，Kotlin 端只负责裁剪+缩放+读像素）

用法:
  python training/export_tflite.py                       # float16
  python training/export_tflite.py --quantize none       # float32 调试
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "model"
IMG_SIZE = 224

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import tensorflow as tf
from tensorflow.keras import layers, Model


def build_inference_model(keras_path: Path) -> Model:
    """从训练好的完整模型里，重建一张只有推理算子的图。"""
    trained = tf.keras.models.load_model(str(keras_path))
    backbone = trained.get_layer("backbone")
    gap = trained.get_layer("gap")
    drop = trained.get_layer("drop")
    logits = trained.get_layer("logits")

    inp = layers.Input(shape=(IMG_SIZE, IMG_SIZE, 3), name="image")
    x = tf.keras.applications.mobilenet_v3.preprocess_input(inp)
    x = backbone(x, training=False)
    x = gap(x)
    x = drop(x, training=False)
    out = logits(x)
    infer = Model(inp, out, name="ginseng_inference")
    # 校验权重确实一致
    a = trained.predict(tf.zeros((1, IMG_SIZE, IMG_SIZE, 3)), verbose=0)
    b = infer.predict(tf.zeros((1, IMG_SIZE, IMG_SIZE, 3)), verbose=0)
    if abs(float(a[0][0]) - float(b[0][0])) > 1e-5:
        print("[warn] 重建的推理图输出与训练图不一致，请检查层名")
    return infer


def export(keras_path: Path, tflite_path: Path, quantize: str = "float16") -> bytes:
    model = build_inference_model(keras_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    if quantize == "float16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    elif quantize == "int8":
        # 动态范围量化：权重 int8，激活仍 float，无需校准集
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
    else:
        converter.optimizations = []
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]

    data = converter.convert()
    tflite_path.parent.mkdir(parents=True, exist_ok=True)
    tflite_path.write_bytes(data)

    # 自己先跑一遍，确认产物可用
    itp = tf.lite.Interpreter(model_path=str(tflite_path))
    itp.allocate_tensors()
    i = itp.get_input_details()[0]
    o = itp.get_output_details()[0]
    print(f"[{quantize}] {tflite_path.name}: {len(data)/1024:.1f} KB")
    print(f"  input : {i['shape'].tolist()} {i['dtype'].__name__}")
    print(f"  output: {o['shape'].tolist()} {o['dtype'].__name__}")
    if i["dtype"] != tf.float32 or i["shape"].tolist() != [1, IMG_SIZE, IMG_SIZE, 3]:
        raise SystemExit("!! 输入张量不符合 Android 端约定 [1,224,224,3] float32")

    import numpy as np

    itp.set_tensor(i["index"], np.zeros((1, IMG_SIZE, IMG_SIZE, 3), dtype=np.float32))
    itp.invoke()
    y = itp.get_tensor(o["index"])[0]
    print(f"  smoke probs: {np.round(y.astype('float32'), 3).tolist()}")
    return data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=str(MODEL / "ginseng_model.keras"))
    ap.add_argument("--output", default=str(MODEL / "ginseng_model.tflite"))
    ap.add_argument("--quantize", default="float16", choices=["none", "float16", "int8"])
    args = ap.parse_args()
    export(Path(args.input), Path(args.output), args.quantize)
    print("\n下一步: 复制 model/ginseng_model.tflite + labels.txt 到 android/app/src/main/assets/")


if __name__ == "__main__":
    main()
