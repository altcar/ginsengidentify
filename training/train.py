"""
train.py — MobileNetV3 Small 迁移学习，3 类分类

输入:  dataset/processed/{train,val,test}/<class>/*
输出:  model/ginseng_model.keras  (Keras 格式)
       model/labels.txt
       model/metrics.json

策略:
  - 基础模型 MobileNetV3Small (alpha=0.75)，ImageNet 预训练
  - 输入 224x224，[-1, 1] 归一化
  - 顶层: GAP -> Dropout(0.3) -> Dense(3, softmax)
  - 阶段 1: 冻结 backbone，warmup 5 epoch
  - 阶段 2: 解冻后 30 层，fine-tune 15 epoch，lr=1e-4
  - 增强: 随机翻转/旋转/缩放/裁剪/亮度对比度
"""
import argparse
import json
import os
import time
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")

import tensorflow as tf
from tensorflow.keras import layers, Model
from tensorflow.keras.applications import MobileNetV3Small
from tensorflow.keras.callbacks import (
    EarlyStopping, ModelCheckpoint, ReduceLROnPlateau,
)

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "dataset" / "processed"
MODEL = ROOT / "model"
MODEL.mkdir(exist_ok=True)
IMG_SIZE = 224
BATCH = 32
SEED = 42


def build_datasets():
    train_ds = tf.keras.utils.image_dataset_from_directory(
        DATA / "train",
        image_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH,
        label_mode="categorical",
        shuffle=True,
        seed=SEED,
    )
    val_ds = tf.keras.utils.image_dataset_from_directory(
        DATA / "val",
        image_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH,
        label_mode="categorical",
        shuffle=False,
        seed=SEED,
    )
    test_ds = tf.keras.utils.image_dataset_from_directory(
        DATA / "test",
        image_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH,
        label_mode="categorical",
        shuffle=False,
        seed=SEED,
    )
    classes = train_ds.class_names
    AUTOTUNE = tf.data.AUTOTUNE
    train_ds = train_ds.cache().prefetch(AUTOTUNE)
    val_ds = val_ds.cache().prefetch(AUTOTUNE)
    test_ds = test_ds.cache().prefetch(AUTOTUNE)
    return train_ds, val_ds, test_ds, classes


def build_model(num_classes: int) -> Model:
    base = MobileNetV3Small(
        input_shape=(IMG_SIZE, IMG_SIZE, 3),
        alpha=0.75,
        include_top=False,
        weights="imagenet",
    )
    base.trainable = False
    data_aug = tf.keras.Sequential([
        layers.RandomFlip("horizontal_and_vertical"),
        layers.RandomRotation(0.15),
        layers.RandomZoom(0.15),
        layers.RandomContrast(0.15),
        layers.RandomTranslation(0.1, 0.1),
    ])
    inputs = layers.Input(shape=(IMG_SIZE, IMG_SIZE, 3))
    x = data_aug(inputs)
    x = tf.keras.applications.mobilenet_v3.preprocess_input(x)
    x = base(x, training=False)
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dropout(0.3)(x)
    outputs = layers.Dense(num_classes, activation="softmax")(x)
    return Model(inputs, outputs, name="ginseng_mnv3")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--epochs-warmup", type=int, default=5)
    parser.add_argument("--epochs-finetune", type=int, default=15)
    parser.add_argument("--lr-finetune", type=float, default=1e-4)
    args = parser.parse_args()

    print("TensorFlow:", tf.__version__)
    print("GPU available:", tf.config.list_physical_devices("GPU"))

    t0 = time.time()
    train_ds, val_ds, test_ds, classes = build_datasets()
    print(f"类别: {classes}")
    if len(classes) == 0:
        print("!! 训练集为空，先跑 prepare_data.py")
        return
    with (MODEL / "labels.txt").open("w", encoding="utf-8") as f:
        for c in classes:
            f.write(c + "\n")
    print(f"已写 labels.txt: {classes}")

    model = build_model(len(classes))
    model.summary()

    # ---- 阶段 1：warmup ----
    print(f"\n=== 阶段 1: warmup ({args.epochs_warmup} epochs) ===")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    cb = [
        ModelCheckpoint(
            str(MODEL / "best_warmup.keras"),
            save_best_only=True, monitor="val_accuracy",
        ),
    ]
    h1 = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=args.epochs_warmup,
        callbacks=cb,
    )

    # ---- 阶段 2：fine-tune 后 30 层 ----
    print(f"\n=== 阶段 2: fine-tune ({args.epochs_finetune} epochs) ===")
    base = model.layers[2]  # MobileNetV3Small layer
    base.trainable = True
    for layer in base.layers[:-30]:
        layer.trainable = False
    model.compile(
        optimizer=tf.keras.optimizers.Adam(args.lr_finetune),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    cb = [
        ModelCheckpoint(
            str(MODEL / "best_finetune.keras"),
            save_best_only=True, monitor="val_accuracy",
        ),
        EarlyStopping(
            patience=5, restore_best_weights=True, monitor="val_accuracy",
        ),
        ReduceLROnPlateau(
            factor=0.5, patience=2, monitor="val_loss", min_lr=1e-6,
        ),
    ]
    h2 = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=args.epochs_finetune,
        callbacks=cb,
    )

    # ---- 评估 ----
    print("\n=== Test 集评估 ===")
    test_loss, test_acc = model.evaluate(test_ds, verbose=1)
    print(f"test_loss={test_loss:.4f}, test_acc={test_acc:.4f}")

    # ---- 保存最终模型 ----
    final_path = MODEL / "ginseng_model.keras"
    model.save(str(final_path))
    print(f"已保存: {final_path}")

    metrics = {
        "classes": classes,
        "num_classes": len(classes),
        "test_loss": float(test_loss),
        "test_accuracy": float(test_acc),
        "warmup_epochs": args.epochs_warmup,
        "finetune_epochs": args.epochs_finetune,
        "img_size": IMG_SIZE,
        "elapsed_sec": round(time.time() - t0, 1),
    }
    with (MODEL / "metrics.json").open("w", encoding="utf-8") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)
    print(f"耗时: {metrics['elapsed_sec']}s")


if __name__ == "__main__":
    main()
