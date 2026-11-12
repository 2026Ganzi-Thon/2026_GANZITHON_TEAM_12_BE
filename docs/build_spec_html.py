"""Generate portable documentation from the reviewed Markdown. No network assets."""
from pathlib import Path
from html import escape
import re
import json
from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parent
md = MarkdownIt('commonmark', {'html': False}).enable('table')

def render(s):
    for name in ['백엔드_구조설계','책임_매핑','백엔드_협업가이드','계산_정책']:
        s=s.replace(']('+name+'.md)', ']('+name+'.html)')
    return md.render(s).replace('href="API.md"', 'href="API.html"').replace('href="계산_정책.md"', 'href="계산_정책.html"').replace('href="ERD.md"', 'href="ERD.html"').replace('href="검토결과.md"', 'href="검토결과.html"')

CSS = '''
:root{--ink:#18312c;--muted:#61716d;--line:#dce5df;--green:#206b53;--bg:#f4f6f1;--paper:#fff;--soft:#e9f0e9}*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:24px}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.65 'Segoe UI','Malgun Gothic',sans-serif}a{color:var(--green);text-underline-offset:3px}button,input,select{font:inherit}button{cursor:pointer}a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible{outline:3px solid #cf8d39;outline-offset:3px}header{padding:42px max(28px,calc((100vw - 1400px)/2));background:#183b30;color:white}header .eyebrow{font-size:12px;letter-spacing:.16em;color:#bed8c8}header h1{font-size:40px;letter-spacing:-.04em;line-height:1.2;margin:12px 0}header p{color:#c9ddd2;max-width:850px;margin:0}nav{display:flex;gap:10px;flex-wrap:wrap;margin-top:25px}nav a,nav button{border:1px solid #ffffff42;border-radius:8px;padding:7px 14px;text-decoration:none;background:transparent;color:white}nav .current{background:white;color:#183b30}main{max-width:1456px;margin:auto;padding:28px}h2{font-size:25px;letter-spacing:-.025em;margin:36px 0 16px}h3{font-size:18px;margin:22px 0 12px}h4{font-size:16px}p{margin:10px 0}.intro{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.tile{background:var(--paper);border:1px solid var(--line);border-radius:12px;padding:20px}.tile strong{display:block;font-size:22px}.tile span,.muted{color:var(--muted);font-size:13px}.notice{border-left:3px solid #bd8a36;background:#fcf5e7;padding:14px 18px;margin:20px 0;font-size:14px}.toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:24px 0 16px}.toolbar input{flex:1;min-width:200px}.toolbar input,.toolbar select,.toolbar button{padding:11px 14px;border:1px solid #bfcfc4;border-radius:8px;background:white;color:var(--ink)}.toolbar label{font-size:13px}#count{font-size:13px;color:var(--muted)}.layout{display:grid;grid-template-columns:210px minmax(0,1fr);gap:28px}.sidebar{position:sticky;top:20px;align-self:start;padding:8px}.sidebar a{display:block;padding:7px 10px;text-decoration:none;font-size:13px;border-radius:6px}.sidebar a:hover{background:var(--soft)}.endpoint{background:white;border:1px solid var(--line);border-radius:10px;margin:9px 0;overflow:hidden}.endpoint summary{list-style:none;display:grid;grid-template-columns:69px minmax(0,1fr) 26px;align-items:center;gap:12px;padding:15px 18px;cursor:pointer}.endpoint summary::-webkit-details-marker{display:none}.endpoint summary:after{content:'+';font-size:23px;color:#82938b}.endpoint[open] summary:after{content:'−'}.method{font-size:11px;font-weight:800;text-align:center;border-radius:5px;padding:4px;background:#e3efe8;color:#216547}.POST{background:#e8edf9;color:#405daa}.PUT,.PATCH{background:#fff0d9;color:#8b601f}.DELETE{background:#f9e6e4;color:#a0443c}.path{font:13px/1.5 Consolas,monospace;overflow-wrap:anywhere;color:#244c3c}.description{font-size:14px;margin-top:3px}.detail-body{padding:0 20px 20px;border-top:1px solid var(--line);overflow-wrap:anywhere}.detail-body>h3:first-child{margin-top:15px}code{font:12px/1.6 Consolas,monospace;overflow-wrap:anywhere;background:#edf2ee;padding:2px 4px;border-radius:4px}pre{background:#122d25;color:#e3f0e8;padding:18px;border-radius:8px;overflow:auto;font-size:12px;white-space:pre}pre code{background:transparent;color:inherit;padding:0}table{border-collapse:collapse;width:100%;font-size:13px;background:white;margin:16px 0}th,td{text-align:left;vertical-align:top;padding:11px 13px;border-bottom:1px solid var(--line);overflow-wrap:anywhere}th{background:#e8eee7;color:#315043}li{margin:5px 0}details.block{background:white;border:1px solid var(--line);border-radius:10px;margin:13px 0}details.block>summary{padding:17px 20px;cursor:pointer;font-weight:600}.content{padding:0 20px 20px;min-width:0;overflow-x:auto}.table-card{margin:12px 0;background:white;border:1px solid var(--line);border-radius:10px;overflow:hidden}.table-card>summary{padding:18px;cursor:pointer;overflow-wrap:anywhere}.table-card .content h4{display:none}.empty{padding:30px;text-align:center;color:var(--muted);border:1px dashed #bfcfc4;border-radius:10px}.diagram-frame{border:1px solid var(--line);border-radius:12px;background:#fff;overflow:auto}.diagram-frame svg{display:block;min-width:1150px;width:100%;height:auto}.diagram-frame .edge{stroke:#abc0b3;stroke-width:1.2;fill:none}.diagram-frame .node:hover rect{fill:#e7f2e9;stroke:#206b53}.diagram-frame text{font-family:Consolas,'Malgun Gothic',monospace}.legend{font-size:12px;color:var(--muted);margin:12px 0}footer{max-width:1400px;margin:24px auto;padding:22px 28px;border-top:1px solid var(--line);font-size:12px;color:var(--muted)}[hidden]{display:none!important}@media(max-width:800px){header{padding:30px 20px}header h1{font-size:32px}main{padding:18px}.intro{grid-template-columns:1fr}.layout{grid-template-columns:1fr}.sidebar{position:static;display:flex;flex-wrap:wrap;gap:3px}.sidebar a{padding:5px 8px}.endpoint summary{padding:12px;gap:8px;grid-template-columns:60px minmax(0,1fr) 20px}table{min-width:580px}.tile{padding:15px}.toolbar input{min-width:100%;width:100%}.content,.detail-body{overflow-x:auto}}@media print{body{background:white;font-size:11px}header{background:white;color:#183b30;padding:10px}header p,header .eyebrow{color:#52685e}nav,.toolbar,.sidebar,.print-button{display:none!important}main{padding:0}.layout{display:block}.intro{display:none}details{break-inside:avoid}details:not([open])>*:not(summary){display:block!important}.diagram-frame{overflow:visible}.diagram-frame svg{min-width:0}pre{white-space:pre-wrap}.endpoint summary{padding:8px}h2{break-after:avoid}.detail-body,.content{overflow:visible}table{min-width:0}}
'''
CSS += '.layout>*{min-width:0} .toolbar>*{max-width:100%}'
CSS += 'article.tile strong{display:inline;font-size:inherit} article.tile p,article.tile li{max-width:100ch} article.tile pre{font-size:13px}'

