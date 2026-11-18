/**
 * 엔진 전체가 쓰는 위험 입력 검사. VNode는 모두 h()를 거쳐 만들어지므로
 * Phase 1(DOM → VNode)과 Phase 2(컴포넌트의 h 호출)가 같은 규칙으로 막힌다.
 * patch도 같은 함수로 한 번 더 검사해 h()를 거치지 않은 Patch 객체를 막는다.
 */

/** 위험한 입력이라 거부했다는 뜻이다. 화면은 이 오류의 message를 그대로 보여 준다. */
export class UnsafeInputError extends TypeError {
  /** @param {string} message */
  constructor(message) {
    super(message);
    this.name = 'UnsafeInputError';
  }
}

// 스크립트를 실행하거나 문서 밖 자원·설정을 끌어오는 태그
const BLOCKED_TAGS = new Set(['script', 'style', 'iframe', 'object', 'embed', 'base', 'link', 'meta']);

// 값이 URL로 해석되는 속성. data는 <object data>다
const URL_ATTRS = new Set(['href', 'src', 'action', 'formaction', 'xlink:href', 'data']);

// 브라우저는 URL 앞의 제어문자·공백을 버리고 중간의 탭·줄바꿈을 지운 뒤 읽는다. "java\tscript:"도 실행된다
const JS_URL = /^[\u0000- ]*j[\r\n\t]*a[\r\n\t]*v[\r\n\t]*a[\r\n\t]*s[\r\n\t]*c[\r\n\t]*r[\r\n\t]*i[\r\n\t]*p[\r\n\t]*t[\r\n\t]*:/i;

/** @param {string} tag */
export function assertSafeTag(tag) {
  if (BLOCKED_TAGS.has(tag)) {
    throw new UnsafeInputError(`<${tag}> 태그는 스크립트나 외부 자원을 실행할 수 있어 막았어요.`);
  }
}

/**
 * @param {string} name
 * @param {string} value
 */
export function assertSafeAttribute(name, value) {
  // HTML 속성 이름은 대소문자를 구분하지 않으므로 ONCLICK도 인라인 핸들러다
  if (/^on/i.test(name)) {
    throw new UnsafeInputError(`${name} 속성은 문자열 코드를 실행해서 막았어요. 이벤트는 events에 함수로 넣어 주세요.`);
  }
  if (URL_ATTRS.has(name.toLowerCase()) && JS_URL.test(value)) {
    throw new UnsafeInputError(`${name}="${value}"는 javascript: URL이라 막았어요.`);
  }
}
