const fs = require('node:fs');
function meaningful(text) {
  const cleaned = text.replace(/<!--[\s\S]*?-->/g, '').trim();
  return /[\p{L}\p{N}]/u.test(cleaned) && !/^(?:[-*]\s*)?(?:TODO|TBD|미작성|작성 예정|없음|N\/A)$/i.test(cleaned);
}
function section(body, heading) {
  const part = body.replace(/<!--[\s\S]*?-->/g, '').split(/^##\s+/m).slice(1).find(s => s.split('\n')[0].trim() === heading);
  return part ? part.slice(part.indexOf('\n') + 1).trim() : '';
}
function validate(pr) {
  const errors = [];
  for (const heading of ['변경 내용', '확인 방법', '관련 이슈']) {
    if (!meaningful(section(pr.body || '', heading))) errors.push(`${heading}: 실제 내용을 작성해주세요.`);
  }
  if (!/\bCloses\s+#[1-9]\d*\b/i.test(section(pr.body || '', '관련 이슈'))) errors.push('관련 이슈에 Closes #번호가 필요합니다.');
  if (!/^(feat|fix|refactor|chore|docs|test):\s+.+\s+\(#[1-9]\d*\)$/.test(pr.title || '')) errors.push('PR 제목은 feat: 작업 설명 (#12) 형식이어야 합니다.');
  return errors;
}
async function main() {
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const pr = event.pull_request;
  const errors = validate(pr);
  const ids = [...section(pr.body || '', '관련 이슈').matchAll(/\bCloses\s+#([1-9]\d*)\b/gi)].map(m => m[1]);
  for (const id of new Set(ids)) {
    const response = await fetch(`https://api.github.com/repos/${process.env.GITHUB_REPOSITORY}/issues/${id}`, {headers: {Authorization: `Bearer ${process.env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json'}});
    if (!response.ok) { errors.push(`이슈 #${id} 조회 실패 (${response.status})`); continue; }
    const issue = await response.json();
    if (issue.pull_request) errors.push(`#${id}는 이슈가 아닌 PR입니다.`);
  }
  if (errors.length) { errors.forEach(e => console.error(e)); process.exitCode = 1; }
  else console.log('PR 제목·본문·연결 이슈 검증 통과');
}
if (require.main === module) main().catch(e => {console.error(e);process.exitCode = 1;});
module.exports = {validate, meaningful, section};