JS = '''
const q=document.querySelector('#search'), filter=document.querySelector('#filter');
function apply(){const query=(q?.value||'').toLowerCase().trim(), group=filter?.value||'';let n=0;document.querySelectorAll('[data-search]').forEach(el=>{let yes=(!group||el.dataset.group===group)&&(!query||el.dataset.search.includes(query));el.hidden=!yes;if(yes)n++});document.querySelectorAll('.api-group').forEach(g=>g.hidden=![...g.querySelectorAll('[data-search]')].some(el=>!el.hidden));let c=document.querySelector('#count');if(c)c.textContent=n+'개 표시';let e=document.querySelector('#empty');if(e)e.hidden=n>0;}
q?.addEventListener('input',apply);filter?.addEventListener('change',apply);apply();
document.querySelector('#expand')?.addEventListener('click',()=>{document.querySelectorAll('details[data-search]:not([hidden])').forEach(d=>d.open=true)});
document.querySelector('#collapse')?.addEventListener('click',()=>{document.querySelectorAll('details[data-search]').forEach(d=>d.open=false)});
function expandHash(){let id=decodeURIComponent(location.hash.slice(1));let el=document.getElementById(id);if(el?.matches('details')){if(q)q.value='';if(filter)filter.value='';apply();el.open=true;el.scrollIntoView()}}
window.addEventListener('hashchange',expandHash);expandHash();
let saved;window.addEventListener('beforeprint',()=>{saved=[...document.querySelectorAll('details')].map(d=>d.open);document.querySelectorAll('details').forEach(d=>d.open=true)});window.addEventListener('afterprint',()=>{document.querySelectorAll('details').forEach((d,i)=>d.open=saved?.[i]??false)});
'''

