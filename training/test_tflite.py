"""
test_tflite.py — 端到端验证 TFLite 模型在 Python 里能正确推理

对比 Python 实现和 Android 实现的输入处理是否一致
"""
import numpy as np
import tensorflow as tf
from PIL import Image
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "model" / "ginseng_model.tflite"
LABELS = ROOT / "model" / "labels.txt"
TEST_DIR = ROOT / "dataset" / "processed" / "test"


def android_preprocess(p: Path) -> np.ndarray:
    """复刻 Android Classifier.classify() 的输入处理:
    - 缩放到 224x224
    - 读 RGB, float32, 范围 [0, 255]
    - shape (1, 224, 224, 3)
    """
    im = Image.open(p).convert("RGB").resize((224, 224), Image.BILINEAR)
    arr = np.asarray(im, dtype=np.float32)  # shape (224, 224, 3), [0, 255]
    return arr[np.newaxis, ...]  # (1, 224, 224, 3)


def main():
    labels = LABELS.read_text(encoding="utf-8").strip().splitlines()
    print(f"labels: {labels}")
    itp = tf.lite.Interpreter(model_path=str(MODEL))
    itp.allocate_tensors()
    in_det = itp.get_input_details()[0]
    out_det = itp.get_output_details()[0]
    print(f"input  : {in_det['shape']}, dtype={in_det['dtype']}")
    print(f"output : {out_det['shape']}, dtype={out_det['dtype']}")

    n_total, n_correct = 0, 0
    confusion = {l: {l2: 0 for l2 in labels} for l in labels}
    for cls_dir in sorted(TEST_DIR.iterdir()):
        if not cls_dir.is_dir():
            continue
        true = cls_dir.name
        for p in cls_dir.glob("*.jpg"):
            x = android_preprocess(p)
            itp.set_tensor(in_det["index"], x)
            itp.invoke()
            y = itp.get_tensor(out_det["index"])[0]
            pred = labels[int(np.argmax(y))]
            confusion[true][pred] += 1
            n_total += 1
            if pred == true:
                n_correct += 1
    print(f"\n===  TFLite 直接推理  ===")
    print(f"总样本: {n_total}, 正确: {n_correct}, 准确率: {n_correct/n_total:.3f}")
    print("混淆矩阵 (rows=true, cols=pred):")
    print("       ", "  ".join(f"{l:>10}" for l in labels))
    for t in labels:
        print(f"{t:>10}", "  ".join(f"{confusion[t][p]:>10}" for p in labels))


if __name__ == "__main__":
    main()
