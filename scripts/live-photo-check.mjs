import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

// 명시적으로만 실행하는 실제 로컬 서버·OpenAI 검증. npm test에는 포함하지 않는다.
const base = process.env.LIVE_BASE_URL || 'http://localhost:8081';
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
  await page.getByLabel('사건 이름').fill('차량 사고 현장사진 실제 연동 검증');
  await page.locator('[name=disputeDomain]').selectOption('vehicle_accident');
  await page.getByLabel('상황 설명').fill('사용자 설명: 차량 두 대가 충돌했고 차량 손상과 사고 경위가 보입니다. 충돌 원인, 신호·속도·과실 비율, 인명 피해와 보험 처리 여부는 미확인이다.');
  await page.getByLabel('원하는 해결',{exact:true}).fill('사진에서 확인 가능한 손상과 사고 경위 확인에 필요한 추가 자료를 정리한다.');
  await page.getByRole('button',{name:'사건 만들기',exact:true}).click();
  await page.getByRole('button',{name:'자료 확인',exact:true}).click();
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
  assert.equal(report.checks.noInventedOcr,true,'OCR must not invent text from a scene photo');
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
  await page.getByLabel('확인할 질문').fill('사용자 설명으로는 차량 두 대가 충돌했습니다. 확정 OCR에는 글자가 없습니다. 사진에서 확인되는 손상과 확인되지 않은 사고 원인·과실·책임을 구분하고 추가 자료를 안내해 주세요.');
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
  assert.equal(analysis.result.evidence.text,'[인식 가능한 텍스트 없음]');
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
