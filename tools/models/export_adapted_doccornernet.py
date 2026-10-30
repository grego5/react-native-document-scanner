"""Export a trusted local DocCornerNet Keras inference model to the Android tensor contract."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import numpy as np
import tensorflow as tf


def load_float32_model(checkpoint,layers):
    spec=importlib.util.spec_from_file_location('adapted_doccorner_layers',layers)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    model=tf.keras.models.load_model(checkpoint,compile=False)
    # Training used mixed precision; deploy the identical weights with float32 CPU operators.
    config=json.loads(model.to_json())
    def float32_policies(node):
        if isinstance(node,dict):
            if node.get('class_name') == 'DTypePolicy' and node.get('config',{}).get('name') == 'mixed_float16':
                node['config']['name']='float32'
            for value in node.values(): float32_policies(value)
        elif isinstance(node,list):
            for value in node: float32_policies(value)
    float32_policies(config)
    original=model
    model=tf.keras.models.model_from_json(json.dumps(config))
    model.set_weights(original.get_weights())
    return model


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkpoint',type=Path,required=True)
    parser.add_argument('--layers',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    model=load_float32_model(args.checkpoint,args.layers)
    shape=model.input_shape
    assert len(shape) == 4 and shape[1] == shape[2] and shape[3] == 3 and shape[1] > 0, shape
    size=int(shape[1])
    class Export(tf.Module):
        def __init__(self):
            super().__init__()
            self.model=model
        @tf.function(input_signature=[tf.TensorSpec([1,size,size,3],tf.float32,name='image')])
        def infer(self,image):
            result=self.model(image,training=False)
            if not isinstance(result,dict):
                result=dict(zip(self.model.output_names,result))
            return tf.concat([tf.cast(result['coords'],tf.float32),tf.math.sigmoid(tf.cast(result['score_logit'],tf.float32))],axis=1)
    wrapper=Export()
    concrete=wrapper.infer.get_concrete_function()
    converter=tf.lite.TFLiteConverter.from_concrete_functions([concrete],wrapper)
    converter.target_spec.supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS]
    data=converter.convert()
    interpreter=tf.lite.Interpreter(model_content=data,num_threads=2)
    interpreter.allocate_tensors()
    error=0.
    for seed in range(3):
        tensor=np.random.default_rng(seed).normal(size=(1,size,size,3)).astype(np.float32)
        interpreter.set_tensor(interpreter.get_input_details()[0]['index'],tensor)
        interpreter.invoke()
        actual=interpreter.get_tensor(interpreter.get_output_details()[0]['index'])
        expected=wrapper.infer(tensor).numpy()
        assert actual.shape == (1,9),actual.shape
        np.testing.assert_allclose(actual,expected,atol=3e-5,rtol=3e-5)
        error=max(error,float(np.max(np.abs(actual-expected))))
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'model.tflite').write_bytes(data)
    provenance=dict(checkpoint=args.checkpoint.name,checkpointSha256=hashlib.sha256(args.checkpoint.read_bytes()).hexdigest(),
      adaptation=args.checkpoint.parent.name,tfliteSha256=hashlib.sha256(data).hexdigest(),maximumTensorParityError=error,
      deployment='Training mixed_float16 policies converted to float32; trained weights unchanged.',
      trainingInputSize=int(shape[1]),coordinateBins=int(model.get_layer('coords').num_bins),
      input=f'float32 NHWC 1x{size}x{size}x3 ImageNet normalized RGB',
      output='float32 1x9 TL.x TL.y TR.x TR.y BR.x BR.y BL.x BL.y sigmoidScore')
    (args.output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(provenance))


if __name__=='__main__':main()
