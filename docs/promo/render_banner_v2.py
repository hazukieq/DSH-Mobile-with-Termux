import sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE=sys.argv[1]; OUT=sys.argv[2]
S=2
W,H=1600,900
F='/usr/share/fonts/opentype/noto/NotoSansCJK-{}.ttc'
MONO='/home/hazukie/.fonts/JetMonos/ttf/JetBrainsMono-{}.ttf'

def f(size, weight='Regular'):
    return ImageFont.truetype(F.format(weight), int(size*S), index=2 if 'SC' not in weight else 2)
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

def grad_text(img, xy, value, size, weight, top, bottom, tracking=0):
    l,t=xy
    ft=f(size,weight)
    mask=Image.new('L', img.size, 0)
    d=ImageDraw.Draw(mask)
    x=l*S
    for ch in value:
        d.text((x, t*S), ch, font=ft, fill=255, anchor='lt')
        x+=d.textlength(ch,font=ft)+tracking*S
    bb=mask.getbbox() or (int(l*S),int(t*S),int((l+600)*S),int((t+size)*S))
    y0,y1=bb[1],bb[3]
    h=max(1,y1-y0)
    arr=np.zeros((img.height,img.width,3),np.uint8)
    c0=np.array(top,dtype=float); c1=np.array(bottom,dtype=float)
    ys=np.clip((np.arange(img.height)-y0)/h,0,1).reshape(-1,1,1)
    col=np.clip(c0*(1-ys)+c1*ys,0,255).astype(np.uint8)
    arr[:]=col
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

def main():
    base=fit_base(BASE).convert('RGBA')
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
    img.alpha_composite(wm,(112*S,120*S))
    tx=text_end=text(d,(144,116),'DEEPSEEK HARNESS',15,(158,168,186),'Medium',3.4)
    # 融合锁记：术语绿 = Termux
    cx=text_end+20
    mono_w=d.textlength('TERMUX',font=fm(14,'SemiBold'))/S
    glass(img,(cx-12,108,cx+mono_w+14,140),16,alpha=16)
    text(d,(cx-12,116),'×',14,(96,106,124),'Regular',0)
    mono(d,(cx+8,115),'TERMUX',14,(141,240,200),'SemiBold')

    # 主标题
    text(d,(110,176),'DeepSeek',112,TXT,'Bold')
    grad_text(img,(110,290),'on Android.',112,'Bold',(96,124,255),(88,228,196))

    # 分隔线
    d.rectangle((110*S,462*S,182*S,464*S),fill=(62,70,86))

    # 中文
    text(d,(110,498),'完整的 Agent 引擎，跑在你的手机上。',29,SUB,'Regular')
    text(d,(110,546),'终端 · 引擎 · 无障碍自动化 —— 一个 APK 全都有。',20,DIM,'Regular')

    # 玻璃 pills
    pills=['No Root','Termux 内核','本地推理']
    x=110
    for p in pills:
        wt=d.textlength(p,font=f(16,'Medium'))/S
        w=wt+34
        glass(img,(x,606,x+w,606+46),23)
        text(d,(x+17,619),p,16,(215,222,235),'Medium')
        x+=w+12

    # 能力行
    mono(d,(110,700),'scr dump  ·  ppocr6  ·  int8 UI detector',15,(104,114,132))

    # 页脚
    text(d,(110,796),'github.com/hazukieq/DSH-Mobile-with-Termux',15,(122,132,150),'Regular')
    text(d,(110,824),'OPEN SOURCE · MIT · ANDROID',11,(74,82,98),'Medium',2.6)

    # 右下角角标
    mono(d,(1400,824),'v2.5.38',12,(64,72,88))

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
