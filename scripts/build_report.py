from pathlib import Path
import re, html
from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, KeepTogether, Preformatted
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT=Path(__file__).resolve().parents[1]
FONT=Path('/System/Library/Fonts/Supplemental')
for name,file in [('TimesRU','Times New Roman.ttf'),('TimesRU-Bold','Times New Roman Bold.ttf'),('TimesRU-Italic','Times New Roman Italic.ttf'),('TimesRU-BoldItalic','Times New Roman Bold Italic.ttf'),('MonoRU','Courier New.ttf')]:
    pdfmetrics.registerFont(TTFont(name,str(FONT/file)))
pdfmetrics.registerFontFamily('TimesRU',normal='TimesRU',bold='TimesRU-Bold',italic='TimesRU-Italic',boldItalic='TimesRU-BoldItalic')
S={
'body':ParagraphStyle('body',fontName='TimesRU',fontSize=12,leading=16.4,spaceAfter=7,alignment=TA_JUSTIFY,splitLongWords=True),
'h2':ParagraphStyle('h2',fontName='TimesRU-Bold',fontSize=15,leading=19,spaceBefore=15,spaceAfter=9,keepWithNext=True),
'h3':ParagraphStyle('h3',fontName='TimesRU-Bold',fontSize=12.5,leading=16,spaceBefore=10,spaceAfter=7,keepWithNext=True),
'cell':ParagraphStyle('cell',fontName='TimesRU',fontSize=9.3,leading=12,spaceAfter=0,splitLongWords=True),
'headcell':ParagraphStyle('headcell',fontName='TimesRU-Bold',fontSize=9.3,leading=12,spaceAfter=0),
'list':ParagraphStyle('list',fontName='TimesRU',fontSize=12,leading=16.4,leftIndent=15,firstLineIndent=-13,spaceAfter=5),
'code':ParagraphStyle('code',fontName='MonoRU',fontSize=8.0,leading=11,spaceAfter=5),
}
def inline(t):
    t=html.escape(t).replace('\u202f',' ').replace('\u00a0',' ')
    t=re.sub(r'`([^`]+)`',lambda m:'<font name="MonoRU" size="8.5">'+m.group(1)+'</font>',t)
    t=re.sub(r'\[([^]]+)\]\(([^)]+)\)',r'<link href="\2" color="#303030"><u>\1</u></link>',t)
    t=re.sub(r'\*\*([^*]+)\*\*',r'<b>\1</b>',t)
    t=re.sub(r'(?<!\*)\*([^*]+)\*(?!\*)',r'<i>\1</i>',t)
    return t

def para(t,sty='body'):
    p=Paragraph(inline(t),S[sty])
    if t.endswith(':'): p.keepWithNext=True
    return p
S['body'].allowWidows=0
S['body'].allowOrphans=0
S['list'].allowWidows=0
S['list'].allowOrphans=0
W,H=A4
width=W-50*mm

def cover(c,doc):
    c.saveState()
    c.setFont('TimesRU-Bold',18)
    c.drawCentredString(W/2,H-43*mm,'УНИВЕРСИТЕТ ИТМО')
    c.setFont('TimesRU-Bold',17)
    c.drawCentredString(W/2,H-107*mm,'ОТЧЁТ')
    c.setFont('TimesRU',14)
    c.drawCentredString(W/2,H-119*mm,'по лабораторной работе № 2')
    title=Paragraph('Использование CouchDB и Etcd<br/>в веб-приложении',ParagraphStyle('title',fontName='TimesRU-Bold',fontSize=16,leading=22,alignment=TA_CENTER))
    _,h=title.wrap(width,100*mm);title.drawOn(c,25*mm,H-137*mm-h)
    c.setFont('TimesRU',13)
    c.drawCentredString(W/2,H-173*mm,'Вариант № 574821')
    x=110*mm
    c.drawString(x,H-208*mm,'Работу выполнили:')
    c.drawString(x,H-220*mm,'Самсонова Владислава')
    c.drawString(x,H-229*mm,'Фонарева Виктория')
    c.drawCentredString(W/2,29*mm,'Санкт-Петербург')
    c.drawCentredString(W/2,21*mm,'2026')
    c.restoreState()