def page(kind, title, sub, body):
    nav=''.join(f'<a class="{"current" if kind==k else ""}" href="{file}">{label}</a>' for k,file,label in [('api','API.html','API 명세'),('erd','ERD.html','ERD · 데이터 구조'),('review','검토결과.html','검토 결과'),('policy','계산_정책.html','계산 정책'),('architecture','백엔드_구조설계.html','백엔드 구조'),('mapping','책임_매핑.html','책임 매핑'),('guide','백엔드_협업가이드.html','협업 가이드')])
    return f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"><title>Come-down · {title}</title><style>{CSS} article.tile{{overflow-x:auto;min-width:0}}</style></head><body><header><div class="eyebrow">COME-DOWN / PRODUCT & ENGINEERING</div><h1>{title}</h1><p>{sub}</p><nav aria-label="문서 이동">{nav}<button class="print-button" type="button" onclick="window.print()">인쇄 / PDF</button></nav></header><main>{body}</main><footer>명세 v0.2 / 구조 v1.0 · 2026.11.12 · 서비스 요구사항·API·ERD·IA 기준 · 구현 완료를 의미하지 않는 설계 명세 · 외부 라이브러리/네트워크 요청 없는 HTML</footer><script>{JS}</script></body></html>'''

api=(ROOT/'API.md').read_text(encoding='utf-8')
sections=re.split(r'^## ',api,flags=re.M)[1:]
groups=[]; appendix=[]; common=''; object_count=0
for sec in sections:
    title, _, content=sec.partition('\n')
    if title.startswith('0.'):
        common=content
    if re.match(r'[1-9]\. ',title):
        items=[]
        for chunk in re.split(r'^### ',content,flags=re.M)[1:]:
            heading,_,detail=chunk.partition('\n')
            m=re.search(r'`(GET|POST|PUT|PATCH|DELETE) ([^`]+)`',heading)
            if not m: continue
            method,path=m.groups();label=re.sub(r'`[^`]+`','',heading).strip(' —') or next((s.strip() for s in detail.splitlines() if s.strip() and not s.startswith(('```','{','|','-'))),'상세 보기')
            label=re.sub(r'^→\s*\d+\s*[—·]?\s*','',label).strip() or '상세 보기'
            items.append((method,path,label,detail))
        # table endpoints (Mypage) are also first-class cards
        for m in re.finditer(r'^\| (GET|POST|PUT|PATCH|DELETE) \| `([^`]+)` \| (.+) \|$',content,re.M):
            method,path,label=m.groups()
            if not any(x[0]==method and x[1].split('?')[0]==path.split('?')[0] for x in items):
                items.append((method,path,re.sub(r'`[^`]+`','',label).strip(),label))
        if items: groups.append((title,items,content))
    elif title.startswith(('10.','11.','12.','13.','14.')):
        appendix.append((title,content))

