const fs = require('node:fs');
const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
function validate(pr) {
  const body = (pr.body || '').replace(/<!--[\s\S]*?-->/g, '');
  const errors = [];
  for (const heading of ['변경 내용', '확인 방법', '관련 이슈']) {
    const sections = body.split(/^##\s+/m).slice(1);
    const section = sections.find(s => s.split('\n')[0].trim() === heading);
    const text = section ? section.slice(section.indexOf('\n') + 1).trim() : '';
    if (!text.replace(/[-*\s\[\]xX]/g, '') || /^(TODO|TBD|미작성|작성 예정)$/i.test(text)) errors.push(`${heading}: 실제 내용을 작성해주세요.`);
    if (heading === '관련 이슈' && !/\bCloses\s+#[1-9]\d*\b/i.test(text)) errors.push('관련 이슈에 Closes #번호가 필요합니다.');
  }
  if (!/^(feat|fix|refactor|chore|docs|test):\s+.+\s+\(#[1-9]\d*\)$/.test(pr.title || '')) errors.push('PR 제목은 feat: 작업 설명 (#12) 형식이어야 합니다.');
  return errors;
}
if (require.main === module) {
  const errors = validate(event.pull_request);
  if (errors.length) { errors.forEach(e => console.error(e)); process.exitCode = 1; }
  else console.log('PR 제목·본문 검증 통과');
}
module.exports = { validate };
