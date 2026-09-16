from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
root=Path(__file__).resolve().parents[2]
fonts=root/'apps/android/app/src/main/res/font'
S=3
im=Image.new('RGB',(412*S,780*S),'#faf9f7');d=ImageDraw.Draw(im)
def f(name,size):return ImageFont.truetype(str(fonts/name),size*S)
def t(x,y,value,font,color):d.text((x*S,y*S),value,font=font,fill=color,anchor='lt')
# Content viewport 412dp, font scale 1.0. Android system bars excluded.
# Title/button fonts and visual constants are copied from MainActivity.
# Body uses a local substitute for Android's unspecified default system font.
t(16,16,'길안내 시작',f('pretendard_bold.otf',28),'#1b1b1d')
body=ImageFont.truetype('/System/Library/Fonts/AppleSDGothicNeo.ttc',24*S)
y=68
for line in ['구미역까지 안내를 시작할까요?','시작 또는 취소라고 말씀해','주세요.']:
 t(16,y,line,body,'#5c5c64');y+=30
# Status bottom padding 24dp. Button minHeight 144dp, gap 12dp.
y+=24
for label in ['시작','다시 듣기','취소']:
 d.rounded_rectangle((16*S,y*S,396*S,(y+144)*S),radius=16*S,fill='#ffffff',outline='#1b4cd8',width=S)
 d.text((206*S,(y+72)*S),label,font=f('pretendard_medium.otf',24),fill='#1b4cd8',anchor='mm')
 y+=156
im.save(Path(__file__).with_name('start-confirmation-code.png'))
