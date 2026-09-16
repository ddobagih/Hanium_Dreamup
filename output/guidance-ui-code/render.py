from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import re, json, shutil
R=Path(__file__).resolve().parents[2];O=Path(__file__).parent
P=R/'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt';src=P.read_text()
S=2;W=412;BG='#faf9f7';INK='#1b1b1d';BLUE='#1b4cd8'
fonts=R/'apps/android/app/src/main/res/font'
def font(size,bold=False):return ImageFont.truetype(str(fonts/('pretendard_bold.otf' if bold else 'pretendard_medium.otf')),round(size*S))
def checked(s):
 assert s in src,s
 return s
class Screen:
 def __init__(self):
  self.im=Image.new('RGB',(W*S,1800*S),BG);self.d=ImageDraw.Draw(self.im);self.y=24
 def lines(self,s,size,width=372):
  out=[]
  for para in s.split('\n'):
   if not para:out.append('');continue
   line=''
   for c in para:
    if self.d.textlength(line+c,font=font(size))/S>width:out.append(line);line=c
    else:line+=c
   out.append(line)
  return out
 def text(self,s,size=20,color=INK,center=False,gap=12):
  for line in self.lines(s,size):
   self.d.text(((W/2 if center else 20)*S,self.y*S),line,font=font(size),fill=color,anchor='mt' if center else 'lt')
   self.y+=size*1.25
  self.y+=gap
 def box(self,y,h,fill='#fff',border=BLUE):
  self.d.rounded_rectangle((20*S,y*S,392*S,(y+h)*S),radius=16*S,fill=fill,outline=border,width=S)
 def button(self,s,h=144,primary=False,size=22):
  checked(s);self.box(self.y,h,BLUE if primary else '#fff')
  self.d.text((206*S,(self.y+h/2)*S),s,font=font(size),fill='#fff' if primary else BLUE,anchor='mm');self.y+=h+12
 def card(self,s,symbol='•',risk=False):
  lines=self.lines(s,26,340);h=max(224,16+50+len(lines)*29.12+20)
  color='#851b16' if risk else '#102e50'
  self.box(self.y,h,'#ffeee9' if risk else '#eef4fc',color if risk else '#b4c9e3')
  self.d.text((206*S,(self.y+16)*S),symbol,font=font(40),fill=color,anchor='mt')
  cy=self.y+66
  for line in lines:
   self.d.text((206*S,cy*S),line,font=font(26),fill=color,anchor='mt');cy+=29.12
  self.y+=h+12
 def finish(self,name):
  height=max(780,int(self.y+16));self.im.crop((0,0,W*S,height*S)).save(O/(name+'.png'))
  return name
items=[]
def add(name,title,s,note):s.finish(name);items.append(dict(file=name+'.png',title=title,note=note))
s=Screen();s.text(checked('길라잡이'),26,gap=24);s.text(checked('목적지'),20,gap=8);s.box(s.y,72,border='#77747a');s.text(checked('목적지 검색'),20,color='#5c5c64',gap=59);s.button('목적지 검색',primary=True);s.button('검색 취소');s.text(checked('검색어를 입력하세요.'),18)
add('01-search','목적지 검색',s,'검색 가능, 검색어 없음. 키보드·시스템 UI 제외.')
s=Screen();s.text('길라잡이',26,gap=24);s.text('목적지',20,gap=8);s.box(s.y,72,border='#77747a');s.text('구미역',20,gap=59);s.button('목적지 검색',primary=True);s.button('검색 취소');s.box(s.y,200,border='#dedbd5');s.d.text((36*S,(s.y+66)*S),'구미역',font=font(22),fill=INK);s.d.text((36*S,(s.y+104)*S),'주소 없음 · 850 m',font=font(18),fill='#5c5c64');s.y+=212
add('02-results','검색 결과',s,'목적지·주소·거리 값은 샘플. 결과 1개, 더보기 숨김. 전체 스크롤 콘텐츠.')
s=Screen();s.text(checked('목적지'),26,gap=24);s.text('선택한 목적지',22,gap=24);s.text('구미역',20);s.button('안내 시작',primary=True);s.button('안내 취소')
add('03-destination-confirm','목적지 확인 뷰',s,'DESTINATION_CONFIRM 뷰 구성. 실제 진입 경로별 노출 여부는 별도이며 구미역은 샘플.')
shutil.copy(R/'output/guidance-code-review/start-confirmation-code-current.png',O/'05-start-confirm.png')
# Normal guidance statuses use nativeGuidanceStatusMessage/preflight branches, not debug preview labels.
def guide(name,title,message,active=True,paused=False,retry=False,risk=False,note=''):
 s=Screen();s.text('목적지  구미역',20);s.card(message,'!' if risk else 'Ⅱ' if paused else '↱' if '오른쪽' in message else '•',risk)
 s.button('음성 명령',64,True,20)
 if active or paused:
  s.button('다시 듣기',64,size=20);s.button('안내 재개' if paused else '일시정지',64,size=20)
 if retry:s.button('다시 시도',64,size=20)
 s.button('안내 종료' if active or paused else '안내 취소',64,size=20)
 add(name,title,s,note)
