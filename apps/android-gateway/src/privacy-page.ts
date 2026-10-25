import { resolvePrivacyRightsRequestUrl } from "./config.js";

function escapedHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => {
    const replacements: Record<string, string> = {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;"
    };
    return replacements[character]!;
  });
}

export function privacyRightsPage(requestUrl: string, headOnly: boolean): Response {
  const href = escapedHtml(resolvePrivacyRightsRequestUrl(requestUrl));
  const body = headOnly
    ? null
    : `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WalkSafe 개인정보 권리 요청</title>
  <style>body{max-width:44rem;margin:2rem auto;padding:0 1rem;font:1.05rem/1.6 system-ui,sans-serif}a{display:inline-block;padding:.8rem 1rem;background:#173b6c;color:#fff;border-radius:.4rem}</style>
</head>
<body>
  <main>
    <h1>WalkSafe 개인정보 권리 요청</h1>
    <p>앱 설치 여부와 로그인 상태에 관계없이 서버 자료 열람, 수집·이용 동의 철회, 서버 자료 삭제를 요청할 수 있습니다.</p>
    <p>요청 접수 뒤 본인 확인과 처리 범위·예외·예상 기한은 운영 담당자가 별도로 안내합니다.</p>
    <p><a href="${href}" rel="noreferrer noopener">권리 요청 시작</a></p>
  </main>
</body>
</html>`;
  return new Response(body, {
    status: 200,
    headers: {
      "cache-control": "no-store",
      "content-type": "text/html; charset=utf-8",
      "content-security-policy":
        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action https:",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff"
    }
  });
}

