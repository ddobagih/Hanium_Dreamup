from pathlib import Path
import os
os.environ.update(CUDA_VISIBLE_DEVICES='-1',TF_CPP_MIN_LOG_LEVEL='3',TF_NUM_INTRAOP_THREADS='2',TF_NUM_INTEROP_THREADS='1',OMP_NUM_THREADS='2',OPENBLAS_NUM_THREADS='2')
import json, hashlib, collections, time, gzip
import cv2, numpy as np, tensorflow as tf
import tempfile
REFERENCE=Path(__file__).resolve().parent
EXPECTED=json.loads((REFERENCE/'result.json').read_bytes())
OUT=Path(tempfile.mkdtemp(prefix='walksafe-s10-18-crowded-evaluation-'))
ROOT=Path('/home/ddobagi/Hanium_Dreamup')
ASSETS=ROOT/'apps/android/app/src/main/assets'
CONFIG=ASSETS/'model-config/two_model_runtime.json'
LIST=Path('/home/ddobagi/Documents/Codex/2026-09-13/d/work/full_all.txt')
sha=lambda b:hashlib.sha256(b).hexdigest()
cfg=json.loads(CONFIG.read_bytes())['models']['unified_walksafe']; names=cfg['classes'] if 'classes' in cfg else json.loads((ROOT/'apps/android/app/src/test/resources/inference/full-class-regression/manifest.json').read_bytes())['classes']
MODEL=ASSETS/cfg['asset']; assert sha(MODEL.read_bytes())==cfg['artifact_sha256']==EXPECTED['model_sha256']
assert sha(CONFIG.read_bytes())==EXPECTED['config_sha256']
assert sha(LIST.read_bytes())==EXPECTED['source_list_sha256']
byclass=collections.defaultdict(list)
for path in LIST.read_text().splitlines():
 im=Path(path); lab=im.parent.parent.parent/'labels/test'/f'{im.stem}.txt'
 ids={int(float(row.split()[0])) for row in lab.read_text().splitlines() if row.strip()}
 assert all(0<=c<21 for c in ids)
 for c in ids:byclass[c].append(path)
chosen={}
box_counts={}
for path in LIST.read_text().splitlines():
 im=Path(path); lab=im.parent.parent.parent/'labels/test'/f'{im.stem}.txt'
 ids=[int(float(row.split()[0])) for row in lab.read_text().splitlines() if row.strip()]
 box_counts[path]=len(ids)
 if len(ids)>=20:chosen[path]=set(ids)
ordered=sorted(chosen)
selection=[{'image':p,'selected_for_classes':sorted(chosen[p])} for p in ordered]
(OUT/'selection.json').write_text(json.dumps(selection,ensure_ascii=False,indent=2))
assert len(ordered)==149
CROWDED_EXPECTED=EXPECTED
assert sha((OUT/'selection.json').read_bytes())==CROWDED_EXPECTED['selection_sha256']
expected_rows={row['image']:row for row in CROWDED_EXPECTED['rows']}
assert tf.__version__==EXPECTED['runtime']['tensorflow']
assert cv2.__version__==EXPECTED['runtime']['opencv']
assert np.__version__==EXPECTED['runtime']['numpy']
interpreter=tf.lite.Interpreter(model_path=str(MODEL),num_threads=2); interpreter.allocate_tensors()
inp=interpreter.get_input_details()[0]; op=interpreter.get_output_details()[0]
assert inp['shape'].tolist()==[1,768,768,3] and op['shape'].tolist()==[1,25,12096]
assert inp['dtype']==op['dtype']==np.float32
thresholds=np.array([cfg['thresholds'].get(n,cfg['thresholds']['default']) for n in names],dtype=np.float32)
allowed=np.array([n in cfg['allowlist'] for n in names])[:,None]
pairs=collections.Counter(); rows=[]; example_rows=[]; start=time.monotonic()
print(json.dumps({'phase':'START','images':len(ordered),'selection':'every source photo with >=20 annotated boxes; source maximum34','selection_sha256':sha((OUT/'selection.json').read_bytes()),'tensorflow':tf.__version__,'opencv':cv2.__version__}),flush=True)
for index,path in enumerate(ordered):
 image=Path(path); photo=image.read_bytes(); rgb=cv2.cvtColor(cv2.imdecode(np.frombuffer(photo,np.uint8),cv2.IMREAD_COLOR),cv2.COLOR_BGR2RGB)
 height,width=rgb.shape[:2]; size=768; gain=min(size/width,size/height); rw,rh=round(width*gain),round(height*gain); px,py=round((size-rw)/2-.1),round((size-rh)/2-.1)
 pixels=np.full((size,size,3),114,np.uint8); pixels[py:py+rh,px:px+rw]=cv2.resize(rgb,(rw,rh),interpolation=cv2.INTER_LINEAR)
 tensor=(pixels.astype(np.float32)[None]/np.float32(255)).astype('<f4')
 interpreter.set_tensor(inp['index'],tensor); interpreter.invoke(); raw=interpreter.get_tensor(op['index']).astype('<f4')[0]
 assert raw.shape==(25,12096) and np.isfinite(raw).all()
 scores=raw[4:]; ids=np.argmax(scores,axis=0); cols=np.arange(12096); top=scores[ids,cols]
 valid_scores=np.all((scores>=0)&(scores<=1),axis=0)&(top>0)
 x=raw[0]-raw[2]/np.float32(2); y=raw[1]-raw[3]/np.float32(2); right=x+raw[2]; bottom=y+raw[3]
 valid_box=(raw[2]>0)&(raw[3]>0)&(x<1)&(y<1)&(right>0)&(bottom>0)
 left_px=np.maximum(np.maximum(x*np.float32(size),np.float32(px)),np.float32(0)); top_px=np.maximum(np.maximum(y*np.float32(size),np.float32(py)),np.float32(0))
 right_px=np.minimum(np.minimum(right*np.float32(size),np.float32(px)+min(np.float32(rw),np.float32(width)*np.float32(gain))),np.float32(size))
 bottom_px=np.minimum(np.minimum(bottom*np.float32(size),np.float32(py)+min(np.float32(rh),np.float32(height)*np.float32(gain))),np.float32(size))
 visible=valid_scores&valid_box&(right_px>left_px)&(bottom_px>top_px)
 top_pass=visible&(top>=thresholds[ids])&allowed[ids,0]
 passing=(scores>=thresholds[:,None])&allowed
 passing[ids,cols]=False
 alt_scores=np.where(passing,scores,-np.inf); alt_ids=np.argmax(alt_scores,axis=0); alt=alt_scores[alt_ids,cols]
 dropped=visible&~top_pass&np.isfinite(alt)
 anchors=np.flatnonzero(dropped)
 row={'index':index,'image':path,'image_sha256':sha(photo),'selected_for_classes':sorted(chosen[path]),'visible_valid_anchors':int(visible.sum()),'top1_pass_anchors':int(top_pass.sum()),'discarded_top1_with_other_pass_anchors':len(anchors),'raw_sha256':sha(raw.tobytes()),'input_sha256':sha(tensor.tobytes())}
 row['annotated_box_count']=box_counts[path]
 assert row==expected_rows[path], path
 rows.append(row)
 for a in anchors:
  key=(names[int(ids[a])],names[int(alt_ids[a])]);pairs[key]+=1
  if len(example_rows)<50:
   example_rows.append({'image':path,'image_sha256':row['image_sha256'],'anchor':int(a),'top_class':key[0],'top_score':float(top[a]),'top_threshold':float(thresholds[ids[a]]),'alternative_class':key[1],'alternative_score':float(alt[a]),'alternative_threshold':float(thresholds[alt_ids[a]]),'score_gap':float(top[a]-alt[a])})
 if len(anchors)>0 and sum((OUT/f'case-{i:04}.f32le.gz').exists() for i in range(index))<5:
  (OUT/f'case-{index:04}.f32le.gz').write_bytes(gzip.compress(raw.tobytes(),mtime=0))
 if (index+1)%100==0 or index==len(ordered)-1:
  print(json.dumps({'phase':'PROGRESS','done':index+1,'total':len(ordered),'elapsed_s':round(time.monotonic()-start,1),'ambiguous_photos':sum(r['discarded_top1_with_other_pass_anchors']>0 for r in rows),'ambiguous_anchors':sum(pairs.values())}),flush=True)