def footer(c,doc):
    c.saveState();c.setFont('TimesRU',10);c.drawCentredString(W/2,13*mm,str(doc.page));c.restoreState()

src=(ROOT/'REPORT.md').read_text()
body=src[src.index('## 1.'):]
lines=body.splitlines();story=[Spacer(1,1),PageBreak()];i=0
while i<len(lines):
    line=lines[i]
    if not line.strip(): i+=1;continue
    if line.startswith('```'):
        i+=1;block=[]
        while i<len(lines) and not lines[i].startswith('```'):
            import textwrap
            block.extend(textwrap.wrap(lines[i],width=86,replace_whitespace=False,drop_whitespace=False,subsequent_indent='  ') or [''])
            i+=1
        pre=Preformatted('\n'.join(block),S['code'])
        box=Table([[pre]],colWidths=[width],style=TableStyle([('BACKGROUND',(0,0),(-1,-1),colors.HexColor('#f3f3f3')),('BOX',(0,0),(-1,-1),.4,colors.HexColor('#cccccc')),('LEFTPADDING',(0,0),(-1,-1),9),('RIGHTPADDING',(0,0),(-1,-1),9),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),7)]))
        story.extend([box,Spacer(1,9)]);i+=1;continue
    if line.startswith('|'):
        rows=[]
        while i<len(lines) and lines[i].startswith('|'):
            cells=[v.strip() for v in lines[i].strip().strip('|').split('|')]
            if not all(re.fullmatch(r'[: -]+',v) for v in cells):rows.append(cells)
            i+=1
        n=len(rows[0]);ratios={2:[.40,.60],3:[.28,.34,.38],4:[.19,.27,.27,.27]}[n]
        if 'Ключ'==rows[0][0]:ratios=[.40,.29,.31]
        if rows[0][0]=='Метод и путь':ratios=[.60,.40]
        if rows[0][0]=='Поле ответа':ratios=[.37,.63]
        data=[[para(v,'headcell' if ri==0 else 'cell') for v in row] for ri,row in enumerate(rows)]
        table=Table(data,colWidths=[width*r for r in ratios],repeatRows=1,hAlign='LEFT')
        table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e9e9e9')),('GRID',(0,0),(-1,-1),.4,colors.HexColor('#b5b5b5')),('LEFTPADDING',(0,0),(-1,-1),6),('RIGHTPADDING',(0,0),(-1,-1),6),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6)]))
        story.append(KeepTogether([table,Spacer(1,9)]));continue
    if line.startswith('## '):story.append(para(line[3:],'h2'));i+=1;continue
    if line.startswith('### '):story.append(para(line[4:],'h3'));i+=1;continue
    if line.startswith('- '):story.append(para('• '+line[2:],'list'));i+=1;continue
    if re.match(r'^\d+\. ',line):story.append(para(line,'list'));i+=1;continue
    parts=[line];i+=1
    while i<len(lines) and lines[i].strip() and not re.match(r'^(#|\||```|- |\d+\. )',lines[i]):parts.append(lines[i]);i+=1
    story.append(para(' '.join(parts)))

out=ROOT/'output/pdf/ITMO_NoSQL_Lab2_2026.pdf'
doc=SimpleDocTemplate(str(out),pagesize=A4,rightMargin=25*mm,leftMargin=25*mm,topMargin=22*mm,bottomMargin=22*mm,title='Университет ИТМО — Лабораторная работа № 2 — CouchDB и Etcd',author='Самсонова Владислава; Фонарева Виктория')
doc.build(story,onFirstPage=cover,onLaterPages=footer)
print(out)
