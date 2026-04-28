# Merge Video

샤오미 홍미10에서 동작하는 간단한 안드로이드 앱. `DCIM/Camera` 폴더의 mp4 파일을 나열해 사용자가 선택한 순서대로 한 개의 mp4로 합쳐 같은 폴더에 저장한다.

## 동작

1. 앱을 실행하면 동영상 권한을 요청한다 (`READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`).
2. `DCIM/Camera` 안의 mp4 파일이 최신 순으로 리스트에 표시된다.
3. 행을 탭하면 선택 토글. 선택된 항목에는 **선택 순서 번호**(1, 2, 3…)가 배지로 표시된다.
4. 2개 이상 선택하면 하단 **병합 (n)** 버튼이 활성화된다.
5. 병합 결과는 `DCIM/Camera/merged_yyyyMMdd_HHmmss.mp4`로 저장되고 갤러리에 즉시 노출된다.

같은 카메라로 녹화된 mp4(동일 코덱·해상도·프레임레이트)를 가정하므로 **재인코딩 없이** `MediaExtractor` + `MediaMuxer`로 빠르게 처리한다. 형식이 다른 파일이 섞이면 스낵바에 에러 메시지가 표시된다.

## 기술 스택

- Kotlin 1.9.24, Jetpack Compose (Material 3)
- AGP 8.5.2, Gradle 8.7
- `minSdk = 24`, `targetSdk = 34`
- 비디오 처리: 표준 안드로이드 API(`MediaExtractor` + `MediaMuxer`) — FFmpeg 등 외부 의존성 없음
- 파일 접근: `MediaStore` (Scoped Storage)

## 빌드 / 설치

`local.properties`에 SDK 경로 설정 후:

```bash
./gradlew assembleDebug   # APK 생성: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug    # USB 연결된 기기에 바로 설치
```

## 구조

```
app/src/main/kotlin/com/example/mergevideo/
├── MainActivity.kt              # Compose 진입점, 권한 분기
├── ui/
│   ├── VideoListScreen.kt       # 리스트 + 선택 배지 + Merge 버튼
│   └── VideoListViewModel.kt    # UiState, 선택 순서 보존, 병합 트리거
├── data/
│   ├── VideoRepository.kt       # MediaStore 쿼리 (DCIM/Camera 필터)
│   ├── MergedVideoSink.kt       # 출력 파일 생성/commit/abort
│   └── VideoItem.kt
└── merge/
    └── VideoMerger.kt           # MediaExtractor + MediaMuxer 무재인코딩 병합
```

자세한 아키텍처 설명은 [`CLAUDE.md`](CLAUDE.md)를 참고.

## 라이선스

미정.
