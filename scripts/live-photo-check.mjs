import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

// 명시적으로만 실행하는 실제 로컬 서버·OpenAI 검증. npm test에는 포함하지 않는다.
const base = process.env.LIVE_BASE_URL || 'http://localhost:8081';
const domain = process.env.LIVE_DOMAIN || 'vehicle_accident';
const domainInputs = {
  vehicle_accident: ['차량 사고 현장사진 실제 연동 검증', '사용자 설명: 차량 두 대가 충돌했고 차량 손상과 사고 경위가 보입니다. 충돌 원인, 신호·속도·과실 비율, 인명 피해와 보험 처리 여부는 미확인이다.', '운전자'],
  labor: ['2026년 3월 임금 체불 검토', '2025년 12월 1일부터 2026년 3월 15일까지 카페에서 근무했습니다. 월급 240만원으로 근로계약서를 작성했다고 주장하지만 2026년 2월·3월 급여 480만원을 받지 못했습니다. 3월 15일 퇴직했고 사업주는 다음 달에 지급하겠다고 답했습니다. 미확인 사실은 구분해 주세요.', '근로자'],
  consumer: ['온라인 노트북 하자 환불 검토', '2026년 8월 2일 온라인몰에서 노트북을 120만원에 결제했습니다. 8월 5일 수령 후 화면 깜빡임을 확인해 8월 6일 교환·환불을 요청했지만 판매자는 사용 흔적을 이유로 거부했다고 주장합니다. 배송·사용 상태와 판매자 답변 원문은 확인이 필요합니다.', '소비자'],
  family: ['이혼 후 양육 사실 정리', '2024년 6월 협의이혼 후 초등학생 자녀의 주 양육을 제가 맡고 있습니다. 상대방이 2026년 7월부터 약속한 양육비 월 80만원을 지급하지 않았다고 주장합니다. 양육비 약정서와 송금 내역의 존재·내용은 확인이 필요합니다.', '본인'],
  inheritance: ['부친 사망 후 상속재산 확인', '2026년 1월 부친이 사망했고 상속인은 자녀 2명이라고 들었습니다. 아파트 1채와 예금이 있다는 가족의 설명이 있지만 등기·금융 조회 자료와 유언장 존재는 확인되지 않았습니다. 상속세 신고와 재산 분할에 필요한 자료를 정리하고 싶습니다.', '상속인'],
  defamation: ['온라인 허위 게시물 대응 자료 정리', '2026년 8월 지역 커뮤니티에 제가 회사 돈을 횡령했다는 글이 게시됐다고 주장합니다. 게시자 계정과 게시 시각은 캡처에 남아 있다고 하지만 원본 파일·URL·조회 수는 아직 확인하지 못했습니다. 삭제 요청과 정정보도에 필요한 자료를 알고 싶습니다.', '피해 주장자'],
  personal_injury: ['업무 중 넘어짐 치료자료 정리', '2026년 7월 18일 창고 바닥에서 미끄러져 손목을 다쳤다고 주장합니다. 당일 병원에서 2주 치료 소견을 받았고 치료비 35만원을 지출했다고 하지만 업무상 재해 인정과 CCTV·사고보고서 존재는 확인이 필요합니다.', '피해 주장자'],
  commercial: ['하도급 대금 지연 사실 정리', '2026년 5월 인테리어 공사를 1,500만원에 하도급받아 6월 30일 완공했다고 주장합니다. 발주처가 잔금 300만원을 7월 15일까지 지급하기로 했지만 아직 받지 못했고, 계약서·검수서·세금계산서와 지급 약정 내용을 확인하고 싶습니다.', '거래 당사자']
};
const domainQuestions = {
  vehicle_accident: '사진에서 확인되는 차량 손상과 확인되지 않은 사고 원인·과실·책임을 구분하고 추가 자료를 안내해 주세요.',
  labor: '2026년 2~3월 임금 480만원 미지급 주장과 사진에서 확인되는 자료를 구분하고, 근로계약·급여·퇴직 관련 법령 근거와 추가 자료를 안내해 주세요.',
  consumer: '120만원 노트북의 수령 3일 후 화면 하자와 환불 거부 주장, 사진에서 확인되는 자료를 구분하고 청약철회·교환·환불 관련 근거와 추가 자료를 안내해 주세요.',
  family: '협의이혼 후 월 80만원 양육비 미지급 주장과 사진에서 확인되는 자료를 구분하고, 약정·지급 사실 확인에 필요한 절차와 자료를 안내해 주세요.',
  inheritance: '2026년 1월 사망 후 아파트·예금 상속 주장과 사진에서 확인되는 자료를 구분하고, 상속관계·재산·세금 확인에 필요한 절차와 자료를 안내해 주세요.',
  defamation: '2026년 8월 횡령 의혹 게시물 주장과 사진에서 확인되는 자료를 구분하고, 게시물 원문·작성자·피해 사실 확인에 필요한 자료와 절차를 안내해 주세요.',
  personal_injury: '2026년 7월 18일 업무 중 넘어져 손목을 다쳤다는 주장과 사진에서 확인되는 자료를 구분하고, 업무상 재해·치료 자료 확인에 필요한 절차를 안내해 주세요.',
  commercial: '2026년 6월 완공한 1,500만원 하도급 공사의 잔금 300만원 미지급 주장과 사진에서 확인되는 자료를 구분하고, 계약·검수·지급 자료와 관련 근거를 안내해 주세요.'
};
if (!domainInputs[domain]) throw new Error(`Unsupported LIVE_DOMAIN: ${domain}`);
const url = new URL(base);
if (!['localhost','127.0.0.1'].includes(url.hostname)) throw new Error('Local server required');
if (process.env.RUN_LIVE_AI !== '1' || !process.env.LIVE_IMAGE_PATH) throw new Error('Set RUN_LIVE_AI=1 and LIVE_IMAGE_PATH');
const out = `build/live-ui/${Date.now()}`;
await mkdir(out,{recursive:true});
const report={checks:{},http:[],startedAt:new Date().toISOString()};
const browser=await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
page.setDefaultTimeout(180000);
let tokens,caseId,analysisId,analysisBody,analysisKey;
page.on('response',async response=>{
  if(!response.url().includes('/api/v1/'))return;
  const request=response.request(), path=new URL(response.url()).pathname;
  report.http.push({method:request.method(),path:path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g,':id'),status:response.status()});
  try {
    if(path.endsWith('/auth/register')&&response.ok())tokens=(await response.json()).data;
    if(path.endsWith('/cases')&&request.method()==='POST'&&response.ok())caseId=(await response.json()).data.id;
  } catch {}
});
const api=async(path,method='GET',body,key)=>{
  const response=await fetch(base+'/api/v1'+path,{method,headers:{Authorization:`Bearer ${tokens.accessToken}`,...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined});
  return {status:response.status,data:response.status===204?null:(await response.json()).data};
};
const waitPost=part=>page.waitForResponse(r=>new URL(r.url()).pathname.endsWith(part)&&r.request().method()==='POST');
try {
  await page.goto(base);
  await page.getByRole('button',{name:'회원가입',exact:true}).click();
  await page.getByLabel('이름',{exact:true}).fill('현장사진 검증');
  await page.getByLabel('이메일',{exact:true}).fill(`live-${randomUUID()}@example.test`);
  await page.getByLabel('비밀번호',{exact:true}).fill(`Test-${randomUUID()}!`);
  await page.locator('[name=terms]').check();await page.locator('[name=privacy]').check();
  await page.getByRole('button',{name:'동의하고 시작하기'}).click();
  await page.getByRole('button',{name:'첫 사건 만들기'}).click();
  const [title, statement, role] = domainInputs[domain];
  await page.getByLabel('사건 이름').fill(title);
  await page.locator('[name=disputeDomain]').selectOption(domain);
  await page.locator('[name=userPartyRole]').selectOption({label:role});
  await page.getByLabel('상황 설명').fill(statement);
  await page.getByLabel('원하는 해결',{exact:true}).fill('사진에서 확인 가능한 손상과 사고 경위 확인에 필요한 추가 자료를 정리한다.');
  await page.getByRole('button',{name:'사건 만들기',exact:true}).click();
  await page.getByRole('button',{name:/자료 확인/}).first().click();
  await page.getByLabel('파일 선택').setInputFiles(process.env.LIVE_IMAGE_PATH);
  const uploadResponse=waitPost('/files');
  await page.getByRole('button',{name:'파일 올리기',exact:true}).click();
  const upload=await uploadResponse; assert.equal(upload.status(),201);
  const file=(await upload.json()).data; report.checks.upload=true;
  await page.locator('[name=consent]').check();
  const ocrResponse=waitPost('/ocr');
  const ocrStart=Date.now();
  await page.getByRole('button',{name:'문서 내용 인식하기'}).click();
  const ocrHttp=await ocrResponse; assert.equal(ocrHttp.status(),200);
  const ocr=(await ocrHttp.json()).data;
  report.ocrMs=Date.now()-ocrStart;
  report.checks.noInventedOcr=ocr.rawText.trim()==='[인식 가능한 텍스트 없음]';
  report.checks.ocrReturned=Boolean(ocr.rawText && ocr.rawText.trim());
  if (domain === 'vehicle_accident') assert.equal(report.checks.noInventedOcr,true,'OCR must not invent text from a scene photo');
  await page.getByLabel('인식 내용 확인 및 수정').waitFor();
  await page.getByRole('button',{name:'수정본 저장'}).click();
  await page.getByRole('status').filter({hasText:'수정본을 저장했어요'}).waitFor();
  await page.locator('#ocr-confirm-form [name=reviewed]').check();
  await page.getByRole('button',{name:'이 수정본으로 확정하기'}).click();
  await page.getByRole('status').filter({hasText:'수정본과 사진 관찰을 확정했어요'}).waitFor();
  const metadata=await api(`/cases/${caseId}/files/${file.id}`);
  report.checks.originalPurged=metadata.data.purgeStatus==='purged';
  report.purgeStatus=metadata.data.purgeStatus;
  await page.getByRole('button',{name:'분석 결과',exact:true}).click();
  await page.getByLabel('확인할 질문').fill(domainQuestions[domain]);
  await page.locator('#analysis-form [name=review]').check();
  const analysisResponse=waitPost('/analyses'),analysisStart=Date.now();
  await page.getByRole('button',{name:'이 내용으로 분석하기'}).click();
  const analysisHttp=await analysisResponse;
  assert.equal(analysisHttp.status(),200);
  const analysis=(await analysisHttp.json()).data;
  analysisId=analysis.id;analysisBody=analysisHttp.request().postDataJSON();analysisKey=analysisHttp.request().headers()['idempotency-key'];
  report.analysisMs=Date.now()-analysisStart;
  report.analysis=analysis.result;
  assert.equal(analysis.status,'succeeded');
  if (domain === 'vehicle_accident') assert.equal(analysis.result.evidence.text,'[인식 가능한 텍스트 없음]');
  report.checks.analysisSaved=true;
  await page.getByRole('heading',{name:'1차 검토 결과'}).waitFor();
  const replay=await api(`/cases/${caseId}/analyses`,'POST',analysisBody,analysisKey);
  assert.equal(replay.data.id,analysisId);report.checks.idempotencyReplay=true;
  const read=await api(`/cases/${caseId}/analyses/${analysisId}`);
  assert.deepEqual(read.data.result,analysis.result);report.checks.persistedRead=true;
  await page.setViewportSize({width:390,height:844});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));report.checks.mobile=true;
  await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));
  await page.screenshot({path:`${out}/analysis-mobile.png`,fullPage:true});
  await page.getByRole('button',{name:'다음 행동 준비하기 →'}).click();
  await page.locator('#message-form [name=reviewed]').check();
  const downloaded=page.waitForEvent('download');
  await page.getByRole('button',{name:'텍스트 파일 내려받기'}).click();
  assert.equal((await downloaded).suggestedFilename(),'대응-메시지-검토본.txt');report.checks.download=true;
  await page.getByRole('button',{name:'사건 정보',exact:true}).click();
  await page.getByLabel('상황 설명').fill('사용자 설명: 천장 누수가 있다. 추가 점검은 아직 하지 않았다. 원인과 책임은 미확인이다.');
  await page.getByRole('button',{name:'수정 내용 저장'}).click();
  await page.getByRole('status').filter({hasText:'수정 내용을 저장했어요'}).waitFor();
  const stale=await api(`/cases/${caseId}/analyses/${analysisId}`);
  assert.equal(stale.data.stale,true);report.checks.stale=true;
} catch(error) {
  report.failure=error.message;process.exitCode=1;
} finally {
  if(caseId&&tokens){const result=await api(`/cases/${caseId}`,'DELETE');report.checks.caseDeleted=result.status===204;}
  if(tokens){const result=await api('/auth/logout','POST',{refreshToken:tokens.refreshToken});report.checks.logout=result.status===204;}
  report.finishedAt=new Date().toISOString();
  await writeFile(`${out}/report.json`,JSON.stringify(report,null,2));
  await browser.close();
  console.log(JSON.stringify({report:`${out}/report.json`,checks:report.checks,failure:report.failure}));
}