pre='\n\n'.join(map(checked,['위치·장착 확인 중','정확한 위치를 아직 확인하지 못했습니다. 위치 설정과 수신 환경을 확인해 주세요.','휴대전화가 흔들립니다. 거치대를 고정해 주세요.']))
guide('04-preparing','위치·장착 확인 중',pre,active=False,note='준비 상태, GPS 미확인·흔들림 조건. 다시 듣기/일시정지 숨김.')
items.append(dict(file='05-start-confirm.png',title='길안내 시작 확인',note='최신 본문 제거 코드. 제목+시작/다시 듣기/취소.'))
guide('06-active','안내 진행 중','50m 앞에서 오른쪽으로 이동하세요.\n\n남은 거리 850 m\n\n'+checked('경로를 따라 안내 중입니다.')+'\n\n'+checked('다시 말해줘 · 보행 일시정지 · 보행 종료'),note='목적지·경로 지시·남은 거리만 샘플 값. 활성 경로, 재시도 없음.')
guide('07-paused','일시정지',checked('안내 일시정지')+'\n\n위치·경로: 경로 안내 실행을 준비하고 있습니다.\n\n'+checked('보행 재개 또는 보행 종료라고 말씀해 주세요.'),paused=True,active=False,note='일시정지 및 경로 준비 메시지 조건 예시. 실제 원인에 따라 내용/다시 시도 노출 변동.')
guide('08-obstacle','장애물 경고','장애물 주의\n\n전방에 장애물이 있습니다.\n잠시 멈춰 주세요.',risk=True,note='유효한 currentGuidanceRisk가 있는 상태. FeedbackAction.message는 예시이며 객체별로 달라짐.')
fail='\n\n'.join(map(checked,['점검이 종료됐습니다. 아래 원인을 해결한 뒤 다시 시도를 눌러 주세요.','위치 요청 실패. 휴대전화 위치 설정과 권한을 확인해 주세요.','카메라 렌즈가 가려져 있습니다. 가림을 제거해 주세요.']))
guide('09-preflight-failure','준비 점검 실패',fail,active=False,retry=True,note='점검 실패+위치 요청 실패+렌즈 가림 조건 예시.')
guide('10-location-wait','안내 중 위치 대기','현재 안내 기능이 제한되어 있습니다.\n\n위치·경로: '+checked('경로 안내에 필요한 정확한 현재 위치를 기다리고 있습니다.')+'\n\n'+checked('다시 말해줘 · 보행 일시정지 · 보행 종료'),retry=True,note='세션 ACTIVE, 신뢰 위치 없음, 카메라 출력 불가, 준비 작업 없음. 정책 제목은 아래 정정 적용.')
# Source policy title has no final period.
p=O/'10-location-wait.png'
# keep visible text faithful by regenerate
items.pop();guide('10-location-wait','안내 중 위치 대기','현재 안내 기능이 제한되어 있습니다\n\n위치·경로: '+checked('경로 안내에 필요한 정확한 현재 위치를 기다리고 있습니다.')+'\n\n'+checked('다시 말해줘 · 보행 일시정지 · 보행 종료'),retry=True,note='세션 ACTIVE, 신뢰 위치 없음, 카메라 출력 불가, 준비 작업 없음.')
items.sort(key=lambda x:x['file'])
# Contact sheet: fixed 780dp preview viewport; individual images retain full scroll content.
thumbW=280;thumbH=530;cw=312;ch=610
sheet=Image.new('RGB',(cw*5,ch*2+105),'#e8ecf1');dr=ImageDraw.Draw(sheet)
dr.text((24,18),'길안내 UI · 현재 코드 재현',font=font(18,True),fill=INK)
dr.text((24,65),'실기기 캡처 아님 / 412dp·글자 1배 / 동적 데이터 예시 / 긴 화면은 개별 PNG 참고',font=font(8),fill='#5c5c64')
for i,v in enumerate(items):
 x=(i%5)*cw+16;y=(i//5)*ch+105
 dr.text((x,y),v['file'][:2]+' '+v['title'],font=font(9),fill=INK)
 im=Image.open(O/v['file']);im=im.crop((0,0,im.width,min(im.height,round(im.width*780/412))));im.thumbnail((thumbW,thumbH))
 sheet.paste(im,(x,y+35))
sheet.save(O/'overview.png')
(O/'manifest.json').write_text(json.dumps(items,ensure_ascii=False,indent=2))
