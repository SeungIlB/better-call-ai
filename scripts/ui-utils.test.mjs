import test from 'node:test';
import assert from 'node:assert/strict';
import {escapeHtml, officialLink, excerpt, messageDraft} from '../src/main/resources/static/assets/ui-utils.mjs';

test('user text is escaped and only official HTTPS links are offered', () => {
  assert.equal(escapeHtml('<img onerror="x">&'), '&lt;img onerror=&quot;x&quot;&gt;&amp;');
  for (const url of ['javascript:alert(1)','https://www.law.go.kr.evil.test','http://www.law.go.kr','https://user@www.law.go.kr']) assert.equal(officialLink(url), null);
  assert.equal(officialLink('https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=1'), 'https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=1');
});
test('excerpt matches server UTF-16 boundaries without splitting emoji', () => {
  const text = '가'.repeat(649) + '😀끝';
  assert.equal(excerpt(text,0).end,649);
  assert.equal(excerpt(text,649).text,'😀끝');
  assert.equal(excerpt(text,650),null);
  assert.equal(excerpt(text,652),null);
  assert.equal(excerpt(text,0).partial,true);
});
test('message template preserves claims without invented dates or amounts', () => {
  const draft = messageDraft('원인 미확인','점검 일정 협의',['통지 기록 확인']);
  assert.ok(draft.includes('원인 미확인'));
  assert.ok(draft.includes('점검 일정 협의'));
  assert.ok(!draft.includes('승소'));
});