# One extra route documented in prose, under the onboarding group.
groups[1][1].append(('POST','/api/onboarding/cbi/skip','건강정보 비동의 시 CBI 건너뛰기','PROFILE 완료 + 민감정보 동의 없음인 경우만 허용.\n\n응답 200: `{ "onboardingStep": "LIFESTYLE" }`'))
LABELS={
('GET','/api/auth/csrf'):'CSRF 쿠키 발급',('GET','/api/me'):'내 정보 · 온보딩 진행 상태',
('POST','/api/auth/logout'):'로그아웃',('DELETE','/api/me'):'회원 탈퇴',
('PUT','/api/onboarding/profile'):'닉네임 · 약관 · 동의 저장',('GET','/api/cbi/questions'):'CBI 문항 조회',
('POST','/api/cbi/assessments'):'CBI 응답 제출 · 점수 계산',('GET','/api/cbi/assessments'):'내 CBI 응답 이력',
('PUT','/api/me/settings'):'측정 시간 · 수면 · 선호 활동 저장',('GET','/api/schedule/report'):'지난달 분석 · 앞으로 4주 일정 리포트',
('POST','/api/onboarding/complete'):'온보딩 완료 · 1일차 시작',('GET','/api/home'):'오늘 회복 · 이번 주 부하 · 추천 요약',
('POST','/api/calendar/sync'):'구글 일정 동기화 · 분류 · 부하 갱신',('GET','/api/calendar/events'):'기간별 일정과 분류 조회',
('PATCH','/api/calendar/events/{id}/category'):'일정 분류 · 부담 · 마감 여부 직접 수정',('GET','/api/schedule/weeks'):'주간 부하 · 위험 주차 조회',
('DELETE','/api/calendar/connection'):'캘린더 연결 해제 · 관련 데이터 정리',('GET','/api/suggestions'):'대비 제안 · 회복 추천 목록',
('POST','/api/suggestions/generate'):'다른 추천 만들기',('GET','/api/suggestions/{id}/slots'):'추천을 넣을 다른 빈 시간 조회',
('POST','/api/suggestions/{id}/approve'):'추천 승인 · 구글 캘린더 등록',('POST','/api/suggestions/{id}/dismiss'):'추천 건너뛰기',
('POST','/api/suggestions/{id}/undo'):'등록한 추천 일정 되돌리기',('POST','/api/suggestions/{id}/done'):'활동 수행 완료 체크',
('GET','/api/morning-checks/today'):'오늘 체크 진행 단계 · 결과',('POST','/api/hrv/measurements'):'HRV 측정값 저장 · 품질 확인',
('POST','/api/morning-checks/today/breathing'):'호흡 훈련 1분 완료',('PUT','/api/morning-checks/today/condition'):'컨디션 저장 · 오늘 결과와 추천',
('GET','/api/reports/recovery'):'회복 그래프 · 평소 대비 변화 · 해석',('GET','/api/reports/four-week'):'28일차 CBI · 회복 · 부하 비교',
('PATCH','/api/me'):'닉네임 수정',('GET','/api/me/settings'):'생활 정보 · 측정 사용 상태 조회',
('GET','/api/me/consents'):'동의 현황 조회',('POST','/api/me/consents'):'건강정보 다시 동의',
('DELETE','/api/me/consents/SENSITIVE_HRV'):'건강정보 동의 철회 · 선택 시 기록 삭제',('DELETE','/api/hrv/data'):'건강 기록 전체 삭제',
('GET','/api/safety'):'도움 받을 수 있는 곳 · 안전 안내',('POST','/api/safety/alerts/{id}/acknowledge'):'안전 안내 닫기',
('POST','/api/onboarding/cbi/skip'):'건강정보 비동의 시 CBI 건너뛰기',
}
seen=set()
for title,items,content in groups:
    unique=[]
    for method,path,label,detail in items:
        key=(method,path.split('?')[0])
        if key not in seen:
            unique.append((method,path,LABELS.get(key,label),detail))
            seen.add(key)
    items[:]=unique
