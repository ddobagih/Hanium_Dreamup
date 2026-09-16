from pathlib import Path
import subprocess,base64,json
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parents[2];O=Path(__file__).parent
# Reuse the previous code-derived visual primitives without running its old fixtures.
scope={'__file__':str(R/'output/guidance-ui-code/render.py')}
primitives=(R/'output/guidance-ui-code/render.py').read_text().split('items=[]')[0]
primitives=primitives.replace('font=font(40)', "font=(ImageFont.truetype('/System/Library/Fonts/Apple Symbols.ttf',40*S) if symbol == '◎' else font(40))")
primitives=primitives.replace('(self.y+16)*S', '(self.y+(h-50-len(lines)*29.12)/2)*S')
primitives=primitives.replace('cy=self.y+66', 'cy=self.y+(h-50-len(lines)*29.12)/2+50')
exec(primitives,scope)
scope['O']=O
Screen=scope['Screen'];font=scope['font'];S=scope['S']
java=Path('/Users/luminggi/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home/bin')
classes=R/'apps/android/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
stdlib=Path('/Users/luminggi/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.0/fdfc65fbc42fda253a26f61dac3c0aca335fae96/kotlin-stdlib-2.2.0.jar')
cp=str(classes)+':'+str(stdlib)
tmp=Path('/private/tmp/walksafe-compact-render');tmp.mkdir(exist_ok=True)
subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(tmp),str(O/'ExportCompact.java')],check=True)
cases=[
 ('01-active','안내 진행 중','50m 앞에서 오른쪽으로 이동하세요.\n\n남은 거리 850 m\n\n경로를 따라 안내 중입니다.\n\n다시 말해줘 · 보행 일시정지 · 보행 종료',False,False,True,False),
 ('02-risk','장애물 경고','장애물 주의\n\n전방에 장애물이 있습니다. 잠시 멈춰 주세요.',True,False,True,False),
 ('03-pause','일시정지','안내 일시정지\n\n위치·경로: 경로 안내 실행을 준비하고 있습니다.\n\n보행 재개 또는 보행 종료라고 말씀해 주세요.',False,True,False,False),
 ('04-position','위치 대기','현재 안내 기능이 제한되어 있습니다\n\n위치·경로: 경로 안내에 필요한 정확한 현재 위치를 기다리고 있습니다.\n\n다시 말해줘 · 보행 일시정지 · 보행 종료',False,False,True,True),
 ('05-preparing','위치·장착 점검','위치·장착 확인 중\n\n정확한 위치를 아직 확인하지 못했습니다. 위치 설정과 수신 환경을 확인해 주세요.\n\n휴대전화가 흔들립니다. 거치대를 고정해 주세요.',False,False,False,False),
 ('06-failed','점검 실패','점검이 종료됐습니다. 아래 원인을 해결한 뒤 다시 시도를 눌러 주세요.\n\n위치 요청 실패. 휴대전화 위치 설정과 권한을 확인해 주세요.\n\n카메라 렌즈가 가려져 있습니다. 가림을 제거해 주세요.',False,False,False,True),
]
records=[]
for name,title,detail,risk,paused,active,retry in cases:
 result=subprocess.check_output([str(java/'java'),'-cp',cp+':'+str(tmp),'ExportCompact',base64.b64encode(detail.encode()).decode(),str(risk).lower(),str(paused).lower()],text=True).splitlines()
 symbol=result[0];text=base64.b64decode(result[1]).decode()
 s=Screen();s.text('목적지  구미역',20);s.card(text,symbol,risk)
 s.button('음성 명령',64,True,20)
 if active or paused:
  s.button('다시 듣기',64,size=20);s.button('안내 재개' if paused else '일시정지',64,size=20)
 if retry:s.button('다시 시도',64,size=20)
 s.button('안내 종료' if active or paused else '안내 취소',64,size=20);s.finish(name)
 records.append(dict(file=name+'.png',title=title,symbol=symbol,visual=text,accessible=detail))
(O/'rendered-states.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
sheet=Image.new('RGB',(996,1285),'#e8ecf1');d=ImageDraw.Draw(sheet)
d.text((16,15),'안내 UI 간소화 · 실제 Kotlin 표시 정책 출력',font=font(15,True),fill='#1b1b1d')
d.text((16,55),'코드 재현 이미지 / Android 캡처 아님 / 목적지·거리·상황은 예시',font=font(8),fill='#5c5c64')
for i,r in enumerate(records):
 x=16+(i%3)*332;y=95+(i//3)*590
 d.text((x,y),r['title'],font=font(10),fill='#1b1b1d')
 im=Image.open(O/r['file']);im.thumbnail((300,550));sheet.paste(im,(x,y+32))
sheet.save(O/'overview.png')
