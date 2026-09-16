from pathlib import Path
import re,json
from PIL import Image,ImageDraw,ImageFont
R=Path(__file__).resolve().parents[2];O=Path(__file__).parent
main=(R/'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt').read_text()
guard=(R/'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/GuidanceTouchGuardView.kt').read_text()
S=2;W=412;H=780
fonts=R/'apps/android/app/src/main/res/font'
def f(size,system=False):return ImageFont.truetype('/System/Library/Fonts/AppleSDGothicNeo.ttc' if system else str(fonts/'pretendard_medium.otf'),size*S)
def color(name):return '#'+re.search(r'const val '+name+r' = 0xff([a-fA-F0-9]{6})',main).group(1)
bg=color('WS_COLOR_GROUND');blue=color('WS_COLOR_PRIMARY_ACTION_FILL')
def canvas():
 im=Image.new('RGB',(W*S,H*S),bg);return im,ImageDraw.Draw(im)
def button(d,x,y,w,h,label,size,fill,textcolor,radius,outline=None,system=False):
 d.rounded_rectangle((x*S,y*S,(x+w)*S,(y+h)*S),radius=radius*S,fill=fill,outline=outline,width=S)
 d.text(((x+w/2)*S,(y+h/2)*S),label,font=f(size,system),fill=textcolor,anchor='mm')
assert 'button.minHeight = dp(if (hasWalk) 144' in main
assert 'nativeGuidanceRepeatButton.visibility = View.GONE' in main
im,d=canvas()
labels=['음성 명령','일시정지','안내 종료',re.search(r'label = "(터치 오작동 방지)"',main).group(1)]
for i,label in enumerate(labels):
 assert label in main
 button(d,20,24+i*156,372,144,label,24,blue if i==0 else '#ffffff','#ffffff' if i==0 else blue,16,None if i==0 else blue)
im.save(O/'01-guidance.png')
title=re.search(r'addView\(TextView\(context\).apply \{\s*text = "([^"]+)"',guard).group(1)
normal=re.search(r'releaseButton.apply \{\s*text = "([^"]+)"',guard).group(1)
held=re.search(r'releaseButton.text = "([^"]+)"',guard).group(1)
height=int(re.search(r'minimumHeight = dp\((\d+)\)',guard).group(1))
title_height=sum(f(28,True).getmetrics())/S
y=(H-(title_height+32+height))/2
for name,label in [('02-protected',normal),('03-holding',held)]:
 im=Image.new('RGB',(W*S,H*S),'#000000');d=ImageDraw.Draw(im);d.text((W*S/2,y*S),title,font=f(28,True),fill='#ffffff',anchor='mt')
 button(d,24,y+title_height+32,364,height,label,26,blue,'#ffffff',20,system=True)
 im.save(O/(name+'.png'))
sheet=Image.new('RGB',(1296,900),'#e8ecf1');d=ImageDraw.Draw(sheet)
d.text((20,12),'현재 코드 기반 UI',font=f(17),fill='#1b1b1d')
d.text((20,55),'412dp · 글자 1배 · Android 캡처 아님 · 시스템 글꼴/패딩은 기기와 차이가 있을 수 있음',font=f(8),fill='#5c5c64')
for i,(name,title) in enumerate([('01-guidance','안내 중'),('02-protected','터치 오작동 방지'),('03-holding','중앙 버튼을 누르는 동안')]):
 x=12+i*432;d.text((x,95),title,font=f(10),fill='#1b1b1d')
 thumb=Image.open(O/(name+'.png'));thumb.resize((400,757)).save(O/(name+'-preview.png'));sheet.paste(thumb.resize((400,757)),(x,130))
sheet.save(O/'overview.png')
(O/'README.md').write_text('''# 코드 기반 안내/터치 보호 화면
MainActivity.kt의 안내 활성 상태: 목적지/안내 카드/다시 듣기/재시도 숨김, 144dp 버튼 4개, 24sp, 여백20/24dp, 간격12dp.
GuidanceTouchGuardView.kt: 중앙 정렬 세로 그룹, 제목28sp, 간격32dp, 버튼200dp·26sp, 여백24dp. 누르는 동안 라벨만 변경되며 별도 카운트다운 바는 코드에 없음.
412×780dp 콘텐츠 영역, 글자 배율1, 시스템바 제외. 안내 버튼에는 프로젝트 Pretendard Medium 사용. 보호 화면은 Android 기본 시스템 글꼴 지정이므로 로컬 한국어 시스템 글꼴로 대체. Android 런타임 캡처가 아니며 폰트 패딩과 실제 기기 렌더링에는 차이가 있음.
''')
