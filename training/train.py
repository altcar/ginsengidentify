"""
train.py — MobileNetV3-Small 迁移学习，三分类「人参 / 西洋参 / 桔梗」

和旧版的区别（旧版 test_acc 只有 62.5%，且 fine-tune 阶段其实没生效）:
  - 用命名层取 backbone（旧代码 model.layers[2] 并不一定是 MobileNet，
    解冻的是数据增强层，所以 "fine-tune" 一直是假的）
  - 中心裁剪成正方形再缩放，和 Android 端预处理完全一致（不是拉伸变形）
  - class_weight 补偿类别不平衡
  - 更长的 warmup / fine-tune，val_accuracy 最优自动回滚
  - 训练完直接算混淆矩阵 + 每类 precision/recall 写进 metrics.json

用法:
  python training/train.py                     # 默认训练 + 导出 tflite
  python training/train.py --no-export
  python training/train.py --epochs-finetune 30 --lr-finetune 5e-5
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "dataset" / "processed"
MODEL = ROOT / "model"
IMG_SIZE = 224
BATCH = 24
SEED = 42

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import os

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")

import tensorflow as tf
from tensorflow.keras import layers, Model
from tensorflow.keras.applications import MobileNetV3Small
from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint, ReduceLROnPlateau


def build_datasets():
    common = dict(
        image_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH,
        label_mode="categorical",
        crop_to_aspect_ratio=True,  # 中心裁剪，与 Android 端一致
    )
    train_ds = tf.keras.utils.image_dataset_from_directory(
        DATA / "train", shuffle=True, seed=SEED, **common
    )
    val_ds = tf.keras.utils.image_dataset_from_directory(
        DATA / "val", shuffle=False, **common
    )
    test_ds = tf.keras.utils.image_dataset_from_directory(
        DATA / "test", shuffle=False, **common
    )
    classes = list(train_ds.class_names)
    autotune = tf.data.AUTOTUNE
    return (
        train_ds.cache().prefetch(autotune),
        val_ds.cache().prefetch(autotune),
        test_ds.cache().prefetch(autotune),
        classes,
    )


def class_weights(train_dir: Path, classes: list[str]) -> dict[int, float]:
    counts = np.array(
        [sum(1 for _ in (train_dir / c).glob("*.*")) for c in classes], dtype=np.float64
    )
    if (counts == 0).any():
        return {}
    weights = counts.sum() / (len(counts) * counts)
    return {i: float(w) for i, w in enumerate(weights)}


def build_model(num_classes: int) -> Model:
    base = MobileNetV3Small(
        input_shape=(IMG_SIZE, IMG_SIZE, 3),
        alpha=0.75,
        include_top=False,
        weights="imagenet",
        name="backbone",
    )
    base.trainable = False

    data_aug = tf.keras.Sequential(
        [
            layers.RandomFlip("horizontal_and_vertical", name="aug_flip"),
            layers.RandomRotation(0.12, fill_mode="reflect", name="aug_rot"),
            layers.RandomZoom(0.15, fill_mode="reflect", name="aug_zoom"),
            layers.RandomTranslation(0.1, 0.1, fill_mode="reflect", name="aug_trans"),
            layers.RandomContrast(0.2, name="aug_contrast"),
            layers.RandomBrightness(0.15, name="aug_bright"),
        ],
        name="augment",
    )

    inputs = layers.Input(shape=(IMG_SIZE, IMG_SIZE, 3), name="image")
    x = data_aug(inputs)
    x = tf.keras.applications.mobilenet_v3.preprocess_input(x)  # [0,255] -> [-1,1]
    x = base(x, training=False)
    x = layers.GlobalAveragePooling2D(name="gap")(x)
    x = layers.Dropout(0.3, name="drop")(x)
    outputs = layers.Dense(num_classes, activation="softmax", name="logits")(x)
    return Model(inputs, outputs, name="ginseng_mnv3")


def evaluate(model, ds, classes) -> tuple[float, float, np.ndarray]:
    y_true, y_pred, logp_true = [], [], []
    for xb, yb in ds:
        p = model.predict(xb, verbose=0).astype(np.float64)
        t = np.argmax(yb.numpy(), axis=1)
        y_true.extend(t.tolist())
        y_pred.extend(np.argmax(p, axis=1).tolist())
        logp_true.extend(np.log(np.clip(p[np.arange(len(t)), t], 1e-9, 1.0)).tolist())
    y_true = np.array(y_true)
    y_pred = np.array(y_pred)
    n = len(classes)
    cm = np.zeros((n, n), dtype=int)
    for t, p in zip(y_true, y_pred):
        cm[t, p] += 1
    acc = float((y_true == y_pred).mean()) if len(y_true) else 0.0
    loss = float(-np.mean(logp_true)) if logp_true else 0.0
    return acc, loss, cm


def per_class_report(cm: np.ndarray, classes: list[str]) -> dict:
    report = {}
    for i, c in enumerate(classes):
        tp = int(cm[i, i])
        fn = int(cm[i, :].sum() - tp)
        fp = int(cm[:, i].sum() - tp)
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        report[c] = {
            "precision": round(prec, 4),
            "recall": round(rec, 4),
            "f1": round(f1, 4),
            "support": int(cm[i, :].sum()),
        }
    return report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs-warmup", type=int, default=8)
    ap.add_argument("--epochs-finetune", type=int, default=25)
    ap.add_argument("--lr-warmup", type=float, default=1e-3)
    ap.add_argument("--lr-finetune", type=float, default=1e-4)
    ap.add_argument("--unfreeze-layers", type=int, default=40)
    ap.add_argument("--no-export", action="store_true")
    args = ap.parse_args()

    tf.keras.utils.set_random_seed(SEED)
    MODEL.mkdir(exist_ok=True)

    print("TensorFlow:", tf.__version__)
    print("GPU:", tf.config.list_physical_devices("GPU") or "CPU only")

    t0 = time.time()
    train_ds, val_ds, test_ds, classes = build_datasets()
    print("类别:", classes)
    n_train = sum(1 for _ in (DATA / "train").glob("*/*"))
    print(f"样本: train={n_train}  val={sum(1 for _ in (DATA/'val').glob('*/*'))}"
          f"  test={sum(1 for _ in (DATA/'test').glob('*/*'))}")

    (MODEL / "labels.txt").write_text("\n".join(classes) + "\n", encoding="utf-8")

    cw = class_weights(DATA / "train", classes)
    print("class_weight:", {classes[i]: round(w, 3) for i, w in cw.items()})

    model = build_model(len(classes))
    model.summary()

    # ---------------- stage 1: warmup (backbone frozen) ----------------
    print(f"\n=== stage 1: warmup ({args.epochs_warmup} epochs) ===")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(args.lr_warmup),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    h1 = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=args.epochs_warmup,
        class_weight=cw,
        callbacks=[
            ModelCheckpoint(str(MODEL / "best_warmup.keras"),
                            save_best_only=True, monitor="val_accuracy", verbose=0),
            EarlyStopping(patience=4, restore_best_weights=True,
                          monitor="val_accuracy"),
        ],
    )

    # ---------------- stage 2: fine-tune ----------------
    backbone = model.get_layer("backbone")
    backbone.trainable = True
    cut = max(0, len(backbone.layers) - args.unfreeze_layers)
    for layer in backbone.layers[:cut]:
        layer.trainable = False
    trainable_n = sum(1 for l in backbone.layers if l.trainable)
    print(f"\n=== stage 2: fine-tune ({args.epochs_finetune} epochs, "
          f"{trainable_n}/{len(backbone.layers)} backbone layers trainable) ===")

    model.compile(
        optimizer=tf.keras.optimizers.Adam(args.lr_finetune),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    h2 = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=args.epochs_finetune,
        class_weight=cw,
        callbacks=[
            ModelCheckpoint(str(MODEL / "best_finetune.keras"),
                            save_best_only=True, monitor="val_accuracy", verbose=1),
            EarlyStopping(patience=6, restore_best_weights=True,
                          monitor="val_accuracy"),
            ReduceLROnPlateau(factor=0.4, patience=3, min_lr=1e-6,
                              monitor="val_loss"),
        ],
    )

    best_path = MODEL / "best_finetune.keras"
    if best_path.exists():
        try:
            model = tf.keras.models.load_model(str(best_path))
            print("已回滚到 val_accuracy 最优权重")
        except Exception as e:
            print(f"[warn] 载入 best_finetune 失败({e})，用当前权重")

    print("\n=== 评估 ===")
    val_acc, _, val_cm = evaluate(model, val_ds, classes)
    test_acc, test_loss, test_cm = evaluate(model, test_ds, classes)
    print(f"val_acc={val_acc:.4f}  test_acc={test_acc:.4f}  test_loss={test_loss:.4f}")
    print("混淆矩阵 (行=真实, 列=预测):")
    print("            " + "".join(f"{c:>12}" for c in classes))
    for i, c in enumerate(classes):
        print(f"{c:>12}" + "".join(f"{v:>12}" for v in test_cm[i]))

    model.save(str(MODEL / "ginseng_model.keras"))
    report = per_class_report(test_cm, classes)

    metrics = {
        "classes": classes,
        "num_classes": len(classes),
        "val_accuracy": round(val_acc, 4),
        "test_accuracy": round(test_acc, 4),
        "test_loss": round(test_loss, 4),
        "confusion_matrix": {"labels": classes, "matrix": test_cm.tolist()},
        "per_class": report,
        "class_weight": {classes[i]: round(w, 4) for i, w in cw.items()},
        "num_train": n_train,
        "warmup_epochs": args.epochs_warmup,
        "finetune_epochs": args.epochs_finetune,
        "img_size": IMG_SIZE,
        "preprocess": "center_crop_square_then_resize_224|rgb|float32[0,255]",
        "elapsed_sec": round(time.time() - t0, 1),
        "tf_version": tf.__version__,
    }
    (MODEL / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    curves = {
        "warmup": {k: [float(x) for x in v] for k, v in h1.history.items()},
        "finetune": {k: [float(x) for x in v] for k, v in h2.history.items()},
    }
    (MODEL / "training_curves.json").write_text(json.dumps(curves), encoding="utf-8")
    print(f"耗时 {metrics['elapsed_sec']}s -> {MODEL/'metrics.json'}")

    if not args.no_export:
        print("\n=== 导出 TFLite ===")
        from export_tflite import export  # 同目录
        export(MODEL / "ginseng_model.keras", MODEL / "ginseng_model.tflite", "float16")


if __name__ == "__main__":
    main()
