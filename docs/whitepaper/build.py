#!/usr/bin/env python3
"""Build the readable HTML and PDF editions from README.md.

Requires Python 3, reportlab and pandoc. No network resources are loaded.
Run from any directory. The PDF is a derived, versioned snapshot.
"""
from pathlib import Path
import html, re, subprocess, json, math
from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, Flowable, KeepTogether
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.graphics.shapes import Drawing, Rect, Line, String, Polygon, Circle
from reportlab.graphics import renderPDF

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent.parent
SOURCE=(ROOT/'README.md').read_text()
INK='#173E3A'; TEAL='#317C69'; GOLD='#B79853'; MUTED='#687871'; PAPER='#FAF8F2'; LINE='#D8E1D9'
FONTDIR=Path('/System/Library/Fonts/Supplemental')
if (FONTDIR/'Georgia.ttf').exists():
 for name,file in [('Body','Arial.ttf'),('BodyBold','Arial Bold.ttf'),('Display','Georgia.ttf'),('DisplayBold','Georgia Bold.ttf')]:
  pdfmetrics.registerFont(TTFont(name,str(FONTDIR/file)))
 pdfmetrics.registerFontFamily('Body',normal='Body',bold='BodyBold',italic='Body',boldItalic='BodyBold')
else:
 for name,base in [('Body','Helvetica'),('BodyBold','Helvetica-Bold'),('Display','Times-Roman'),('DisplayBold','Times-Bold')]:
  pdfmetrics.registerFont(pdfmetrics.Font(name,base,'WinAnsiEncoding'))
 pdfmetrics.registerFontFamily('Body',normal='Body',bold='BodyBold',italic='Body',boldItalic='BodyBold')

def cc(s):return colors.HexColor(s)
class Diagram:
 def __init__(self,height,title):
  self.h=height;self.title=title;self.d=Drawing(760,height);self.svg=[]
 def rect(self,x,y,w,h,fill=PAPER,stroke=LINE,r=14):
  self.d.add(Rect(x,self.h-y-h,w,h,rx=r,ry=r,fillColor=cc(fill),strokeColor=cc(stroke),strokeWidth=1))
  self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" stroke="{stroke}"/>')
 def text(self,x,y,t,size=15,color=INK,bold=False,anchor='middle'):
  self.d.add(String(x,self.h-y,t,fontName='BodyBold' if bold else 'Body',fontSize=size,fillColor=cc(color),textAnchor=anchor))
  self.svg.append(f'<text x="{x}" y="{y}" font-family="Arial,sans-serif" font-size="{size}" font-weight="{600 if bold else 400}" fill="{color}" text-anchor="{anchor}">{html.escape(t)}</text>')
 def node(self,x,y,w,title,sub='',dark=False):
  self.rect(x,y,w,66,INK if dark else '#FFFFFF',INK if dark else LINE)
  self.text(x+w/2,y+27,title,16,'#FFFFFF' if dark else INK,True)
  self.text(x+w/2,y+48,sub,12,'#DAE8DE' if dark else MUTED)
 def arrow(self,x1,y1,x2,y2,label=''):
  self.d.add(Line(x1,self.h-y1,x2,self.h-y2,strokeColor=cc(TEAL),strokeWidth=1.7))
  a=math.atan2(y2-y1,x2-x1); p=[(x2,y2),(x2-8*math.cos(a-.45),y2-8*math.sin(a-.45)),(x2-8*math.cos(a+.45),y2-8*math.sin(a+.45))]
  self.d.add(Polygon([v for x,y in p for v in (x,self.h-y)],fillColor=cc(TEAL),strokeColor=None))
  self.svg.append(f'<path d="M{x1},{y1} L{x2},{y2}" stroke="{TEAL}" stroke-width="1.7"/><polygon points="'+ ' '.join(f'{x},{y}' for x,y in p)+f'" fill="{TEAL}"/>')
  if label:self.text((x1+x2)/2+8,(y1+y2)/2-8,label,12,MUTED)
 def save(self,n):
  value=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 {self.h}" role="img" aria-label="{html.escape(self.title)}"><title>{html.escape(self.title)}</title>'+''.join(self.svg)+'</svg>'
  (ROOT/'figures'/f'{n:02}.svg').write_text(value);return value

