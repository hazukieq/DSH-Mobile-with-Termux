import sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE=sys.argv[1]; OUT=sys.argv[2]
S=2
W,H=1600,900
F='/usr/share/fonts/opentype/noto/NotoSansCJK-{}.ttc'
MONO='/home/hazukie/.fonts/JetMonos/ttf/JetBrainsMono-{}.ttf'
KAI='/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-{}.ttf'

def f(size, weight='Regular'):
    return ImageFont.truetype(F.format(weight), int(size*S), index=2)
def fk(size, weight='Regular'):
    return ImageFont.truetype(KAI.format(weight), int(size*S))
def fm(size, weight='Regular'):
    return ImageFont.truetype(MONO.format(weight), int(size*S))

BG=(8,9,12); TXT=(247,249,252); SUB=(183,192,207); DIM=(119,128,143); FAINT=(94,104,120)
BLUE=(77,107,254); MINT=(95,227,201)

def fit_base(path):
    im=Image.open(path).convert('RGB')
    # cover 16:9
    tw,th=W*S,H*S
    r=max(tw/im.width, th/im.height)
    im=im.resize((round(im.width*r), round(im.height*r)), Image.LANCZOS)
    x=(im.width-tw)//2; y=(im.height-th)//2
    return im.crop((x,y,x+tw,y+th))

def grad_text(img, xy, value, size, weight, left_color, right_color, tracking=0):
    l,t=xy
    ft=f(size,weight)
    mask=Image.new('L', img.size, 0)
    md=ImageDraw.Draw(mask)
    if tracking:
        x=l*S
        for ch in value:
            md.text((x, t*S), ch, font=ft, fill=255, anchor='lt')
            x+=md.textlength(ch,font=ft)+tracking*S
    else:
        md.text((l*S, t*S), value, font=ft, fill=255, anchor='lt')
    bb=mask.getbbox() or (int(l*S),int(t*S),int((l+900)*S),int((t+size)*S))
    x0,x1=bb[0],bb[2]
    w=max(1,x1-x0)
    xs=np.clip((np.arange(img.width)-x0)/w,0,1).reshape(1,-1,1)
    c0=np.array(left_color,dtype=float); c1=np.array(right_color,dtype=float)
    row=np.clip(c0*(1-xs)+c1*xs,0,255).astype(np.uint8)
    arr=np.repeat(row,img.height,axis=0)
    layer=Image.fromarray(arr).convert('RGBA'); layer.putalpha(mask)
    img.alpha_composite(layer)

def text(d, xy, value, size, fill, weight='Regular', tracking=0, anchor='lt'):
    ft=f(size,weight)
    if tracking:
        x,yy=xy[0]*S,xy[1]*S
        for ch in value:
            d.text((x,yy), ch, font=ft, fill=fill, anchor='lt')
            x+=d.textlength(ch,font=ft)+tracking*S
        return x/S
    d.text((xy[0]*S,xy[1]*S), value, font=ft, fill=fill, anchor=anchor)
    return xy[0]+d.textlength(value,font=ft)/S

def mono(d, xy, value, size, fill, weight='Regular'):
    d.text((xy[0]*S,xy[1]*S), value, font=fm(size,weight), fill=fill, anchor='lt')

def glass(img, rect, radius, alpha=18, outline=(255,255,255,26)):
    l,t,r,b=[int(v*S) for v in rect]
    lay=Image.new('RGBA',(r-l,b-t),(255,255,255,alpha))
    mask=Image.new('L',(r-l,b-t),0)
    ImageDraw.Draw(mask).rounded_rectangle((0,0,r-l,b-t),radius=int(radius*S),fill=255)
    card=Image.new('RGBA',(r-l,b-t),(0,0,0,0))
    card.paste(lay,(0,0),mask)
    d=ImageDraw.Draw(card)
    d.rounded_rectangle((1,1,r-l-2,b-t-2),radius=int(radius*S),outline=outline,width=max(1,S))
    img.alpha_composite(card,(int(l),int(t)))

