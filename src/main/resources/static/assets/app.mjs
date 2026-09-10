import { escapeHtml as esc, officialLink, excerpt, messageDraft } from './ui-utils.mjs';

const app = document.querySelector('#app');
let tokens = null, refreshing = null, me = null, current = null, tab = 'overview', signup = false;
let files = [], evidence = [], analyses = [], selectedAnalysis = null, selectedFile = null, ocr = null;
let casePage = 1, filePage = 1, analysisPage = 1, pendingAnalysis = null, uploadKey = null, analysisInput = null;
let previewUrl = null, previewFileId = null, busy = false, toastTimer, savedMessage = '', chatPage = 1;
const date = value => value ? new Intl.DateTimeFormat('ko-KR', {dateStyle:'medium'}).format(new Date(value)) : '미확인';
function visual(value) { try { const parsed = JSON.parse(value || '{}'); return { observations:Array.isArray(parsed.observations) ? parsed.observations : [], unknowns:Array.isArray(parsed.unknowns) ? parsed.unknowns : [] }; } catch { return {observations:[],unknowns:[]}; } }
const labels = { DRAFT:'작성 중', COLLECTING:'자료 수집', REVIEW_READY:'검토 준비', ANALYZING:'분석 중', READY:'검토 완료', CLOSED:'종료',
  UPLOADED:'업로드 완료', PROCESSING:'인식 중', REVIEW_REQUIRED:'확인 필요', CONFIRMED:'확정 완료', PURGED:'원본 삭제 완료', FAILED:'실패' };
