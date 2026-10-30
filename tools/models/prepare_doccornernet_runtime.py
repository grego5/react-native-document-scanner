"""Prepare private, opt-in Android instrumentation fixtures; never release assets.

Run with .local/doccornernet-env/Scripts/python.exe. No downloads or model changes.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import sys


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--photos', type=Path, default=root / '.local/scanner-samples')
    parser.add_argument('--output', type=Path, default=root / '.local/doccornernet-runtime/assets')
    parser.add_argument('--checkpoint',type=Path,default=root/'.local/models/mobilenetv3_custom_224_v1/best_model_inference.keras')
    parser.add_argument('--layers',type=Path,default=root/'.local/models/mobilenetv3_custom_224_v1/model.py')
    args = parser.parse_args()
    from export_adapted_doccornernet import load_float32_model
    import numpy as np
    import tensorflow as tf
    from PIL import Image, ImageOps, __version__ as pillow_version

    model=load_float32_model(args.checkpoint,args.layers)
    size=int(model.input_shape[1])
    asset = root / 'android/src/main/assets/doccornernet/model.tflite'
    interpreter = tf.lite.Interpreter(model_path=str(asset), num_threads=2)
    interpreter.allocate_tensors()
    destination = args.output / 'doccornernet-runtime'
    destination.mkdir(parents=True, exist_ok=True)
    rows = []
    photos = sorted((p for p in args.photos.iterdir() if p.stem.isdigit() and
                     p.suffix.lower() in {'.jpg', '.jpeg'}), key=lambda p: int(p.stem))
    if not photos:
        raise SystemExit('No numbered private photos found.')
    for photo in photos:
        name = f'sample-{int(photo.stem):02d}'
        with Image.open(photo) as source:
            upright = ImageOps.exif_transpose(source).convert('RGB')
            width, height = upright.size
            upright.save(destination / f'{name}.png')
            resized = upright.resize((size, size), Image.Resampling.BILINEAR)
            resized.save(destination / f'{name}-{size}.png')
            pixels = np.asarray(resized, dtype=np.float32) / np.float32(255)
        tensor = ((pixels - np.array([.485, .456, .406], dtype=np.float32)) /
                  np.array([.229, .224, .225], dtype=np.float32))[None]
        tensor.astype('<f4').tofile(destination / f'{name}.input.f32')
        output = model(tensor, training=False)
        if not isinstance(output,dict): output=dict(zip(model.output_names,output))
        reference = np.concatenate([output['coords'].numpy(), tf.sigmoid(tf.cast(output['score_logit'],tf.float32)).numpy()], axis=1)[0]
        interpreter.set_tensor(interpreter.get_input_details()[0]['index'], tensor)
        interpreter.invoke()
        lite = interpreter.get_tensor(interpreter.get_output_details()[0]['index'])[0]
        np.testing.assert_allclose(lite, reference, atol=3e-5, rtol=3e-5)
        shutil.copyfile(photo, destination / f'{name}.jpg')
        rows.append(dict(name=name, sha256=hashlib.sha256(photo.read_bytes()).hexdigest(),
                         width=width, height=height, tensorflow=reference.tolist(), tflite=lite.tolist(),
                         exportMaxError=float(np.max(np.abs(lite-reference)))))

    # Valid TFLite asset with an intentionally incompatible tensor contract.
    class WrongShape(tf.Module):
        @tf.function(input_signature=[tf.TensorSpec([1, 2], tf.float32)])
        def infer(self, image):
            return image + 1
    module = WrongShape()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([module.infer.get_concrete_function()], module)
    (destination / 'wrong-shape.tflite').write_bytes(converter.convert())
    report = dict(modelSha256=hashlib.sha256(asset.read_bytes()).hexdigest(), pillow=pillow_version,
                  meanCornerBudget=.005, worstCornerBudget=.015, photos=rows)
    (destination / 'references.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(f'Prepared {len(rows)} private photos in {destination}; max export error '
          f'{max(row["exportMaxError"] for row in rows):.8f}')


if __name__ == '__main__':
    main()