def whale(size, color):
    import xml.etree.ElementTree as ET, re, cv2
    xml='/home/hazukie/Projects/common/Deepseek-Harness-Local-Android/docs/promo/source/ic_launcher_foreground.xml'
    node=ET.parse(xml).getroot().find('.//path')
    data=node.attrib['{http://schemas.android.com/apk/res/android}pathData']
    tokens=re.findall(r'[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?', data)
    contours=[];pos=np.array([0.,0.]);cur=[];i=0;cmd='';last=None
    while i<len(tokens):
        if tokens[i].isalpha(): cmd=tokens[i]; i+=1
        op=cmd.upper(); rel=cmd.islower()
        if op=='Z':
            if cur: contours.append(np.array(cur))
            pos=np.array(cur[0]);cur=[];cmd='';last=None;continue
        n={'M':2,'L':2,'C':6,'S':4,'H':1,'V':1}[op]
        vals=np.array([float(v) for v in tokens[i:i+n]]); i+=n
        if op in('M','L'):
            p=vals+pos if rel else vals
            if op=='M' and cur: contours.append(np.array(cur));cur=[]
            cur.append(p.copy());pos=p;last=None
            if op=='M': cmd='l' if rel else 'L'
        elif op in('C','S'):
            pts=vals.reshape((-1,2))
            if rel: pts+=pos
            if op=='C': p1,p2,end=pts
            else:
                p1=pos if last is None else 2*pos-last; p2,end=pts
            for t in np.linspace(0,1,24)[1:]:
                cur.append((1-t)**3*pos+3*(1-t)**2*t*p1+3*(1-t)*t**2*p2+t**3*end)
            pos=end.copy();last=p2.copy()
        elif op=='H':
            pos=np.array([pos[0]+vals[0] if rel else vals[0], pos[1]]); cur.append(pos.copy()); last=None
        elif op=='V':
            pos=np.array([pos[0], pos[1]+vals[0] if rel else vals[0]]); cur.append(pos.copy()); last=None
    if cur: contours.append(np.array(cur))
    wp=round(size*S); hp=round(wp*42.2/56.6)
    mask=np.zeros((hp,wp),np.uint8); ratio=wp/56.6
    for c in contours:
        poly=np.rint(c*ratio).astype(np.int32); sh=np.zeros_like(mask)
        cv2.fillPoly(sh,[poly],255,lineType=cv2.LINE_AA); mask=cv2.bitwise_xor(mask,sh)
    im=Image.new('RGBA',(wp,hp),color); im.putalpha(Image.fromarray(mask)); return im

def panel_on_quad(img, quad_px, lines, mono_px=34, pad_px=34, radius_px=26, fill_alpha=168, outline_alpha=52):
    """把一张半透明终端卡按透视贴到四边形上（四边形来自底图玻璃卡）。"""
    import cv2
    pts=np.array(quad_px,dtype=np.float32)
    sm=pts.sum(axis=1); df=np.diff(pts,axis=1).ravel()
    tl=pts[np.argmin(sm)]; br=pts[np.argmax(sm)]
    tr=pts[np.argmin(df)]; bl=pts[np.argmax(df)]
    w=int(max(np.linalg.norm(tr-tl),np.linalg.norm(br-bl)))
    h=int(max(np.linalg.norm(bl-tl),np.linalg.norm(br-tr)))
    P=Image.new('RGBA',(w,h),(0,0,0,0))
    pd=ImageDraw.Draw(P)
    r=radius_px
    if fill_alpha or outline_alpha:
        pd.rounded_rectangle((0,0,w-1,h-1),radius=r,fill=(9,11,15,fill_alpha),outline=(255,255,255,outline_alpha),width=2)
    fmn=lambda sz,wt: ImageFont.truetype(MONO.format(wt), sz)
    ty=pad_px
    for prompt,pcol,rest,tcol in lines:
        x=pad_px
        if prompt:
            pd.text((x,ty),prompt,font=fmn(mono_px,'SemiBold'),fill=pcol,anchor='lt')
            x+=pd.textlength(prompt,font=fmn(mono_px,'SemiBold'))
        if rest:
            pd.text((x,ty),rest,font=fmn(mono_px,'Regular'),fill=tcol,anchor='lt')
        ty+=int(mono_px*1.55)
    src=np.float32([[0,0],[w,0],[w,h],[0,h]])
    M=cv2.getPerspectiveTransform(src,np.float32([tl,tr,br,bl]))
    warp=cv2.warpPerspective(np.array(P), M, (img.width,img.height), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0,0,0,0))
    layer=Image.fromarray(warp)
    img.alpha_composite(layer)

