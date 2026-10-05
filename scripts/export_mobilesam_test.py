#!/usr/bin/env python3
"""Export the tested MobileSAM encoder + box decoder for the mobileSam Android variant."""
import argparse
import hashlib
import json
import os
from pathlib import Path

os.environ.setdefault("YOLO_CONFIG_DIR", "/private/tmp/walkmate-mobilesam-export")
Path(os.environ["YOLO_CONFIG_DIR"]).mkdir(parents=True, exist_ok=True)

import cv2
import numpy as np
import onnx
import onnxruntime as ort
import torch
from ultralytics.models.sam.build import build_mobile_sam
from ultralytics.data.augment import LetterBox


class BoxDecoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.prompt_encoder = model.prompt_encoder
        self.mask_decoder = model.mask_decoder

    def forward(self, image_embeddings, box):
        sparse, dense = self.prompt_encoder(points=None, boxes=box, masks=None)
        masks, quality = self.mask_decoder(image_embeddings=image_embeddings,
            image_pe=self.prompt_encoder.get_dense_pe(), sparse_prompt_embeddings=sparse,
            dense_prompt_embeddings=dense, multimask_output=False)
        return torch.nn.functional.interpolate(masks, (1024, 1024), mode="bilinear", align_corners=False), quality


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--checkpoint",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--images",type=Path,nargs="+",required=True)
    p.add_argument("--reference-output",type=Path,help="Optional Android parity fixture for the first image")
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(4)
    model=build_mobile_sam(str(args.checkpoint)).eval()
    decoder=BoxDecoder(model).eval()
    image=torch.zeros(1,3,1024,1024)
    with torch.inference_mode():
        embedding=model.image_encoder(image)
        box=torch.tensor([[100.,100.,900.,900.]])
        torch.onnx.export(model.image_encoder,image,args.output/"mobile_sam_encoder.onnx",
            input_names=["image"],output_names=["image_embeddings"],opset_version=17,dynamo=False)
        torch.onnx.export(decoder,(embedding,box),args.output/"mobile_sam_decoder.onnx",
            input_names=["image_embeddings","box"],output_names=["mask_logits","quality"],opset_version=17,dynamo=False)
    options=ort.SessionOptions();options.intra_op_num_threads=4
    encoder_session=ort.InferenceSession(str(args.output/"mobile_sam_encoder.onnx"),options,providers=["CPUExecutionProvider"])
    decoder_session=ort.InferenceSession(str(args.output/"mobile_sam_decoder.onnx"),options,providers=["CPUExecutionProvider"])
    checks=[]
    for source in args.images:
        original=cv2.imread(str(source));assert original is not None
        padded=LetterBox((1024,1024),auto=False,center=False)(image=original)
        tensor=((padded[...,::-1].astype(np.float32)-[123.675,116.28,103.53])/[58.395,57.12,57.375]).transpose(2,0,1)[None].astype(np.float32)
        h,w=original.shape[:2];gain=1024/max(h,w)
        for frac in [(0.1,0.15,0.8,0.9),(0.4,0.5,0.7,0.95)]:
            coords=np.array([[frac[0]*w,frac[1]*h,frac[2]*w,frac[3]*h]],np.float32)*gain
            with torch.inference_mode():
                ref_embedding=model.image_encoder(torch.from_numpy(tensor))
                ref_logits,ref_quality=decoder(ref_embedding,torch.from_numpy(coords))
            actual_embedding=encoder_session.run(None,{"image":tensor})[0]
            actual,quality=decoder_session.run(None,{"image_embeddings":actual_embedding,"box":coords})
            a=actual>0;b=ref_logits.numpy()>0
            intersection=np.logical_and(a,b).sum();union=np.logical_or(a,b).sum()
            mask_iou=float(intersection/union) if union else 1.0
            assert mask_iou>=.995,(source,mask_iou)
            assert np.max(np.abs(actual_embedding-ref_embedding.numpy()))<.01
            assert np.max(np.abs(quality-ref_quality.numpy()))<.001
            if args.reference_output and source == args.images[0] and frac == (0.4,0.5,0.7,0.95):
                args.reference_output.mkdir(parents=True,exist_ok=True)
                restored=cv2.resize(actual[0,0,:round(h*gain),:round(w*gain)],(w,h),interpolation=cv2.INTER_LINEAR)>0
                (args.reference_output/"mobilesam_expected_mask.bin").write_bytes(restored.astype(np.uint8).tobytes())
                (args.reference_output/"mobilesam_expected_mask.json").write_text(json.dumps({
                    "source":source.name,"width":w,"height":h,"box_fraction":frac,
                    "meaning":"Desktop ONNX parity reference, not semantic ground truth"},indent=2)+"\n")
            checks.append({"source":str(source),"box_fraction":frac,"mask_iou_vs_pytorch":mask_iou,"quality_abs_error":float(np.max(np.abs(quality-ref_quality.numpy())))})
    files={}
    for path in args.output.glob("*.onnx"):
        onnx.checker.check_model(str(path))
        files[path.name]={"sha256":hashlib.sha256(path.read_bytes()).hexdigest(),"bytes":path.stat().st_size}
    manifest={"schema":1,"model":"MobileSAM vit_t","source_sha256":hashlib.sha256(args.checkpoint.read_bytes()).hexdigest(),
        "input_size":1024,"preprocess":"Ultralytics RGB resize longest side, top-left padding114, CHW (RGB-mean)/std",
        "mean":[123.675,116.28,103.53],"std":[58.395,57.12,57.375],"box":"single xyxy in 1024 image coordinates",
        "mask_output":"float32[1,1,1024,1024], crop padding then bilinear resize to original, threshold0",
        "files":files,"versions":{"torch":torch.__version__,"onnx":onnx.__version__,"onnxruntime":ort.__version__},"validation":checks}
    (args.output/"mobile_sam_manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
    print(json.dumps(manifest,indent=2),flush=True)


if __name__=="__main__":main()
