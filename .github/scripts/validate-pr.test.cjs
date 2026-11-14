const {test}=require('node:test');
const assert=require('node:assert/strict');
const {validate,meaningful}=require('./validate-pr.cjs');
const good={title:'chore: 검증 강화 (#2)',body:'## 변경 내용\n검증 로직 보완\n## 확인 방법\n자동 테스트 통과\n## 관련 이슈\nCloses #2'};
test('정상 본문과 CRLF 허용',()=>{assert.deepEqual(validate(good),[]);assert.deepEqual(validate({...good,body:good.body.replaceAll('\n','\r\n')}),[]);});
test('주석·기호·미작성 문구 거부',()=>{for(const x of ['.','<!-- 설명 -->','- [ ]','TODO','작성 예정'])assert.equal(meaningful(x),false);});
test('필수 섹션과 이슈 연결 및 제목 검사',()=>{assert.ok(validate({...good,body:''}).length);assert.ok(validate({...good,title:'잘못된 제목'}).length);assert.ok(validate({...good,body:good.body.replace('Closes #2','참조만 작성')}).length);});
