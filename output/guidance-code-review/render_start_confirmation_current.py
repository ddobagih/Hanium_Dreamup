from pathlib import Path
import re
from PIL import Image, ImageDraw, ImageFont
root=Path(__file__).resolve().parents[2]
source=(root/'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt').read_text()
block=source.split('private fun requestNativeGuidanceStartConfirmation(action: () -> Unit) {',1)[1].split('private fun removeNativeGuidanceConfirmationScreen()',1)[0]
assert 'visibility = View.GONE' in block
labels=re.search(r'listOf\("시작", "다시 듣기", "취소"\)',block).group()
labels=re.findall(r'"([^"]+)"',labels)
title=re.search(r'text = "([^"]+)"',block).group(1)
title_size=int(re.search(r'textSize = (\d+)f',block).group(1))
button_size=int(re.findall(r'textSize = (\d+)f',block)[-1])
button_height=int(re.search(r'private fun applyWsSecondaryButtonStyle\(button: Button\) \{\s*applyWsButtonStyle\(button, (\d+)f\)',source).group(1))
def color(name):return '#'+re.search(r'const val '+name+r' = 0xff([a-fA-F0-9]{6})',source).group(1)
radius=int(re.search(r'const val WS_CORNER_RADIUS_DP = (\d+)f',source).group(1))
S=3;W=412;H=780
fonts=root/'apps/android/app/src/main/res/font'
def font(name,size):return ImageFont.truetype(str(fonts/name),size*S)
im=Image.new('RGB',(W*S,H*S),color('WS_COLOR_GROUND'));d=ImageDraw.Draw(im)
heading=font('pretendard_bold.otf',title_size)
ascent,descent=heading.getmetrics()
# Specified 16dp content padding and 16dp items padding; line box from font metrics.
d.text((16*S,16*S),title,font=heading,fill=color('WS_COLOR_EMPHASIS'),anchor='lt')
y=16+(ascent+descent)/S+16
for label in labels:
 d.rounded_rectangle((16*S,round(y*S),(W-16)*S,round((y+button_height)*S)),radius=radius*S,fill=color('WS_COLOR_BUTTON_FILL'),outline=color('WS_COLOR_PRIMARY_ACTION_FILL'),width=S)
 d.text((W*S/2,(y+button_height/2)*S),label,font=font('pretendard_medium.otf',button_size),fill=color('WS_COLOR_PRIMARY_ACTION_FILL'),anchor='mm')
 y+=button_height+12
im.save(Path(__file__).with_name('start-confirmation-code-current.png'))
