import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import assert from 'node:assert/strict';

// 실제 UI와 고정 API 계약을 함께 검증한다. 외부 AI나 운영 데이터는 호출하지 않는다.
const root = resolve('src/main/resources/static');
const server = createServer(async (req,res) => {
  const path = resolve(root, '.' + (req.url === '/' ? '/index.html' : req.url.split('?')[0]));
  if (!path.startsWith(root + sep)) { res.writeHead(404).end(); return; }
  try { res.setHeader('Content-Type', ({'.html':'text/html','.mjs':'text/javascript','.css':'text/css'})[extname(path)] || 'text/plain'); res.end(await readFile(path)); }
  catch { res.writeHead(404).end(); }
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
await mkdir('build/ui-smoke',{recursive:true});
const browser = await chromium.launch({headless:true});
const context = await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
const page = await context.newPage();
const errors = []; page.on('pageerror',e=>errors.push(e.message));
const caseId='11111111-1111-4111-8111-111111111111', fileId='22222222-2222-4222-8222-222222222222', revisionId='33333333-3333-4333-8333-333333333333';
let chat=[], caseData, file, ocr, revision, analysis, createCalls=0, refreshCalls=0, expireOnce=false, failAnalysis=false;
const stamp='2026-09-10T05:00:00Z';
const paged = items => ({items,page:1,pageSize:20,hasNext:false});
await page.route('**/api/v1/**',async route=>{
  const request=route.request(), path=new URL(request.url()).pathname.replace('/api/v1',''), method=request.method();
  const send=(data,status=200)=>route.fulfill({status,json:{success:true,data}});
  const fail=(code,message,status)=>route.fulfill({status,json:{success:false,code,message,traceId:'ui-test'}});
  if(path==='/auth/register'||path==='/auth/login')return send({accessToken:'test-access',refreshToken:'test-refresh'});
  if(path==='/auth/refresh'){refreshCalls++;return send({accessToken:'test-access-2',refreshToken:'test-refresh-2'});}
  if(path==='/auth/me')return send({displayName:'테스트',id:caseId,email:'ui@example.test'});
  if(path==='/auth/logout')return route.fulfill({status:204});
  assert.match(request.headers().authorization,/Bearer test-access/);
  if(expireOnce){expireOnce=false;return fail('AUTH_001','인증이 필요해요',401);}
  if(path==='/cases'&&method==='POST'){caseData={...request.postDataJSON(),id:caseId,status:'DRAFT',version:1,createdAt:stamp,updatedAt:stamp};return send(caseData,201);}
  if(path==='/cases')return send(paged(caseData?[caseData]:[]));
  if(path===`/cases/${caseId}`){
    if(method==='DELETE'){caseData=null;return route.fulfill({status:204});}
    if(method==='PATCH'){caseData.originalStatement=request.postDataJSON().originalStatement;caseData.version++;if(analysis)analysis.stale=true;}
    return send(caseData);
  }
  if(path.endsWith('/confirmed-evidence'))return send({caseId,caseVersion:caseData.version,evidence:paged(revision?.confirmedAt?[{fileId,revisionId,correctedText:revision.correctedText,confirmedAt:stamp}]:[])});
  if(path.endsWith('/files')){
    if(method==='POST'){assert.ok(request.headers()['idempotency-key']);file={id:fileId,caseId,originalName:'누수.png',mimeType:'image/png',sizeBytes:500,lifecycleStatus:'UPLOADED',purgeStatus:'scheduled'};return send(file,201);}
    return send(paged(file?[file]:[]));
  }
  if(path.endsWith('/ocr')){
    if(method==='POST'){assert.equal(request.postDataJSON().externalOcrAccepted,true);ocr={fileId,extractionId:fileId,status:'succeeded',rawText:'천장 누수 확인. 원인 미확인.'};}
    return ocr?send({...ocr,latestRevision:revision,confirmedRevision:revision?.confirmedAt?revision:null}):fail('OCR_001','인식 전이에요',409);
  }
  if(path.endsWith('/ocr-revisions')){revision={id:revisionId,extractionId:fileId,revision:(revision?.revision||0)+1,correctedText:request.postDataJSON().correctedText};return send(revision,201);}
  if(path.endsWith('/confirm')){assert.equal(request.postDataJSON().sensitiveDataReviewed,true);revision.confirmedAt=stamp;caseData.version++;file.lifecycleStatus='PURGED';return send(revision);}
  if(path.endsWith('/analyses')&&method==='POST'){
    createCalls++;
    if(failAnalysis)return fail('LEGAL_DATA_002','법률 근거 검색을 완료하지 못했어요',503);
    analysis={id:revisionId,caseId,version:1,status:'succeeded',stale:false,createdAt:stamp,request:request.postDataJSON(),result:{caseId,caseVersion:caseData.version,status:'NEEDS_REVIEW',summary:'천장 누수가 확인되었으나 원인과 비용 부담 약정은 미확인이에요.',evidence:{fileId,revisionId,text:revision.correctedText,start:0,end:revision.correctedText.length,totalLength:revision.correctedText.length,partial:false},findings:[{quote:'사용, 수익에 필요한 상태를 유지하게 할 의무를 부담한다.',explanation:'수선 범위와 누수 원인, 임대인에게 알린 내용을 확인해 주세요.',source:{heading:'민법 제623조',sourceUrl:'https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=284415',effectiveFrom:'2026-03-17'}}],questions:['누수 원인을 확인한 점검 자료가 있나요?'],notice:'검토용 초안이에요. 사건 당시 법령과 사실관계 확인이 필요해요.'}};return send(analysis);
  }
  if(path.endsWith('/analyses'))return send(paged(analysis?[analysis]:[]));
  if(path.includes('/analyses/'))return send(analysis);
  if(path.endsWith('/chat/turns')){
    if(method==='POST'){assert.ok(request.headers()['idempotency-key']);chat=[{id:revisionId,question:request.postDataJSON().content,answer:'점검 일시와 전달 내용을 기록해 주세요.',attempt:1,retryAllowed:false,createdAt:stamp}];return send(chat[0]);}
    return send(paged(chat));
  }
  throw new Error(`Unhandled test endpoint ${method} ${path}`);
});
try {
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.screenshot({path:'build/ui-smoke/login-desktop.png',fullPage:true});
  await page.getByRole('button',{name:'회원가입',exact:true}).click();
  await page.getByLabel('이름',{exact:true}).fill('테스트');
  await page.getByLabel('이메일',{exact:true}).fill('ui@example.test');
  await page.getByLabel('비밀번호',{exact:true}).fill('Test-password-2026');
  await page.locator('[name=terms]').check(); await page.locator('[name=privacy]').check();
  await page.getByRole('button',{name:'동의하고 시작하기'}).click();
  await page.getByRole('button',{name:'첫 사건 만들기'}).waitFor();
  await page.getByRole('button',{name:'첫 사건 만들기'}).click();
  await page.getByLabel('사건 이름').fill('월셋집 천장 누수');
  await page.getByLabel('상황 설명').fill('천장에 누수가 생겼어요. 집주인에게 알렸고 원인 확인을 기다리고 있어요.');
  await page.getByLabel('원하는 해결',{exact:true}).fill('누수 원인을 확인하고 수리 일정을 협의하고 싶어요.');
  await page.getByRole('button',{name:'사건 만들기',exact:true}).click();
  await page.getByRole('button',{name:'자료 확인',exact:true}).click();
  await page.getByLabel('파일 선택').setInputFiles({name:'누수.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2l8AAAAASUVORK5CYII=','base64')});
  await page.getByRole('button',{name:'파일 올리기',exact:true}).click();
  await page.locator('[name=consent]').check();
  await page.getByRole('button',{name:'문서 내용 인식하기'}).click();
  await page.getByLabel('인식 내용 수정').fill('천장 누수 확인. 원인은 아직 미확인.');
  await page.getByRole('button',{name:'수정본 저장'}).click();
  await page.getByRole('status').filter({hasText:'수정본을 저장했어요'}).waitFor();
  await page.getByLabel('인식 내용 수정').fill('아직 저장하지 않은 변경');
  await page.locator('#ocr-confirm-form [name=reviewed]').check();
  await page.getByRole('button',{name:'이 수정본으로 확정하기'}).click();
  await page.getByRole('status').filter({hasText:'먼저 저장'}).waitFor(); assert.equal(revision.confirmedAt,undefined);
  await page.getByLabel('인식 내용 수정').fill(revision.correctedText);
  await page.getByRole('button',{name:'이 수정본으로 확정하기'}).click();
  expireOnce=true;
  await page.getByRole('button',{name:'분석 결과',exact:true}).click();
  await page.getByLabel('확인할 질문').fill('누수 수선과 관련해 어떤 근거를 확인할 수 있나요?');
  await page.locator('#analysis-form [name=review]').check();
  await page.getByRole('button',{name:'이 내용으로 분석하기'}).click();
  await page.getByRole('heading',{name:'1차 검토 결과'}).waitFor();
  assert.equal(createCalls,1);assert.equal(refreshCalls,1);
  await page.locator('#notice').waitFor({state:'hidden'});
  await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));
  await page.screenshot({path:'build/ui-smoke/analysis-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth));
  await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));
  await page.screenshot({path:'build/ui-smoke/analysis-mobile.png',fullPage:true});
  await page.getByRole('button',{name:'다음 행동 준비하기 →'}).click();
  await page.locator('#message-form [name=reviewed]').check();
  const downloadPromise=page.waitForEvent('download');
  await page.getByRole('button',{name:'텍스트 파일 내려받기'}).click();
  const download=await downloadPromise; assert.equal(download.suggestedFilename(),'대응-메시지-검토본.txt');
  analysis.stale=true;
  await page.getByRole('button',{name:'대응 준비',exact:true}).click();
  await page.getByRole('heading',{name:'현재 자료로 다시 확인해 주세요'}).waitFor();
  analysis.stale=false;
  await page.getByRole('button',{name:'사건 정보',exact:true}).click();
  await page.getByLabel('상황 설명').fill('추가 점검 내용을 기록했어요.');
  await page.getByRole('button',{name:'수정 내용 저장'}).click();
  await page.getByRole('button',{name:'분석 결과',exact:true}).click();
  await page.getByText('아래는 이전 입력의 결과예요.',{exact:false}).waitFor();
  assert.equal(await page.getByRole('button',{name:'다음 행동 준비하기 →'}).isDisabled(),true);
  failAnalysis=true;
  await page.locator('#analysis-form [name=review]').check();
  await page.getByRole('button',{name:'이 내용으로 분석하기'}).click();
  await page.getByRole('status').filter({hasText:'법률 근거 검색'}).waitFor();
  assert.ok(await page.getByLabel('확인할 질문').inputValue());
  await page.getByRole('button',{name:'추가 대화',exact:true}).click();
  await page.getByLabel('추가로 정리할 내용').fill('점검 전에 무엇을 기록할까요?');
  await page.getByRole('button',{name:'질문 보내기'}).click();
  await page.getByText('점검 일시와 전달 내용을 기록해 주세요.',{exact:true}).waitFor();
  await page.getByRole('button',{name:'로그아웃'}).click();
  await page.getByRole('button',{name:'로그인',exact:true}).waitFor();
  assert.deepEqual(errors,[]);
  console.log('UI smoke passed: signup, case, upload, OCR review/confirm, refresh, analysis, source, stale, download, error, logout, mobile');
} finally { await browser.close(); await new Promise(r=>server.close(r)); }