count=len(seen)
sidebar=''.join(f'<a href="#g{i}">{escape(t)}</a>' for i,(t,_,_) in enumerate(groups))
options=''.join(f'<option value="g{i}">{escape(t)}</option>' for i,(t,_,_) in enumerate(groups))
cards=''
for i,(title,items,content) in enumerate(groups):
    cards+=f'<section class="api-group" id="g{i}"><h2>{escape(title)}</h2>'
    for method,path,label,detail in items:
        search=escape((method+' '+path+' '+label+' '+detail).lower(),quote=True)
        cards+=f'<details class="endpoint" data-search="{search}" data-group="g{i}"><summary><span class="method {method}">{method}</span><div><div class="path">{escape(path)}</div><div class="description">{escape(label)}</div></div></summary><div class="detail-body">{render(detail)}</div></details>'
    if title.startswith('8.'):
        cards+='<p class="muted">공용 API: 닉네임 수정 외 회원 조회·로그아웃·탈퇴는 1절, 생활 정보 저장은 2절, 캘린더 해제는 4절을 참고하세요.</p>'
        notes=content.split('용어 사전')[0]
        notes=re.sub(r'^\|.*\n','',notes,flags=re.M)
        cards+=f'<details class="block"><summary>설정·동의·건강 기록 삭제 규칙</summary><div class="content">{render(notes)}</div></details>'
    cards+='</section>'
extra=''.join(f'<details class="block"><summary>{escape(t)}</summary><div class="content">{render(c)}</div></details>' for t,c in appendix)
body=f'''<div class="intro"><div class="tile"><strong>{count}개 API</strong><span>사용자 기능 기준 · 개발용 API는 하단 별도</span></div><div class="tile"><strong>화면 순서대로</strong><span>가입 → 일정 → 아침 체크 → 회복 리포트</span></div><div class="tile"><strong>필요할 때 펼치기</strong><span>기본은 역할만, 클릭하면 요청·응답 상세</span></div></div><div class="notice">설계 검토본입니다. <a href="검토결과.html">수정 근거와 미확정 정책</a>을 먼저 확인하세요. 숫자·날짜 예시는 실행 응답이 아니며 일부 배열은 구조를 축약했습니다.</div><div class="toolbar"><label for="search">검색</label><input id="search" type="search" placeholder="주소, 화면, 기능으로 찾기" autocomplete="off"><label for="filter">분류</label><select id="filter"><option value="">모든 기능</option>{options}</select><button id="expand">모두 펼치기</button><button id="collapse">모두 접기</button><span id="count" aria-live="polite"></span></div><div class="layout"><aside class="sidebar" aria-label="기능 목차">{sidebar}<a href="#common">공통 규칙</a><a href="#reference">화면 매핑·열린 항목</a></aside><div>{cards}<div id="empty" class="empty" hidden>검색 결과가 없습니다. 검색어나 분류를 바꿔 주세요.</div><section id="common"><h2>공통 규칙 · 객체</h2><details class="block"><summary>인증 · 오류 · 재시도 · 공통 데이터</summary><div class="content">{render(common)}</div></details></section><section id="reference"><h2>화면 매핑 · 개발 참고</h2>{extra}</section></div></div>'''
(ROOT/'API.html').write_text(page('api','API 명세, 간결하게','주소와 역할을 먼저 확인하고, 필요한 API의 요청·응답만 펼쳐 보세요.',body),encoding='utf-8')

erd=(ROOT/'ERD.md').read_text(encoding='utf-8')
tables=[]
for m in re.finditer(r'^#### `([^`]+)` — ([^\n]+)\n(.*?)(?=^#### |^### |^## |\Z)',erd,re.M|re.S):
    tables.append((m[1],m[2],m[3]))
m=re.search(r'^### `api_idempotency_keys` — ([^\n]+)\n(.*?)(?=^- 복합 FK)',erd,re.M|re.S)
tables.append(('api_idempotency_keys',m[1],m[2]))
diagram=re.search(r'```mermaid\n(.*?)```',erd,re.S)[1]
relations=re.findall(r'^    (\w+) ([|o{}\-]+) (\w+) : "([^"]+)"',diagram,re.M)
fields={}
for m in re.finditer(r'^    (\w+) \{\n(.*?)^    \}',diagram,re.M|re.S):
    fields[m[1]]=[s.strip() for s in m[2].strip().splitlines()]