def main():
    base=fit_base(BASE).convert('RGBA')
    # v4：整体提亮——黑底从 #06070a 抬到 ~#1b1e25，保留高光不糊
    arr=np.asarray(base.convert('RGB')).astype(np.float32)
    arr=np.clip(arr*0.92+36,0,255)
    # 轻微降低饱和，避免提亮后发蓝
    lum=arr.mean(axis=2,keepdims=True)
    arr=np.clip(lum+(arr-lum)*0.92,0,255)
    base=Image.fromarray(arr.astype(np.uint8)).convert('RGBA')
    img=base.copy()

    # 左侧压暗，保证文字可读
    veil=Image.new('RGBA',(W*S,H*S),(0,0,0,0))
    dv=ImageDraw.Draw(veil)
    span=int(78*S)
    for x in range(0,span,2):
        a=int(150*(1-x/span)**0.85)
        dv.rectangle((x,0,x+2,H*S),fill=(6,7,10,a))
    img.alpha_composite(veil)

    d=ImageDraw.Draw(img)

    # 品牌眉标
    wm=whale(22,(150,170,255,255))
    _cap=15*0.34           # 15px 文字的大写字母视觉中心约在 +5.1px
    img.alpha_composite(wm,(112*S,int((116+_cap)*S-wm.height/2)))
    tx=text_end=text(d,(144,116),'DEEPSEEK HARNESS',15,(158,168,186),'Medium',3.4)
    # 融合锁记：术语绿 = Termux
    cx=text_end+20
    mono_w=d.textlength('TERMUX',font=fm(14,'SemiBold'))/S
    text(d,(cx-12,116),'×',14,(96,106,124),'Regular',0)
    mono(d,(cx+8,115),'TERMUX',14,(141,240,200),'SemiBold')

    # 主标题
    text(d,(110,176),'DeepSeek',112,TXT,'Bold')
    grad_text(img,(110,290),'on Android.',112,'Bold',(104,132,255),(90,226,198))

    # 分隔线
    d.rectangle((110*S,462*S,182*S,464*S),fill=(62,70,86))

    # 中文（霞鹜文楷）
    d.text((110*S,494*S),'完整的 Agent 引擎，跑在你的手机上。',font=fk(34,'Regular'),fill=SUB,anchor='lt')
    d.text((110*S,552*S),'终端 · 引擎 · 无障碍自动化 —— 一个 APK 全都有。',font=fk(27,'Regular'),fill=DIM,anchor='lt')

    # 玻璃 pills
    pills=['No Root','Termux 内核','本地推理']
    x=110
    for p in pills:
        wt=d.textlength(p,font=f(16,'Medium'))/S
        w=wt+34
        glass(img,(x,604,x+w,604+48),24)
        d.text(((x+18)*S,(604+24)*S), p, font=f(17,'Medium'), fill=(215,222,235), anchor='lm')
        x+=w+12

    # 能力行
    mono(d,(110,704),'scr dump  ·  ppocr6  ·  int8 UI detector',16,(110,120,138))

    # 终端：画一张浮空卡 A；底图那块磨砂玻璃卡作为它错位的玻璃投影（A 与投影倾斜/位置错开叠加）
    mono_px=int(13.2*S)
    lines=[('$ ', (141,240,200,255), 'dsh web', (238,243,252,255)),
           ('',  None, ':3080 ready', (206,217,232,255)),
           ('$ ', (141,240,200,255), 'scr dump', (238,243,252,255)),
           ('',  None, '1080x2376', (206,217,232,255)),
           ('',  None, '37 nodes', (206,217,232,255)),
           ('$ ', (141,240,200,255), '/act tap', (238,243,252,255)),
           ('',  None, 'ok gesture', (120,232,186,255))]
    fmn=lambda sz,wt: ImageFont.truetype(MONO.format(wt), sz)
    pl=int(22*S); lh=int(mono_px*1.6)
    tw=max(sum(ImageDraw.Draw(Image.new('RGBA',(1,1))).textlength(t,font=fmn(mono_px,'Regular')) for t in (p or '', r or '')) for p,_,r,_ in lines)
    cw,ch=int(tw+2*pl), int(lh*len(lines)+2*pl+int(6*S))
    A=Image.new('RGBA',(cw,ch),(0,0,0,0)); ad=ImageDraw.Draw(A)
    # 卡体：深色玻璃 + 细边 + 顶部三点
    ad.rounded_rectangle((0,0,cw-1,ch-1),radius=int(16*S),fill=(10,13,18,214),outline=(255,255,255,40),width=2)
    for i,c in enumerate([(244,112,96,255),(246,190,92,255),(110,214,132,255)]):
        cx0=int(pl+ i*13*S); cy0=int(pl*0.62)
        ad.ellipse((cx0,cy0,cx0+int(5.4*S),cy0+int(5.4*S)),fill=c)
    ty=int(pl*1.55)
    for prompt,pcol,rest,tcol in lines:
        x=pl
        if prompt:
            ad.text((x,ty),prompt,font=fmn(mono_px,'SemiBold'),fill=pcol,anchor='lt')
            x+=ad.textlength(prompt,font=fmn(mono_px,'SemiBold'))
        if rest:
            ad.text((x,ty),rest,font=fmn(mono_px,'Regular'),fill=tcol,anchor='lt')
        ty+=lh
    # 卡 A 的投影（柔黑，偏右下）
    sh=A.split()[3].point(lambda v:int(v*0.55))
    shl=Image.new('RGBA',(cw+int(40*S),ch+int(40*S)),(0,0,0,0))
    shl.paste((0,0,0,150),(int(24*S),int(24*S)),sh)
    shl=shl.filter(ImageFilter.GaussianBlur(7*S))
    Arot=A.rotate(-12.6,resample=Image.BICUBIC,expand=True)
    Shi=shl.rotate(-12.6,resample=Image.BICUBIC,expand=True)
    ax,ay=int(1012*S-Arot.width/2),int(534*S-Arot.height/2)
    img.alpha_composite(Shi,(ax+int(8*S),ay+int(14*S)))
    img.alpha_composite(Arot,(ax,ay))

    # 页脚
    text(d,(110,796),'github.com/hazukieq/DSH-Mobile-with-Termux',16,(126,136,154),'Regular')
    text(d,(110,824),'OPEN SOURCE · MIT · ANDROID',11,(74,82,98),'Medium',2.6)

    # 右下角角标
    mono(d,(1394,822),'v2.5.39',12,(64,72,88))

    final=img.convert('RGB')
    final.save(OUT+'.png')
    final.resize((W*S,H*S),Image.LANCZOS).save(OUT+'@2x.png')
    final.resize((1280,720),Image.LANCZOS).save(OUT+'-web.png')
    # 社交预览 1280x640（2:1）：等比缩放后居中补黑边，不裁掉任何文字
    sw=1600; sh=800
    inner=final.resize((sw,int(sw*final.height/final.width)),Image.LANCZOS)
    social=Image.new('RGB',(sw,sh),(6,7,10))
    social.paste(inner,(0,(sh-inner.height)//2))
    social.resize((1280,640),Image.LANCZOS).save(OUT+'-social.png')
    print('ok',OUT)

main()
