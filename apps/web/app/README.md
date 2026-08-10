# Next.js App Routes

이 디렉터리는 App Router의 사용자 화면과 same-origin API route를 소유한다.

- `page.tsx`: camera/sensor/detection/navigation/report hook을 조립하는 보행 보조 화면
- `admin/page.tsx`: backend 신고 운영 화면
- `_walksafe/`: route가 아닌 내부 feature 모듈
- `api/`: backend proxy와 명시적으로 gated된 test capture endpoint
- `layout.tsx`, `globals.css`: 전역 metadata, 접근성 viewport와 공통 스타일

`page.tsx`에서 도메인 정책을 새로 만들기보다 `_walksafe` 또는 `lib`의 기존 정책을 조합한다. API secret은 `NEXT_PUBLIC_*` 값이나 client component에 넣지 않는다.