columns=[['user_settings','user_preferred_activities','user_consents'],['oauth_tokens','subscriptions','api_idempotency_keys'],['cbi_assessments','cbi_answers','safety_alerts'],['calendar_events','event_category_overrides','weekly_loads'],['morning_checks','hrv_measurements','hrv_daily_states'],['suggestions','ai_outputs']]
W=1560;cardw=234;h=128;pos={'users':(663,38)}
for col,names in enumerate(columns):
    for row,name in enumerate(names):pos[name]=(20+col*256,280+row*210)
svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} 940" role="img" aria-labelledby="erdtitle erddesc"><title id="erdtitle">Come-down ERD 관계도</title><desc id="erddesc">users를 중심으로 {len(tables)}개 테이블이 연결됩니다. 테이블을 클릭하면 컬럼 정의로 이동합니다. 전체 관계는 아래 표에서 확인할 수 있습니다.</desc>'
svg+='<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto"><path d="M0,0 L10,5 L0,10" fill="#8aa696"/></marker></defs>'
# user FKs routed through horizontal lanes; four child relations retain direct paths.
for n,(src,card,dst,label) in enumerate(relations):
    x,y=pos[src];xx,yy=pos[dst]
    if src=='users':
        lane=197+n*2
        path=f'M{x+cardw/2},{y+h} V{lane} H{xx-8} V{yy+h/2} H{xx}'
    elif abs(x-xx)<1:
        path=f'M{x+cardw-8},{y+h} C{x+cardw+20},{y+h+35} {xx+cardw+20},{yy-35} {xx+cardw-8},{yy}'
    else:
        path=f'M{x+cardw-12},{y} V{y-12} H{xx+cardw-12} V{yy}'
    svg+=f'<path class="edge" d="{path}" marker-end="url(#arrow)"><title>{escape(src+" → "+dst+" / "+label+" / "+card)}</title></path>'
labels=['설정 · 동의','연결 · 체험 · 재시도','CBI · 안전','일정 · 부하','측정 · 회복','추천 · AI']
for col,label in enumerate(labels):svg+=f'<text x="{20+col*256}" y="255" font-size="14" fill="#206b53" font-weight="bold">{label}</text>'
for name,(x,y) in pos.items():
    selected=[f for f in fields[name] if 'PK' in f or 'FK' in f]
    selected+=( [f for f in fields[name] if f not in selected])
    svg+=f'<a class="node" href="#table-{name}" aria-label="{name} 컬럼 보기"><rect x="{x}" y="{y}" width="{cardw}" height="{h}" rx="9" fill="{ "#e5efe7" if name=="users" else "#fff"}" stroke="#9eb9a7"/><text x="{x+12}" y="{y+25}" fill="#193d2e" font-size="12" font-weight="bold">{name}</text><path d="M{x},{y+37} H{x+cardw}" stroke="#dce5df"/>'
    for i,f in enumerate(selected[:4]):svg+=f'<text x="{x+12}" y="{y+56+i*17}" fill="#5f7167" font-size="10">{escape(f)}</text>'
    svg+='</a>'
svg+='<text x="20" y="914" fill="#61716d" font-size="13">요약 관계도 · 주요 키/필드만 표시 · 전체 컬럼과 제약은 아래 정의 참조 · 관계선에 마우스를 올리면 설명 표시</text></svg>'
relation_table='<table><thead><tr><th>부모</th><th>관계</th><th>자식</th><th>역할</th></tr></thead><tbody>'+''.join(f'<tr><td><a href="#table-{a}">{a}</a></td><td>{escape(c)}</td><td><a href="#table-{b}">{b}</a></td><td>{escape(d)}</td></tr>' for a,c,b,d in relations)+'</tbody></table>'
table_cards=''
for name,label,content in tables:
    table_cards+=f'<details class="table-card" id="table-{name}" data-search="{escape((name+" "+label+" "+content).lower(),quote=True)}"><summary><strong>{escape(name)}</strong> <span class="muted">— {escape(label)}</span></summary><div class="content">{render(content)}</div></details>'