result={'scope':'All 149 source photos with at least 20 annotated boxes (maximum34 of full27021). Current actual model pre-NMS accepted-anchor upper bound for the existing300 cap; not field performance, risk-object loss, annotation completeness, mAP, Android YUV/delegate equivalence or artificial300-object stress.',
 'selection_rule':'all full_all27021 source photos with >=20 nonempty YOLO label rows, absolute path order','source_list':str(LIST),'source_list_sha256':sha(LIST.read_bytes()),'source_images':len(LIST.read_text().splitlines()),'available_per_class':{str(c):len(byclass[c]) for c in range(21)},'classes':names,'thresholds':cfg['thresholds'],
 'model':str(MODEL),'model_sha256':sha(MODEL.read_bytes()),'config_sha256':sha(CONFIG.read_bytes()),'selection_sha256':sha((OUT/'selection.json').read_bytes()),'runtime':{'tensorflow':tf.__version__,'opencv':cv2.__version__,'numpy':np.__version__,'delegate':'host CPU XNNPACK','threads':2},
 'images':len(rows),'images_with_discarded_alternative':sum(r['discarded_top1_with_other_pass_anchors']>0 for r in rows),'raw_anchors':len(rows)*12096,'visible_valid_anchors':sum(r['visible_valid_anchors'] for r in rows),'top1_pass_anchors':sum(r['top1_pass_anchors'] for r in rows),'discarded_top1_with_other_pass_anchors':sum(pairs.values()),'pairs':[{'top_class':k[0],'alternative_class':k[1],'anchor_count':v} for k,v in pairs.most_common()],'examples':example_rows,'rows':rows,'elapsed_s':time.monotonic()-start}
result['maximum_pre_nms_accepted_anchors']=max(row['top1_pass_anchors'] for row in rows)
result['images_with_accepted_anchors_at_least_300']=sum(row['top1_pass_anchors']>=300 for row in rows)
result['images_with_accepted_anchors_above_300']=sum(row['top1_pass_anchors']>300 for row in rows)
for key in ['images','top1_pass_anchors','maximum_pre_nms_accepted_anchors','images_with_accepted_anchors_at_least_300','images_with_accepted_anchors_above_300']:
 assert result[key]==CROWDED_EXPECTED[key],key
(OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)); print(json.dumps({k:v for k,v in result.items() if k not in ['rows','examples','classes','thresholds','available_per_class']},ensure_ascii=False),flush=True)

print("Every crowded photo/input/raw hash and row count reproduced. Output:", OUT, flush=True)