def diagrams():
 out=[]
 d=Diagram(420,'From a human decision to ledger-enforced rules')
 d.node(245,12,270,'Kavach dashboard','Choose action and review')
 d.node(20,124,210,'Cardano wallet','Sign intents or transactions');d.node(265,124,230,'Backend + SDK','Resolve, build and evaluate');d.node(530,124,210,'iPhone Companion','Independent COSE approval')
 d.arrow(330,78,145,124);d.arrow(380,78,380,124);d.arrow(430,78,635,124)
 d.arrow(230,157,265,157);d.arrow(530,157,495,157)
 d.node(245,244,270,'Cardano ledger','Validate the full transaction',True);d.arrow(380,190,380,244,'submit')
 d.text(380,355,'Account state  •  Assets  •  Authorization rules',17,INK,True)
 d.text(380,389,'Private spending keys stay with the signers.',14,MUTED);out.append(d)
 d=Diagram(365,'An account persists while its credentials can change')
 d.node(15,30,225,'Account identity','Unique state NFT');d.node(280,30,220,'Canonical state','Version, mode and policy');d.arrow(240,63,280,63)
 d.node(535,30,210,'Authorization module','Configured keys and roles');d.arrow(500,63,535,63)
 d.node(15,185,225,'Asset UTxOs','Account-controlled value');d.node(280,185,220,'Immutable core','Identity and value checks',True);d.arrow(240,218,280,218);d.arrow(390,96,390,185,'read state')
 d.node(535,185,210,'Valid outputs','Recipients and change');d.arrow(500,218,535,218)
 d.text(380,307,'Change a supported key or module. Keep the account address.',17,INK,True);d.text(380,337,'Changing the immutable core is a separate migration question.',13,MUTED);out.append(d)
 d=Diagram(315,'Three distinct stages: approve, fund, confirm')
 for x,num,title,sub in [(15,'01','Approve intent','Wallet or iPhone keys'),(275,'02','Fund transaction','Fee wallet signs'),(535,'03','Confirm on-chain','Ledger accepts the result')]:
  d.text(x+105,40,num,28,GOLD,True);d.node(x,66,210,title,sub,x==535)
 d.arrow(225,99,275,99);d.arrow(485,99,535,99)
 d.text(120,176,'One proof per required',13,MUTED);d.text(120,196,'key and purpose.',13,MUTED)
 d.text(380,176,'Funding cannot replace',13,MUTED);d.text(380,196,'a COSE approval.',13,MUTED)
 d.text(640,176,'A signature alone does',13,MUTED);d.text(640,196,'not move funds.',13,MUTED)
 d.rect(15,235,730,58,'#EEF3E9');d.text(380,270,'Mixed transaction-witness profiles also need their authority witnesses.',13,INK);out.append(d)
 d=Diagram(400,'Proposed network execution with user-controlled account authority')
 d.text(380,23,'PROPOSED  /  NOT A DEPLOYED NETWORK',12,GOLD,True)
 d.node(20,60,210,'User approval','Phone or supported wallet');d.node(280,60,220,'Exact Kavach intent','Account policy satisfied');d.arrow(230,93,280,93)
 d.node(265,175,250,'Execution providers','Discover, quote, build and fund',True);d.arrow(390,126,390,175)
 d.node(535,175,210,'Cardano ledger','Validate and confirm');d.arrow(515,208,535,208)
 d.text(130,198,'Account authority stays',14,INK,True);d.text(130,220,'with the user.',14,INK,True)
 d.rect(20,292,720,77,'#EEF3E9');d.text(380,323,'A simpler experience: review the action, approve, receive confirmation.',15,INK,True)
 d.text(380,347,'Funding and execution are replaceable. Signed instructions are not.',13,MUTED);out.append(d)
 d=Diagram(290,'Three setup phases, ten current ledger transactions')
 for x,num,title,sub in [(15,'01–06','Prepare','5 publications + registration'),(275,'07','Enroll','Genesis and key possession'),(535,'08–10','Activate','Final module and approvals')]:
  d.text(x+105,40,num,22,GOLD,True);d.node(x,67,210,title,sub,x==535)
 d.arrow(225,100,275,100);d.arrow(485,100,535,100)
 d.rect(15,180,730,75,'#EEF3E9');d.text(380,212,'Spending stays blocked until final activation.',17,INK,True);d.text(380,237,'The setup module can activate only its precommitted final policy.',13,MUTED);out.append(d)
 d=Diagram(445,'Optional budgets enforce one account-wide ADA cap')
 d.node(260,10,240,'Approved payment','Check budget configuration')
 d.node(20,135,290,'Budget disabled','Independent inputs can be concurrent');d.node(450,135,290,'Budget enabled','Check period and remaining capacity')
 d.arrow(300,76,165,135);d.arrow(460,76,595,135)
 d.node(450,272,290,'Spend + update counter','One atomic ledger transaction',True);d.arrow(595,201,595,272,'within cap')
 d.text(165,272,'No shared counter to consume.',13,MUTED);d.text(595,381,'Over the cap? Reject.',16,INK,True);d.text(380,421,'Competing enabled spends share one counter; a stale request must be rebuilt.',13,MUTED);out.append(d)
 d=Diagram(365,'Lifecycle transitions and independent recovery controls')
 d.node(20,35,200,'Normal','Spending allowed',True);d.node(540,35,200,'Frozen','Spending blocked')
 d.arrow(220,56,540,56,'freeze');d.arrow(540,86,220,86,'independent unfreeze')
 d.node(245,213,270,'Recovery pending','Exact target + immutable delay');d.arrow(120,101,270,213,'start');d.arrow(640,101,490,213,'start')
 d.arrow(260,245,90,112);d.text(96,200,'complete after delay',12,MUTED)
 d.arrow(510,246,708,114);d.text(674,210,'cancel',12,MUTED)
 d.text(380,334,'Full recovery qualification remains open.',16,INK,True);out.append(d)
 return out