function toast(message, error = false) {
  const node = document.querySelector('#notice'); clearTimeout(toastTimer);
  node.textContent = message; node.className = `toast${error ? ' error' : ''}`; node.hidden = false;
  toastTimer = setTimeout(() => { node.hidden = true; }, error ? 14000 : 5000);
}
function clearPreview() { if (previewUrl) URL.revokeObjectURL(previewUrl); previewUrl = null; previewFileId = null; }
function clearSession() { tokens = null; me = null; current = null; pendingAnalysis = null; analysisInput = null; selectedAnalysis = null; selectedFile = null; files = []; evidence = []; analyses = []; ocr = null; savedMessage = ''; clearPreview(); renderAuth(); }
async function api(path, {method = 'GET', body, key, publicRequest = false} = {}, retry = true) {
  const headers = {};
  if (tokens && !publicRequest) headers.Authorization = `Bearer ${tokens.accessToken}`;
  if (key) headers['Idempotency-Key'] = key;
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  let response;
  try { response = await fetch(`/api/v1${path}`, {method, headers, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined, cache:'no-store'}); }
  catch { throw new Error('서버에 연결하지 못했어요. 연결 상태를 확인해 주세요. 실행 중이었다면 기록에서 결과를 먼저 확인해 주세요.'); }
  if (response.status === 401 && tokens && !publicRequest && retry) {
    if (!refreshing) refreshing = api('/auth/refresh', {method:'POST', body:{refreshToken:tokens.refreshToken}, publicRequest:true}, false)
      .then(value => { tokens = value; }).catch(error => { clearSession(); throw error; }).finally(() => { refreshing = null; });
    await refreshing; return api(path, {method, body, key, publicRequest}, false);
  }
  if (response.status === 204) return null;
  let value;
  try { value = await response.json(); } catch { throw new Error('서버 응답을 확인하지 못했어요. 기록을 새로고침해 주세요.'); }
  if (!response.ok) {
    const error = new Error(`${value.message || '요청을 완료하지 못했어요.'}${value.traceId ? ` (확인 번호: ${value.traceId})` : ''}`);
    error.code = value.code; throw error;
  }
  return value.data;
}
const casePath = suffix => `/cases/${current.id}${suffix}`;
const filePath = suffix => casePath(`/files/${selectedFile.id}${suffix}`);
const brand = '<div class="brand"><span>B</span>Better Call AI</div>';
const footer = '<footer>주택 임대차 자료를 정리하는 검토용 서비스예요. 법률적 판단이 필요할 때는 전문가와 확인해 주세요.<br>업로드 원본은 임시 보관되며 OCR 확정 후 삭제돼요. 본인이 보유한 원본은 따로 보관해 주세요.</footer>';
function field(label, name, value = '', extra = '') { return `<label class="field">${label}<input name="${name}" value="${esc(value)}" ${extra}></label>`; }
function area(label, name, value = '', extra = '') { return `<label class="field">${label}<textarea name="${name}" ${extra}>${esc(value)}</textarea></label>`; }
function pager(kind, page, hasNext) { return `<div class="pager"><button class="ghost" data-act="page-${kind}" data-page="${page - 1}" ${page === 1 ? 'disabled' : ''}>이전</button><span>${page} 페이지</span><button class="ghost" data-act="page-${kind}" data-page="${page + 1}" ${!hasNext ? 'disabled' : ''}>다음</button></div>`; }
function shell(content, breadcrumb = '나의 사건') {
  app.innerHTML = `<div class="shell"><aside class="sidebar">${brand}<div><p class="side-label">MY WORKSPACE</p><button class="nav-item" data-act="home">▤ &nbsp; 나의 사건</button></div><div class="side-bottom">어려운 상황일수록<br>하나씩, 차근차근.<br><br>상황 정리부터 다음 행동까지<br>함께 준비할게요.</div></aside><div><header class="topbar"><small>${esc(breadcrumb)}</small><div class="mobile-brand">${brand}</div><div class="user"><div class="avatar">${esc((me?.displayName || '나').slice(0, 1))}</div><span>${esc(me?.displayName || '사용자')}님</span><button class="ghost" data-act="logout">로그아웃</button></div></header><main id="main" class="content">${content}${footer}</main></div></div>`;
}
function renderAuth() {
  app.innerHTML = `<main id="main" class="auth"><section class="intro">${brand}<div><div class="eyebrow">BETTER STEPS, TOGETHER</div><h1>막막한 분쟁,<br>다음 행동이<br>보이도록.</h1><p>흩어진 상황과 자료를 한곳에 모으세요.<br>근거를 살펴보고, 차근차근 준비할 수 있어요.</p><div class="intro-art" aria-hidden="true"></div></div><small>나의 기록에서 시작하는, 더 나은 해결</small></section><section class="auth-form"><div class="auth-inner"><div class="eyebrow">나의 사건 정리</div><h2>${signup ? '함께 시작해 볼까요?' : '다시 만나 반가워요'}</h2><p>${signup ? '내 사건을 안전하게 보관할 계정을 만들어요.' : '로그인하고 이어서 사건을 정리해 보세요.'}</p><form id="auth-form" class="stack">${signup ? field('이름','displayName','','required maxlength="100" autocomplete="name"') : ''}${field('이메일','email','','type="email" required maxlength="254" autocomplete="email"')}${field('비밀번호','password','','type="password" required minlength="8" maxlength="72" autocomplete="'+(signup?'new-password':'current-password')+'"')}${signup ? '<label class="check"><input name="terms" type="checkbox" required>자료 정리와 검토용 AI 안내 서비스 이용에 동의해요. 외부 전송이나 법률 판단을 대신하지 않아요.</label><label class="check"><input name="privacy" type="checkbox" required>계정과 내 사건 자료를 서비스 제공을 위해 저장·처리하는 데 동의해요. 사건 삭제는 보존 정책에 따라 처리돼요.</label>' : ''}<button type="submit">${signup ? '동의하고 시작하기' : '로그인'}</button></form><div class="auth-switch"><small>${signup ? '이미 계정이 있나요?' : '처음 오셨나요?'} </small><button class="link-button" data-act="auth-switch">${signup ? '로그인' : '회원가입'}</button></div><div class="auth-footer">개인 주택 임대차 사건을 지원해요.<br>로그인 정보와 상담 내용은 브라우저 저장소에 남기지 않아요.<br>페이지를 새로 열면 다시 로그인해 주세요.</div></div></section></main>`;
}
async function home() {
  current = null; selectedAnalysis = null; clearPreview();
  const page = await api(`/cases?page=${casePage}&pageSize=8`);
  shell(`<div class="welcome row wrap"><div><div class="eyebrow">MY CASES</div><h1>${esc(me.displayName)}님의 사건 보관함</h1><p>복잡한 이야기도 하나씩 정리하면 괜찮아요.<br>상황과 자료를 확인하고, 다음 행동을 준비해 보세요.</p></div><button data-act="new-case">＋ 새 사건 만들기</button></div><div class="banner"><div class="symbol" aria-hidden="true">✓</div><div><h3>좋은 준비는 정확한 기록에서 시작해요</h3><p>사진·계약서·대화 내용을 모으고, 인식된 내용을 직접 확인해 주세요.</p></div></div><div class="section-title"><h2>나의 사건</h2><small>최근 수정한 순서</small></div>${page.items.length ? `<div class="cards">${page.items.map(c => `<article class="case-card"><div class="row"><span class="pill">${esc(labels[c.status] || c.status)}</span><small>주택 임대차</small></div><h3>${esc(c.title)}</h3><p>${esc(c.userPartyRole || '역할 미입력')} · 입력 ${c.version}차</p><div class="row"><small>${date(c.updatedAt)} 수정</small><button class="secondary" data-act="open-case" data-id="${esc(c.id)}">사건 열기 →</button></div></article>`).join('')}</div>` : '<div class="empty"><h3>아직 등록한 사건이 없어요</h3><p>지금 겪고 있는 상황을 편하게 적어 주세요.</p><button data-act="new-case">첫 사건 만들기</button></div>'}${pager('cases', casePage, page.hasNext)}`);
}
function newCase() {
  shell(`<button class="ghost" data-act="home">← 나의 사건</button><div class="welcome"><div class="eyebrow">NEW CASE</div><h1>어떤 일이 있었나요?</h1><p>법률 용어를 몰라도 괜찮아요. 알고 있는 사실부터 적어 주세요.</p></div><form id="case-form" class="panel stack">${field('사건 이름','title','','required maxlength="200" placeholder="예: 월셋집 천장 누수 수리"')}<label class="field">나의 역할<select name="userPartyRole"><option value="임차인">임차인</option><option value="임대인">임대인</option></select></label>${area('상황 설명','originalStatement','','required maxlength="50000" placeholder="언제, 어디서, 어떤 일이 있었는지 적어 주세요. 모르는 사실은 미확인이라고 남겨도 괜찮아요."')}${area('원하는 해결','userGoal','','maxlength="2000" placeholder="예: 누수 원인을 확인하고 수리 일정을 협의하고 싶어요."')}<div class="button-row"><button>사건 만들기</button><button type="button" class="ghost" data-act="home">취소</button></div></form>`, '새 사건');
}
async function openCase(id) {
  clearPreview(); current = await api(`/cases/${id}`); pendingAnalysis = null; analysisInput = null; selectedAnalysis = null; savedMessage = ''; selectedFile = null; ocr = null;
  filePage = 1; analysisPage = 1; chatPage = 1; tab = 'overview'; await workspace();
}
async function workspace() {
  current = await api(casePath(''));
  const parts = [['overview','사건 정보'],['files','자료 확인'],['analysis','분석 결과'],['action','대응 준비'],['chat','추가 대화']];
  shell(`<button class="ghost" data-act="home">← 나의 사건</button><div class="welcome row wrap"><div><div class="eyebrow">CASE WORKSPACE</div><h1>${esc(current.title)}</h1><small>${esc(current.userPartyRole || '역할 미입력')} · 입력 ${current.version}차 · ${date(current.updatedAt)} 수정</small></div><span class="pill">${esc(labels[current.status] || current.status)}</span></div><nav class="tabs" aria-label="사건 메뉴">${parts.map(([id,title]) => `<button data-act="tab" data-id="${id}" class="${tab === id ? 'active' : ''}" ${tab === id ? 'aria-current="page"' : ''}>${title}</button>`).join('')}</nav><div id="workspace"></div>`, current.title);
  const node = document.querySelector('#workspace');
  if (tab === 'overview') node.innerHTML = `<div class="workspace-grid"><form id="statement-form" class="panel stack"><h2>기록한 상황</h2>${area('상황 설명','originalStatement',current.originalStatement,'required maxlength="50000"')}<p>내용을 바꾸면 이전 분석은 최신 아님으로 표시돼요.</p><button>수정 내용 저장</button></form><aside><section class="panel"><h2>원하는 해결</h2><p class="pre">${esc(current.userGoal || '아직 입력하지 않았어요.')}</p><button class="secondary" data-act="tab" data-id="files">자료 확인하기 →</button></section><section class="panel"><h3>이 순서로 준비해요</h3><ol class="steps"><li>알고 있는 상황을 기록해요</li><li>자료의 인식 내용을 확인해요</li><li>근거와 미확인 사실을 살펴봐요</li><li>상대방에게 보낼 문장을 준비해요</li></ol><button class="danger" data-act="delete-case">사건 삭제</button></section></aside></div>`;
  if (tab === 'files') await renderFiles();
  if (tab === 'analysis') await renderAnalyses();
  if (tab === 'action') {
    if (selectedAnalysis) selectedAnalysis = await api(casePath(`/analyses/${selectedAnalysis.id}`));
    renderAction();
  }
  if (tab === 'chat') await renderChat();
}
async function renderFiles() {
  const page = await api(casePath(`/files?page=${filePage}&pageSize=20`)); files = page.items;
  document.querySelector('#workspace').innerHTML = `<div class="workspace-grid"><section class="panel"><h2>상황을 보여주는 자료</h2><p>계약서, 수리 내역, 문자 캡처 등을 추가해 주세요.</p><form id="upload-form" class="stack drop"><label class="field">파일 선택<input name="file" type="file" required accept="image/jpeg,image/png,image/webp,application/pdf"></label><small>JPG·PNG·WebP·PDF, 파일당 최대 20MB · PDF 최대 30페이지</small><button>파일 올리기</button></form><div class="section-title"><h3>등록한 자료</h3><small>현재 페이지 ${files.length}개</small></div><div class="file-list">${files.map(f => `<div class="file-row"><div class="row"><div class="file-icon">DOC</div><div><strong>${esc(f.originalName)}</strong><small>${esc(labels[f.lifecycleStatus] || f.lifecycleStatus)} · ${(f.sizeBytes/1024).toFixed(0)}KB</small></div></div><button class="secondary" data-act="file" data-id="${esc(f.id)}">내용 확인</button></div>`).join('') || '<div class="empty"><p>자료를 올리면 이곳에 표시돼요.</p></div>'}</div>${pager('files',filePage,page.hasNext)}</section><aside class="panel"><h2>확정하기 전에 확인해요</h2><ol class="steps"><li>원본과 인식 내용을 비교해요</li><li>틀린 날짜·금액·문자를 수정해요</li><li>불필요한 개인정보를 지워요</li><li>확정한 내용만 분석에 사용해요</li></ol><div class="note">확정 후 서버의 원본은 삭제돼요. 확정한 텍스트는 사건에 남아요.</div></aside></div><div id="ocr-panel"></div>`;
  if (selectedFile && files.some(f => f.id === selectedFile.id)) await openFile(selectedFile.id);
}
async function openFile(id) {
  selectedFile = files.find(f => f.id === id) || await api(casePath(`/files/${id}`));
  try { ocr = await api(filePath('/ocr')); } catch (error) { if (error.code === 'OCR_001') ocr = null; else throw error; }
  const text = ocr?.latestRevision?.correctedText ?? ocr?.rawText ?? '';
  const seen = visual(ocr?.visionJson);
  document.querySelector('#ocr-panel').innerHTML = `<section class="panel"><div class="row wrap"><h2>${esc(selectedFile.originalName)}</h2><span class="pill">${ocr?.confirmedRevision ? '확정본 있음' : '직접 확인해 주세요'}</span></div>${ocr?.status === 'succeeded' ? `<div class="ocr-grid"><div><h3>내가 올린 원본</h3>${previewUrl && previewFileId === selectedFile.id && selectedFile.mimeType.startsWith('image/') ? `<img class="preview" alt="현재 업로드한 자료 미리보기" src="${previewUrl}">` : '<div class="empty"><p>현재 브라우저에서 올린 이미지에 한해 미리보기를 제공해요.<br>보유한 원본과 내용을 비교해 주세요.</p></div>'}<details><summary>원문 보기</summary><p class="pre">${esc(ocr.rawText)}</p></details>${seen.observations.length || seen.unknowns.length ? `<div class="note"><strong>사진에서 보이는 내용</strong>${seen.observations.length ? `<ul>${seen.observations.map(item => `<li>${esc(item)}</li>`).join('')}</ul>` : '<p>눈에 보이는 별도 흔적을 확인하지 못했어요.</p>'}${seen.unknowns.length ? `<strong>사진만으로 확인할 수 없는 내용</strong><ul>${seen.unknowns.map(item => `<li>${esc(item)}</li>`).join('')}</ul>` : ''}<small>사진 관찰은 참고용이며 원인·책임을 확정하지 않아요. 내용을 확인한 뒤 수정본과 함께 확정해 주세요.</small></div>` : ''}</div><form id="ocr-save-form" class="stack">${area('인식 내용 확인 및 수정','correctedText',text,'required maxlength="100000"')}<small>원문을 확인한 뒤 이 칸에서 바로 고쳐 주세요.</small><button>수정본 저장</button></form></div><div class="note">인식 내용에는 오타가 있을 수 있어요. 저장한 최신 수정본을 확인한 뒤 확정해 주세요.</div>${ocr.latestRevision ? `<form id="ocr-confirm-form" class="stack">${area('사진에서 보이는 내용 (한 줄에 하나)','observations',seen.observations.join('\n'),'maxlength="3000"')} ${area('사진만으로 확인할 수 없는 내용 (한 줄에 하나)','unknowns',seen.unknowns.join('\n'),'maxlength="3000"')}<small>관찰 내용을 직접 확인·수정하면 확정본과 분석에 반영돼요.</small><label class="check"><input name="reviewed" type="checkbox" required>날짜·금액·문자를 확인하고 불필요한 민감정보를 지웠어요. 확정하면 서버 원본이 삭제되는 것을 이해했어요.</label><button>이 수정본으로 확정하기</button></form>` : '<p>먼저 수정본을 저장해 주세요.'}</div>` : `<form id="ocr-start-form" class="stack"><p>문서 내용을 인식한 뒤 직접 확인할 수 있어요. 처리에 최대 150초가 걸릴 수 있어요.</p><label class="check"><input name="consent" type="checkbox" required>내용 인식을 위해 원본을 OpenAI에 전송하는 데 동의해요.</label><button>${ocr?.status === 'running' ? '인식 결과 확인' : '문서 내용 인식하기'}</button></form>`}<div class="button-row"><button class="ghost" data-act="file" data-id="${esc(id)}">처리 상태 새로고침</button></div></section>`;
}
async function renderAnalyses() {
  const form = document.querySelector('#analysis-form');
  if (form) analysisInput = {query:form.elements.query.value,fileId:form.elements.fileId.value,excerptStart:Number(form.elements.excerptStart.value)};
  const [list, confirmed] = await Promise.all([api(casePath(`/analyses?page=${analysisPage}&pageSize=10`)), api(casePath('/confirmed-evidence?pageSize=100'))]);
  analyses = list.items; evidence = confirmed.evidence.items;
  if (selectedAnalysis) selectedAnalysis = await api(casePath(`/analyses/${selectedAnalysis.id}`));
  else selectedAnalysis = analyses.find(a => a.status === 'succeeded' && !a.stale) || analyses[0] || null;
  document.querySelector('#workspace').innerHTML = `<div class="workspace-grid"><section class="panel"><h2>어떤 점을 확인하고 싶나요?</h2><p>확정한 자료 한 개의 발췌문을 근거와 함께 살펴봐요.</p>${evidence.length ? `<form id="analysis-form" class="stack"><label class="field">확정 자료<select name="fileId" id="evidence-select">${evidence.map((e,i) => `<option value="${esc(e.fileId)}" ${analysisInput?.fileId === e.fileId ? 'selected' : ''}>확정 자료 ${i+1} · ${date(e.confirmedAt)}</option>`).join('')}</select></label>${area('확인할 질문','query',analysisInput?.query || pendingAnalysis?.body.query || '이 자료에서 수선 비용을 확인하려면 어떤 근거와 추가 정보가 필요한가요?','required maxlength="300"')}${field('발췌 시작 위치 (글자 기준)','excerptStart',analysisInput?.excerptStart || 0,'id="excerpt-start" type="number" min="0" required')}<details open><summary>분석에 사용할 발췌문</summary><p id="excerpt-preview" class="quote"></p></details><label class="check"><input name="review" type="checkbox" required>선택한 발췌와 질문이 제가 확인하려는 내용이에요.</label><button>${pendingAnalysis ? '같은 요청의 결과 확인' : '이 내용으로 분석하기'}</button></form>` : '<div class="empty"><p>먼저 자료의 OCR 수정본을 확정해 주세요.</p><button data-act="tab" data-id="files">자료 확인하기</button></div>'}</section><section class="panel"><div class="row"><h2>분석 이력</h2><button class="ghost" data-act="refresh-analysis">새로고침</button></div>${analyses.map(a => `<div class="history-item"><div><strong>${a.version}차 검토</strong><small>${date(a.createdAt)} · ${a.stale ? '최신 아님' : a.status === 'running' ? '분석 중' : a.status === 'failed' ? '실패' : '검토 필요'}</small></div><button class="secondary" data-act="analysis" data-id="${esc(a.id)}">보기</button></div>`).join('') || '<p>아직 분석 이력이 없어요.</p>'}${pager('analyses',analysisPage,list.hasNext)}</section></div><div id="analysis-result"></div>`;
  updateExcerpt(); renderResult();
}
function updateExcerpt() {
  const form = document.querySelector('#analysis-form'); if (!form) return;
  const full = evidence.find(e => e.fileId === form.elements.fileId.value)?.correctedText || '';
  const part = excerpt(full, Number(form.elements.excerptStart.value));
  document.querySelector('#excerpt-preview').textContent = part ? `${part.start}–${part.end} / 전체 ${full.length}자${part.partial ? ' · 일부 발췌' : ''}\n\n${part.text}` : '시작 위치를 확인해 주세요.';
}
function renderResult() {
  const a = selectedAnalysis, node = document.querySelector('#analysis-result'); if (!a || !node) return;
  if (!a.result) { node.innerHTML = `<section class="panel"><h2>${a.version}차 검토</h2><p>${a.status === 'running' ? '분석 중이에요. 잠시 후 이력을 새로고침해 주세요.' : '분석을 완료하지 못했어요. 입력·연동 상태를 확인한 뒤 새 분석을 실행해 주세요.'}</p>${a.errorCode ? `<small>확인 코드: ${esc(a.errorCode)}</small>` : ''}</section>`; return; }
  const r = a.result;
  node.innerHTML = `<section class="panel"><div class="row wrap"><h2>${a.version}차 검토 결과</h2><span class="pill ${a.stale ? 'warn' : ''}">${a.stale ? '최신 아님' : '검토용 안내'}</span></div>${a.stale ? '<div class="note warn">사건 내용이나 자료가 바뀌었어요. 아래는 이전 입력의 결과예요. 현재 자료로 새 분석을 실행해 주세요.</div>' : ''}<h3>기록된 상황</h3><p class="pre">${esc(r.summary)}</p><h3>사용한 발췌문</h3><details><summary>${r.evidence.start}–${r.evidence.end} / 전체 ${r.evidence.totalLength}자 ${r.evidence.partial ? '· 일부 발췌' : ''}</summary><p class="quote">${esc(r.evidence.text)}</p></details><div class="section-title"><h2>함께 확인할 법률 근거</h2><small>${r.findings.length}개</small></div>${r.findings.map(f => `<article class="finding"><h3>${esc(f.source.heading)}</h3><p>${esc(f.explanation)}</p><blockquote class="quote">${esc(f.quote)}</blockquote>${officialLink(f.source.sourceUrl) ? `<a class="source-link" href="${esc(officialLink(f.source.sourceUrl))}" target="_blank" rel="noopener noreferrer">공식 원문 확인 ↗</a>` : '<small>원문 링크 확인 필요</small>'}<small> · 시행일 ${esc(f.source.effectiveFrom)}</small></article>`).join('') || '<div class="note warn">직접 연결할 법률 근거가 부족해요. 자료와 질문을 보완해 주세요.</div>'}<div class="section-title"><h2>추가로 확인해 주세요</h2></div><ul class="steps">${r.questions.map(q => `<li>${esc(q)}</li>`).join('') || '<li>제시된 추가 질문이 없어요.</li>'}</ul><div class="note">${esc(r.notice)}</div><button data-act="tab" data-id="action" ${a.stale ? 'disabled' : ''}>다음 행동 준비하기 →</button></section>`;
}
function renderAction() {
  const a = selectedAnalysis;
  if (a?.stale) { document.querySelector('#workspace').innerHTML = '<section class="panel"><h2>현재 자료로 다시 확인해 주세요</h2><p>이전 분석은 최신이 아니에요. 새 분석 후 대응 문장을 준비해 주세요.</p><button data-act="tab" data-id="analysis">분석 확인하기</button></section>'; return; }
  const questions = a?.result?.questions || [];
  document.querySelector('#workspace').innerHTML = `<div class="workspace-grid"><section class="panel"><h2>상대방에게 보낼 문장 준비</h2><p>기록한 상황을 담은 기본 양식이에요. 사실과 요청을 직접 확인하고 수정해 주세요. 법적 효력이나 기한을 확정하는 문서가 아니에요.</p><form id="message-form" class="stack">${area('요청 메시지 초안','message',savedMessage || messageDraft(current.originalStatement,current.userGoal,questions),'class="doc-area" required maxlength="60000"')}<label class="check"><input name="reviewed" type="checkbox" required>내용을 직접 확인했어요. 전송은 제가 별도로 진행할게요.</label><button>텍스트 파일 내려받기</button></form></section><aside class="panel"><h2>다음 행동 체크리스트</h2><div class="stack"><label class="check"><input type="checkbox">원본 자료를 별도로 보관했어요</label><label class="check"><input type="checkbox">날짜·금액·상대방에게 알린 내용을 확인했어요</label><label class="check"><input type="checkbox">미확인 사실을 단정하지 않았어요</label><label class="check"><input type="checkbox">원하는 조치와 협의할 내용을 적었어요</label></div><div class="note">법적 기한이나 권리 유지에 관한 판단이 필요하면 전문 상담으로 확인해 주세요.</div>${questions.length ? `<h3>더 확인할 내용</h3><ul class="steps">${questions.map(q => `<li>${esc(q)}</li>`).join('')}</ul>` : ''}</aside></div>`;
}
async function renderChat() {
  const page = await api(casePath(`/chat/turns?page=${chatPage}&pageSize=20`));
  document.querySelector('#workspace').innerHTML = `<section class="panel"><h2>상황을 더 정리해 볼까요?</h2><p>이 대화는 사실 정리를 도와요. 선택한 자료나 법률 검색 결과가 자동으로 연결되지는 않아요.</p>${[...page.items].reverse().map(t => `<article class="chat-turn"><h3>나의 질문</h3><p>${esc(t.question)}</p><h3>정리 도우미</h3><p>${esc(t.answer || '완료된 답변이 아직 없어요.')}</p>${t.retryAllowed ? `<button class="secondary" data-act="retry-chat" data-id="${esc(t.id)}" data-attempt="${t.attempt}">이 질문 재시도</button>` : ''}</article>`).join('') || '<div class="empty"><p>헷갈리는 사실이나 더 정리하고 싶은 내용을 적어 주세요.</p></div>'}${pager('chat',chatPage,page.hasNext)}<form id="chat-form" class="stack">${area('추가로 정리할 내용','content','','required maxlength="4000"')}<button>질문 보내기</button></form></section>`;
}
async function perform(button, work) {
  if (busy) return; busy = true;
  const original = button?.innerHTML;
  if (button) button.innerHTML = '<span class="spinner" aria-hidden="true"></span>처리하고 있어요';
  const disabled = [...app.querySelectorAll('button')].filter(b => !b.disabled); disabled.forEach(b => { b.disabled = true; });
  try { await work(); } catch (error) { toast(error.message || '요청을 완료하지 못했어요.', true); }
  finally { busy = false; disabled.filter(b => b.isConnected).forEach(b => { b.disabled = false; }); if (button?.isConnected) button.innerHTML = button.form?.id === 'analysis-form' && pendingAnalysis ? '같은 요청의 결과 확인' : original; }
}
app.addEventListener('submit', event => {
  event.preventDefault(); const form = event.target, data = Object.fromEntries(new FormData(form));
  perform(form.querySelector('button[type="submit"],button:not([type])'), async () => {
    if (form.id === 'auth-form') {
      tokens = await api(signup ? '/auth/register' : '/auth/login', {method:'POST',publicRequest:true,body:signup ? {email:data.email,password:data.password,displayName:data.displayName,termsAccepted:!!data.terms,privacyAccepted:!!data.privacy} : {email:data.email,password:data.password}});
      me = await api('/auth/me'); await home();
    }
    if (form.id === 'case-form') { const value = await api('/cases',{method:'POST',body:data}); await openCase(value.id); toast('새 사건을 만들었어요.'); }
    if (form.id === 'statement-form') { current = await api(casePath(''),{method:'PATCH',body:{originalStatement:data.originalStatement,expectedVersion:current.version}}); selectedAnalysis = null; await workspace(); toast('수정 내용을 저장했어요. 이전 분석은 최신 아님으로 표시돼요.'); }
    if (form.id === 'upload-form') {
      const file = form.elements.file.files[0]; if (!file || file.size > 20*1024*1024) throw new Error('20MB 이하의 파일을 선택해 주세요.');
      const signature = `${current.id}:${file.name}:${file.size}:${file.lastModified}`;
      if (uploadKey?.signature !== signature) uploadKey = {signature,key:crypto.randomUUID()};
      const body = new FormData(); body.append('file',file);
      const value = await api(casePath('/files'),{method:'POST',body,key:uploadKey.key}); uploadKey = null;
      clearPreview(); if (file.type.startsWith('image/')) { previewUrl = URL.createObjectURL(file); previewFileId = value.id; }
      selectedFile = value; filePage = 1; await renderFiles(); toast('자료를 올렸어요. 인식 내용을 확인해 주세요.');
    }
    if (form.id === 'ocr-start-form') { if (ocr?.status === 'running') { await openFile(selectedFile.id); return; } await api(filePath('/ocr'),{method:'POST',body:{externalOcrAccepted:!!data.consent},key:crypto.randomUUID()}); await openFile(selectedFile.id); }
    if (form.id === 'ocr-save-form') { await api(filePath('/ocr-revisions'),{method:'POST',body:{extractionId:ocr.extractionId,expectedRevision:ocr.latestRevision?.revision || 0,correctedText:data.correctedText}}); await openFile(selectedFile.id); toast('수정본을 저장했어요. 내용을 검토하고 확정해 주세요.'); }
    if (form.id === 'ocr-confirm-form') { if (document.querySelector('#ocr-save-form textarea').value !== ocr.latestRevision.correctedText) throw new Error('수정한 내용을 먼저 저장해 주세요.'); const lines = value => value.split('\n').map(item => item.trim()).filter(Boolean); await api(filePath('/confirm'),{method:'POST',body:{revisionId:ocr.latestRevision.id,sensitiveDataReviewed:!!data.reviewed,observations:lines(data.observations || ''),unknowns:lines(data.unknowns || '')}}); clearPreview(); selectedAnalysis = null; await workspace(); toast('수정본과 사진 관찰을 확정했어요. 분석에 사용할 수 있어요.'); }
    if (form.id === 'analysis-form') {
      const body = {fileId:data.fileId,query:data.query,expectedCaseVersion:current.version,excerptStart:Number(data.excerptStart)};
      analysisInput = body;
      const full = evidence.find(e => e.fileId === body.fileId)?.correctedText || '';
      if (!excerpt(full,body.excerptStart)) throw new Error('발췌 시작 위치를 확인해 주세요.');
      if (JSON.stringify(pendingAnalysis?.body) !== JSON.stringify(body)) pendingAnalysis = {body,key:crypto.randomUUID()};
      selectedAnalysis = await api(casePath('/analyses'),{method:'POST',body,key:pendingAnalysis.key});
      if (selectedAnalysis.status !== 'running') pendingAnalysis = null;
      analysisPage = 1; await renderAnalyses(); document.querySelector('#analysis-result').scrollIntoView({behavior:'smooth',block:'start'});
    }
    if (form.id === 'message-form') { savedMessage = data.message; const url = URL.createObjectURL(new Blob([data.message],{type:'text/plain;charset=utf-8'})); const link = document.createElement('a'); link.href = url; link.download = '대응-메시지-검토본.txt'; link.click(); setTimeout(()=>URL.revokeObjectURL(url),1000); toast('초안을 내려받았어요. 전송 전 내용을 다시 확인해 주세요.'); }
    if (form.id === 'chat-form') { await api(casePath('/chat/turns'),{method:'POST',body:{content:data.content},key:crypto.randomUUID()}); chatPage = 1; await renderChat(); }
  });
});
app.addEventListener('click', event => {
  const button = event.target.closest('button[data-act]'); if (!button || button.disabled) return;
  perform(button, async () => {
    const act = button.dataset.act;
    if (act === 'auth-switch') { signup = !signup; renderAuth(); }
    if (act === 'home') await home();
    if (act === 'new-case') newCase();
    if (act === 'open-case') await openCase(button.dataset.id);
    if (act === 'logout') { try { await api('/auth/logout',{method:'POST',body:{refreshToken:tokens.refreshToken}}); } finally { clearSession(); } }
    if (act === 'tab') { const text = document.querySelector('#message-form textarea'); if (text) savedMessage = text.value; tab = button.dataset.id; await workspace(); }
    if (act === 'delete-case' && confirm('이 사건과 연결된 자료를 삭제할까요? 삭제 후 일반 조회에서 숨겨지고 보존 정책에 따라 정리돼요.')) { await api(casePath(''),{method:'DELETE'}); casePage = 1; await home(); }
    if (act === 'file') await openFile(button.dataset.id);
    if (act === 'analysis') { selectedAnalysis = await api(casePath(`/analyses/${button.dataset.id}`)); renderResult(); }
    if (act === 'refresh-analysis') await renderAnalyses();
    if (act === 'page-cases') { casePage = Number(button.dataset.page); await home(); }
    if (act === 'page-files') { filePage = Number(button.dataset.page); selectedFile = null; clearPreview(); await renderFiles(); }
    if (act === 'page-analyses') { analysisPage = Number(button.dataset.page); selectedAnalysis = null; await renderAnalyses(); }
    if (act === 'page-chat') { chatPage = Number(button.dataset.page); await renderChat(); }
    if (act === 'retry-chat') { await api(casePath(`/chat/turns/${button.dataset.id}/retry`),{method:'POST',body:{expectedAttempt:Number(button.dataset.attempt)}}); await renderChat(); }
  });
});
app.addEventListener('input', event => { if (['excerpt-start','evidence-select'].includes(event.target.id)) updateExcerpt(); });
window.addEventListener('beforeunload', event => { if (busy) { event.preventDefault(); event.returnValue = ''; } });
renderAuth();
