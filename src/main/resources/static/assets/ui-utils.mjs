export const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function officialLink(value) {
  try { const u = new URL(value); return u.protocol === 'https:' && u.hostname === 'www.law.go.kr' && !u.username && !u.password ? u.href : null; }
  catch { return null; }
}
export function excerpt(text, start) {
  if (!Number.isInteger(start) || start < 0 || start >= text.length) return null;
  const low = n => n >= 0xDC00 && n <= 0xDFFF;
  const high = n => n >= 0xD800 && n <= 0xDBFF;
  if (start > 0 && low(text.charCodeAt(start)) && high(text.charCodeAt(start - 1))) return null;
  let end = Math.min(text.length, start + 650);
  if (end < text.length && high(text.charCodeAt(end - 1)) && low(text.charCodeAt(end))) end--;
  return { text: text.slice(start, end), start, end, partial: start > 0 || end < text.length };
}
export function messageDraft(statement, goal, questions = []) {
  return `안녕하세요. 임대차 주택과 관련하여 아래 내용을 확인하고 협의하고자 연락드립니다.\n\n[제가 확인한 상황 — 발송 전 사실을 다시 확인해 주세요]\n${statement || '(상황을 입력해 주세요)'}\n\n[요청드리는 내용]\n${goal || '(원하는 조치와 협의 내용을 입력해 주세요)'}\n\n[함께 확인할 사항]\n${questions.map(q => `• ${q}`).join('\n') || '(확인이 필요한 사항을 입력해 주세요)'}\n\n관련 자료를 함께 확인하고 가능한 조치와 일정을 회신 부탁드립니다. 감사합니다.`;
}