(ROOT/'figures').mkdir(exist_ok=True)
DIAGRAMS=diagrams();SVGS=[d.save(i+1) for i,d in enumerate(DIAGRAMS)]

# HTML is a self-contained reading edition. Repository links resolve to this edition's baseline.
def web_link(target):
 if target.startswith('#'):return re.sub(r'^#\d+-','#',target)
 if target.startswith(('http:','https:','mailto:')):return target
 path=(ROOT/target.split('#')[0]).resolve();frag=('#'+target.split('#',1)[1]) if '#' in target else ''
 revision='main' if path.name=='adr-010-intent-execution-and-fee-sponsorship.md' else 'f2b9420'
 return 'https://github.com/bloxbean/kavach/blob/'+revision+'/'+str(path.relative_to(REPO))+frag
blocks=iter(SVGS)
webmd=re.sub(r'```mermaid\n.*?```',lambda m:'\n'+next(blocks)+'\n',SOURCE,flags=re.S)
body=subprocess.check_output(['pandoc','-f','markdown+raw_html','-t','html5'],input=webmd,text=True)
body=re.sub(r'href="([^"]+)"',lambda m:'href="'+html.escape(web_link(html.unescape(m.group(1))),quote=True)+'"',body)
sections=re.findall(r'<h2 id="([^"]+)">([^<]+)</h2>',body)
nav=''.join(f'<a href="#{id}">{title}</a>' for id,title in sections if re.match(r'\d+\.',title))
css='''
:root{--ink:#173e3a;--muted:#63776f;--paper:#faf8f2;--green:#317c69;--line:#d8e1d9}*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;color:var(--ink);background:var(--paper);font:17px/1.8 system-ui,-apple-system,sans-serif}a{color:#286c59;text-underline-offset:4px}header{background:var(--ink);color:#f5f0df;padding:90px max(7vw,24px);position:relative;overflow:hidden}header:after{content:"";position:absolute;width:650px;height:650px;border:1px solid #719b8338;border-radius:50%;right:-150px;top:-130px;box-shadow:0 0 0 70px #719b8310,0 0 0 140px #719b8310;pointer-events:none}.eyebrow{font-size:12px;letter-spacing:.2em;text-transform:uppercase;color:#bccea6}.brand{font-size:24px;letter-spacing:-1px;margin:0 0 70px}header h1{font:clamp(52px,7vw,100px)/1.08 Georgia,serif;letter-spacing:-.045em;font-weight:400;max-width:850px;margin:25px 0}header p{max-width:540px;color:#d5e0d0;font-size:19px}header .meta{margin-top:60px;font-size:12px;letter-spacing:.1em;color:#c6bc96}.layout{display:grid;grid-template-columns:250px minmax(0,850px);gap:70px;max-width:1270px;margin:auto;padding:70px 30px}nav{position:sticky;top:25px;align-self:start;font-size:12px;line-height:1.5;max-height:90vh;overflow:auto}nav strong{display:block;letter-spacing:.13em;text-transform:uppercase;margin-bottom:18px;color:#768679}nav a{display:block;padding:7px 0;text-decoration:none;color:#5d7065}nav a:hover{color:#173e3a}main{min-width:0}main>h1,main>h2:first-of-type{display:none}h2{font:40px/1.2 Georgia,serif;letter-spacing:-.035em;padding-top:55px;margin:60px 0 25px;border-top:1px solid var(--line);scroll-margin-top:25px}h3{font:25px/1.35 Georgia,serif;margin:36px 0 14px}p{margin:16px 0}strong{font-weight:650}blockquote{margin:30px 0;padding:18px 28px;border-left:3px solid #b79853;background:#f0f1e7;font:21px/1.6 Georgia,serif}blockquote p{margin:12px 0}table{width:100%;border-collapse:collapse;font-size:13px;line-height:1.55;margin:24px 0 36px}th{text-align:left;background:var(--ink);color:#fff;font-weight:550;padding:13px 15px}td{border-bottom:1px solid var(--line);padding:13px 15px;vertical-align:top}tr:nth-child(even) td{background:#f1f3ed}td:first-child{font-weight:550;min-width:120px}pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:22px;background:#edf2e9;border-radius:8px;font:13px/1.6 ui-monospace,monospace}code{font-size:.85em;overflow-wrap:anywhere}svg{width:100%;height:auto;display:block;margin:32px 0 12px;border:1px solid var(--line);border-radius:16px;background:#f4f6ef;padding:14px}svg+p{font-size:13px;color:var(--muted);line-height:1.6;margin-bottom:36px}li{padding:4px 0}hr{border:0;border-top:1px solid var(--line);margin:40px 0}footer{background:#e9eee3;padding:45px 7vw;font-size:13px}#reading-progress{height:3px;position:fixed;top:0;left:0;background:#b79853;z-index:5;width:0}button{border:1px solid #a9b8a5;background:transparent;border-radius:20px;padding:9px 15px;color:inherit;cursor:pointer;margin-top:20px}a:focus-visible,button:focus-visible{outline:2px solid #b79853;outline-offset:4px}@media(max-width:900px){.layout{display:block;padding:30px 22px}nav{position:static;max-height:none;border-bottom:1px solid var(--line);padding-bottom:22px;margin-bottom:35px;columns:2}nav strong{column-span:all}header{padding:55px 25px}header .brand{margin-bottom:50px}h2{font-size:32px}table{font-size:12px}th,td{padding:9px}}@media print{nav,#reading-progress,button{display:none}.layout{display:block;padding:0}body{font-size:10pt}header{break-after:page;padding:50px}h2{break-before:page}table{font-size:9pt}tr,svg,blockquote{break-inside:avoid}}
'''
html_doc=f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Kavach — Living white paper v0.1</title><style>{css}</style></head><body><div id="reading-progress"></div><header><div class="brand">kavach.</div><div class="eyebrow">Living white paper · Version 0.1</div><h1>Your account.<br>Your rules.<br>Beyond one key.</h1><p>Programmable accounts for Cardano. A practical guide to what exists, how it is protected, and what comes next.</p><div class="meta">09 SEPTEMBER 2026 · DEVELOPMENT EDITION</div></header><div class="layout"><nav aria-label="Chapters"><strong>Inside this paper</strong>{nav}<button onclick="window.print()">Print this edition</button></nav><main>{body}</main></div><footer><strong>Kavach · v0.1</strong><br>Based on source f2b9420. Development candidate; not an independent audit or production qualification.<br>Canonical source: docs/whitepaper/README.md</footer><script>addEventListener('scroll',()=>{{const d=document.documentElement;document.getElementById('reading-progress').style.width=(100*d.scrollTop/(d.scrollHeight-d.clientHeight))+'%'}},{{passive:true}});</script></body></html>'''
(ROOT/'kavach-whitepaper.html').write_text(html_doc)

# PDF: vector diagrams, embedded fonts, repeatable table headings, page furniture.
styles={
 'body':ParagraphStyle('body',fontName='Body',fontSize=9.5,leading=14.7,textColor=cc(INK),spaceAfter=9),
 'h2':ParagraphStyle('h2',fontName='Display',fontSize=25,leading=30,textColor=cc(INK),spaceAfter=20,keepWithNext=True),
 'h3':ParagraphStyle('h3',fontName='Display',fontSize=15,leading=20,textColor=cc(INK),spaceBefore=15,spaceAfter=10,keepWithNext=True),
 'small':ParagraphStyle('small',fontName='Body',fontSize=8,leading=11.5,textColor=cc(MUTED),spaceAfter=9),
 'cell':ParagraphStyle('cell',fontName='Body',fontSize=8,leading=11.5,textColor=cc(INK)),
 'th':ParagraphStyle('th',fontName='BodyBold',fontSize=8,leading=11.5,textColor=colors.white),
 'quote':ParagraphStyle('quote',fontName='Display',fontSize=12,leading=18,textColor=cc(INK),backColor=cc('#EEF1E5'),borderPadding=12,spaceBefore=12,spaceAfter=16),
 'code':ParagraphStyle('code',fontName='Courier',fontSize=8,leading=12,backColor=cc('#EDF2E9'),borderPadding=10,spaceBefore=8,spaceAfter=15),
}
def inline(s):
 s=html.escape(s)
 s=re.sub(r'\[([^\]]+)\]\(([^)]+)\)',lambda m:('<link color="#317C69" href="'+html.escape(web_link(html.unescape(m.group(2))),quote=True)+'">'+m.group(1)+'</link>') if not m.group(2).startswith('#') else m.group(1),s)
 s=re.sub(r'`([^`]+)`',r'<font name="Courier">\1</font>',s)
 s=re.sub(r'\*\*([^*]+)\*\*',r'<b>\1</b>',s)
 s=re.sub(r'\*([^*]+)\*',r'<i>\1</i>',s)
 return s.replace('→','&#8594;')
def para(s,kind='body'):return Paragraph(inline(s).replace('\n','<br/>') if kind=='code' else inline(s),styles[kind])
class Figure(Flowable):
 def __init__(self,d):super().__init__();self.d=d;self.width=475;self.height=d.h*475/760+12
 def draw(self):
  self.canv.saveState();self.canv.scale(475/760,475/760);renderPDF.draw(self.d.d,self.canv,0,0);self.canv.restoreState()
class Cover(Flowable):
 def __init__(self):super().__init__();self.width=475;self.height=695
 def draw(self):
  c=self.canv;c.setFillColor(cc(INK));c.rect(-66,-90,630,920,fill=1,stroke=0)
  c.setStrokeColor(cc('#396057'))
  for r in [100,155,210,265]:c.circle(450,510,r,stroke=1,fill=0)
  c.setFillColor(cc('#DCE6D0'));c.setFont('BodyBold',23);c.drawString(0,656,'kavach.')
  c.setFillColor(cc('#C4C597'));c.setFont('Body',9);c.drawString(0,559,'LIVING WHITE PAPER  /  VERSION 0.1')
  c.setFillColor(cc('#FAF8EF'));c.setFont('Display',46)
  for y,t in [(475,'Your account.'),(417,'Your rules.'),(359,'Beyond one key.')]:c.drawString(0,y,t)
  c.setFont('Body',13);c.drawString(0,286,'Programmable accounts for Cardano.')
  c.setFillColor(cc('#CBDAC8'));c.setFont('Body',10)
  for y,t in [(254,'What exists. How it is protected. What comes next.'),(92,'09 SEPTEMBER 2026  /  DEVELOPMENT EDITION'),(69,'Source baseline f2b9420'),(32,'Not independently audited or production qualified.')]:c.drawString(0,y,t)
class Doc(SimpleDocTemplate):
 def afterFlowable(self,flowable):
  if isinstance(flowable,Paragraph) and flowable.style.name=='h2':
   t=flowable.getPlainText();key='chapter'+str(self.page);self.canv.bookmarkPage(key);self.canv.addOutlineEntry(t,key,0,False)
def furniture(c,doc):
 if doc.page==1:return
 c.saveState();w,h=doc.pagesize;c.setStrokeColor(cc(LINE));c.line(60,h-46,w-60,h-46)
 c.setFont('BodyBold',8);c.setFillColor(cc(INK));c.drawString(60,h-34,'KAVACH')
 c.setFont('Body',8);c.setFillColor(cc(MUTED));c.drawRightString(w-60,h-34,'WHITE PAPER  /  0.1')
 c.line(60,43,w-60,43);c.setFont('Body',7);c.drawString(60,29,'Development edition • 9 September 2026 • f2b9420');c.drawRightString(w-60,29,f'{doc.page:02d}');c.restoreState()
story=[Cover(),PageBreak(),para('About this edition','h2')]
lines=SOURCE.splitlines();i=3;fig=0
while i<len(lines):
 line=lines[i].strip()
 if not line or line=='---':i+=1;continue
 if line.startswith('```'):
  typ=line[3:];chunk=[];i+=1
  while i<len(lines) and not lines[i].startswith('```'):chunk.append(lines[i]);i+=1
  i+=1
  if typ=='mermaid':
   caption=i
   while caption<len(lines) and not lines[caption].strip():caption+=1
   if caption<len(lines) and lines[caption].startswith('*Figure'):
    story.append(KeepTogether([Figure(DIAGRAMS[fig]),para(lines[caption],'small')]));i=caption+1
   else:story.append(Figure(DIAGRAMS[fig]))
   fig+=1
  else:story.append(para('<br/>'.join(chunk).replace('<br/>','\n'),'code'))
  continue
 if line.startswith('## '):
  story.extend([PageBreak() if re.match(r'^## 1\.',line) else Spacer(1,28),para(line[3:],'h2')]);i+=1;continue
 if line.startswith('#### '):story.append(para(line[5:],'h3'));i+=1;continue
 if line.startswith('### '):
  if line=='### Contents':story.append(PageBreak())
  story.append(para(line[4:],'h3'));i+=1;continue
 if line.startswith('|'):
  rows=[]
  while i<len(lines) and lines[i].strip().startswith('|'):
   cells=[c.strip() for c in lines[i].strip().strip('|').split('|')]
   if not all(re.fullmatch(r'[: -]+',c) for c in cells):rows.append(cells)
   i+=1
  n=len(rows[0]);widths=[475*.31,475*.69] if n==2 else [475*.24]+[475*.76/(n-1)]*(n-1)
  tbl=Table([[para(c,'th' if ri==0 else 'cell') for c in r] for ri,r in enumerate(rows)],colWidths=widths,repeatRows=1,hAlign='LEFT')
  tbl.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),cc(INK)),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),9),('RIGHTPADDING',(0,0),(-1,-1),9),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),8),('LINEBELOW',(0,1),(-1,-1),.4,cc(LINE)),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,cc('#F2F4EC')])]))
  story.extend([tbl,Spacer(1,13)]);continue
 if line.startswith('>'):
  chunk=[]
  while i<len(lines) and lines[i].startswith('>'):chunk.append(lines[i].lstrip('> '));i+=1
  story.append(para(' '.join(chunk),'quote'));continue
 if re.match(r'^(- |\d+\. )',line):
  story.append(para(('• '+line[2:]) if line.startswith('- ') else line));i+=1;continue
 chunk=[line];i+=1
 while i<len(lines) and lines[i].strip() and not re.match(r'^(#|\||>|```|- |\d+\. )',lines[i]):chunk.append(lines[i].strip());i+=1
 text=' '.join(chunk);story.append(para(text,'small' if text.startswith('*Figure') else 'body'))
PDF=REPO/'output/pdf/kavach-whitepaper-v0.1.pdf';PDF.parent.mkdir(parents=True,exist_ok=True)
doc=Doc(str(PDF),pagesize=(595.28,841.89),rightMargin=60,leftMargin=60,topMargin=66,bottomMargin=60,title='Kavach — Living white paper and specification v0.1',author='Kavach',pageCompression=1)
# Keep a heading chain with the first visual or table, including its caption.
polished=[];j=0
while j<len(story):
 if isinstance(story[j],Paragraph) and story[j].style.name in ('h2','h3'):
  group=[]
  while j<len(story) and isinstance(story[j],Paragraph) and story[j].style.name in ('h2','h3'):
   group.append(story[j]);j+=1
  if j<len(story) and isinstance(story[j],KeepTogether):
   group.extend(story[j]._content);j+=1
  elif j<len(story) and not isinstance(story[j],PageBreak):group.append(story[j]);j+=1
  polished.append(KeepTogether(group))
 else:polished.append(story[j]);j+=1
doc.build(polished,onFirstPage=furniture,onLaterPages=furniture)
print('Built HTML, seven SVG figures, and',PDF)