rules=erd.split('## 6. 무결성·삭제·재시도 보완 (v0.2)')[1]
rules=rules[rules.index('- 복합 FK:'):]
principles=erd.split('## 0. 설계 원칙')[1].split('## 1. 관계도')[0]
enums=erd.split('## 3. 코드 값(enum)')[1].split('## 4.')[0]
pending=erd.split('## 5. 정해야 할 것')[1].split('## 6.')[0]
body=f'''<div class="intro"><div class="tile"><strong>{len(tables)}개 테이블</strong><span>회원 · 일정 · 측정 · 추천의 6개 영역</span></div><div class="tile"><strong>{len(relations)}개 관계</strong><span>사용자 소유권 + 하위 리소스 참조</span></div><div class="tile"><strong>키와 삭제 규칙 보완</strong><span>중복 요청 방지 · 복합 FK · 건강 기록 정리</span></div></div><div class="notice">구현 전 설계입니다. ERD는 도메인 테이블만 표시하며 Spring Session 표준 테이블은 제외합니다. <a href="검토결과.html">변경 근거·정책 결정 항목</a></div><h2>01 / 전체 관계도</h2><p class="muted">테이블을 클릭하면 전체 컬럼 정의가 열립니다. 작은 화면에서는 관계도를 좌우로 스크롤하세요.</p><div class="diagram-frame">{svg}</div><p class="legend">PK 기본키 · FK 외래키 · UK 고유키 · || 정확히 1 · o| 0 또는 1 · o&#123; 0개 이상 · |&#123; 1개 이상 · 화살표는 부모 → 자식 참조</p><details class="block"><summary>전체 관계 {len(relations)}개를 표로 보기</summary><div class="content">{relation_table}</div></details><h2>02 / 테이블 정의</h2><div class="toolbar"><label for="search">테이블·컬럼 검색</label><input id="search" type="search" placeholder="예: suggestions, user_id, 기준선"><button id="expand">모두 펼치기</button><button id="collapse">모두 접기</button><span id="count" aria-live="polite"></span></div>{table_cards}<div id="empty" class="empty" hidden>일치하는 테이블이 없습니다.</div><h2>03 / 무결성 · 삭제 · 코드 값</h2><details class="block" open><summary>참조·트랜잭션·재시도·삭제 정책</summary><div class="content">{render(rules)}</div></details><details class="block"><summary>설계 원칙</summary><div class="content">{render(principles)}</div></details><details class="block"><summary>코드 값(enum)</summary><div class="content">{render(enums)}</div></details><details class="block"><summary>미확정 항목</summary><div class="content">{render(pending)}</div></details>'''
(ROOT/'ERD.html').write_text(page('erd','ERD · 데이터 구조','관계도를 먼저 보고, 테이블별 컬럼과 무결성 규칙을 확인하세요.',body),encoding='utf-8')
review=(ROOT/'검토결과.md').read_text(encoding='utf-8')
(ROOT/'검토결과.html').write_text(page('review','설계 검토 결과','기획과 IA에 대조해 바로잡은 내용과, 팀에서 결정해야 할 항목을 구분했습니다.','<article class="tile">'+render(review)+'</article>'),encoding='utf-8')
print(f'Generated API.html ({count} routes), ERD.html ({len(tables)} tables, {len(relations)} relationships), 검토결과.html')

# Product-first architecture: validate against the specification, never Java source.
def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'Duplicate architecture mapping: {key}')
        result[key] = value
    return result

mapping = json.loads((ROOT/'architecture-map.json').read_text(encoding='utf-8'), object_pairs_hook=unique_object)
specified_routes = {method+' '+path for method,path in seen}
if set(mapping['httpOwners']) != specified_routes:
    raise ValueError({'unmappedApi': sorted(specified_routes-set(mapping['httpOwners'])),
                      'unknownApi': sorted(set(mapping['httpOwners'])-specified_routes)})
specified_tables = {name for name,_,_ in tables}
if len(specified_tables) != len(tables) or set(mapping['tableOwners']) != specified_tables:
    raise ValueError({'unmappedTables': sorted(specified_tables-set(mapping['tableOwners'])),
                      'unknownTables': sorted(set(mapping['tableOwners'])-specified_tables)})
