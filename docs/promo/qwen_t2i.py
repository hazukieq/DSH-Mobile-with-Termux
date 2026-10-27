import json,sys,time,urllib.request,urllib.parse,os,uuid
API="http://127.0.0.1:8188"
prompt=sys.argv[1]; out=sys.argv[2]; seed=int(sys.argv[3]) if len(sys.argv)>3 else 12345
W,H=1600,896
g={
 "1":{"class_type":"UNETLoader","inputs":{"unet_name":"qwen_image_2.1_int8_convrot.safetensors","weight_dtype":"default"}},
 "2":{"class_type":"ModelSamplingAuraFlow","inputs":{"model":["1",0],"shift":3.1}},
 "3":{"class_type":"CLIPLoader","inputs":{"clip_name":"qwen3vl_8b_int8_convrot.safetensors","type":"qwen_image"}},
 "4":{"class_type":"VAELoader","inputs":{"vae_name":"qwen_image_2.1_vae_bf16.safetensors"}},
 "5":{"class_type":"TextEncodeQwenImage21","inputs":{"clip":["3",0],"prompt":prompt,"negative_prompt":"lowres, text, watermark, logo, cluttered, cartoon, blurry, deformed, extra limbs, busy background","resolution":1024,"images":[]}},
 "6":{"class_type":"EmptySD3LatentImage","inputs":{"width":W,"height":H,"batch_size":1}},
 "7":{"class_type":"KSampler","inputs":{"model":["2",0],"seed":seed,"steps":28,"cfg":3.0,"sampler_name":"euler","scheduler":"simple","positive":["5",0],"negative":["5",1],"latent_image":["6",0],"denoise":1.0}},
 "8":{"class_type":"VAEDecode","inputs":{"samples":["7",0],"vae":["4",0]}},
 "9":{"class_type":"SaveImage","inputs":{"images":["8",0],"filename_prefix":out}}
}
req=urllib.request.Request(API+"/prompt",data=json.dumps({"prompt":g,"client_id":uuid.uuid4().hex}).encode(),headers={"Content-Type":"application/json"})
pid=json.load(urllib.request.urlopen(req,timeout=30))["prompt_id"]
print("queued",pid,flush=True)
t0=time.time()
while time.time()-t0<1800:
    time.sleep(5)
    h=json.load(urllib.request.urlopen(f"{API}/history/{pid}",timeout=30))
    if pid in h:
        st=h[pid]["status"]
        if st.get("status_str")=="error":
            print("ERROR",json.dumps(st)[:800]);sys.exit(1)
        imgs=[i for o in h[pid]["outputs"].values() for i in o.get("images",[])]
        print("DONE",round(time.time()-t0),"s",imgs)
        for i in imgs:
            q=f"{API}/view?filename={urllib.parse.quote(i['filename'])}&subfolder={i.get('subfolder','')}&type={i.get('type','output')}"
            data=urllib.request.urlopen(q,timeout=60).read()
            open(i['filename'],'wb').write(data); print("saved",i['filename'],len(data))
        sys.exit(0)
    time.sleep(0)
print("TIMEOUT"); sys.exit(2)