modules = {'account','cbi','calendar','schedule','checkin','recovery','recommendation',
           'safety','subscription','ai','experience','integration','shared'}
for owner in list(mapping['httpOwners'].values())+list(mapping['tableOwners'].values()):
    if owner.split('.')[0] not in modules:
        raise ValueError(f'Unknown target module: {owner}')
mapping_md = '''# Come-down(캄다운) API·테이블 책임 매핑

설계 v1.0 · 기존 구현을 기준으로 하지 않은 목표 책임이다. 원본은 architecture-map.json이며 API.md·ERD.md의 모든 항목과 대조해 생성한다. API/테이블을 추가하면 JSON을 갱신하고 문서를 다시 생성한다.

## HTTP API 책임

HTTP 책임은 Controller/보안 필터가 위치할 곳이다. 저장·계산 책임은 각 기능의 contract/application에 위임한다. experience 아래에 있다는 이유로 테이블 소유권을 가져가지 않는다. PUT /api/me/settings는 온보딩 단계에도 쓰이므로 experience.onboarding에 두되 저장된 계정의 설정 변경은 account에 위임하며 완료된 단계를 되돌리지 않는다. INITIAL/DAY28 CBI 제출은 experience.assessments가 cbi·safety·초기 온보딩 진행을 조합한다.

| 메서드 | API (쿼리 제외) | HTTP 책임 |
|---|---|---|
'''
for route, owner in mapping['httpOwners'].items():
    method,path=route.split(' ',1)
    mapping_md += f'| {method} | `{path}` | `{owner}` |\n'
mapping_md += '\n## ERD 테이블 쓰기 소유권\n\n소유 기능만 해당 테이블을 갱신한다. 기능 간 조회/명령은 contract를 사용한다. experience·integration에는 소유 테이블이 없다.\n\n| 테이블 | 유일한 쓰기 소유자 |\n|---|---|\n'
for table,owner in mapping['tableOwners'].items():
    mapping_md += f'| `{table}` | `{owner}` |\n'
mapping_md += '\n## OAuth·개발용 경로\n\n사용자 API 39개와 구분한다. OAuth 콜백은 Spring Security 흐름으로 처리하며 임의의 중복 Controller를 추가하지 않는다. local 전용 시드/시계/삭제 기능은 운영 빈·라우팅에서 제외한다.\n\n| 경로 | 책임 |\n|---|---|\n'
for route,owner in mapping['additionalRoutes'].items():
    mapping_md += f'| `{route}` | {owner} |\n'
mapping_md += f'\n## 문서 대조 결과\n\nAPI {len(specified_routes)}개와 테이블 {len(specified_tables)}개가 누락/초과/중복 키 없이 매핑됐다. 이 검증은 문서의 책임 완전성 검사이며 실제 소스 구현·의존 방향·테스트 통과를 뜻하지 않는다.\n'
(ROOT/'책임_매핑.md').write_text(mapping_md,encoding='utf-8')

extra_pages = [
    ('architecture','백엔드_구조설계','백엔드 구조 설계','서비스 전체·API·ERD에서 도출한 Come-down의 신규 목표 구조입니다.'),
    ('mapping','책임_매핑','API · 테이블 책임 매핑','39개 HTTP API와 18개 테이블의 책임을 한 곳에서 확인하세요.'),
    ('guide','백엔드_협업가이드','백엔드 2인 협업 가이드','구조의 경계와 환경·작업·리뷰·DB 규칙을 함께 맞춥니다.'),
    ('policy','계산_정책','계산 정책 v1','일정 부하·CBI·HRV 측정·회복·안전·범위 위치의 확정 산식입니다.')
]
for kind,stem,title,subtitle in extra_pages:
    content=(ROOT/(stem+'.md')).read_text(encoding='utf-8')
    body='<article class="tile">'+render(content)+'</article>'
    (ROOT/(stem+'.html')).write_text(page(kind,title,subtitle,body),encoding='utf-8')
print(f'Architecture validated: {len(specified_routes)} API owners / {len(specified_tables)} table owners; generated architecture, mapping, and collaboration HTML.')
