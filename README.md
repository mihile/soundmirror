# SoundMirror

Windows 시스템 오디오를 같은 Wi-Fi의 Android 기기로 저지연 전송하는
오픈소스 프로젝트입니다. 물리 마이크 입력은 사용하지 않고 WASAPI loopback
출력만 캡처합니다.

## 기능

- UDP 자동 검색 및 직접 IP 연결
- Opus 128/160 kbps, FLAC 무손실, PCM16
- 패킷 버퍼 대기량 조절
- 총지연, 지터, 유실률, 오디오 수신률 표시
- Android 저지연 출력과 백그라운드 재생
- Windows 트레이 실행 및 시작 프로그램 등록

## 빌드

Android:

```powershell
.\gradlew.bat assembleRelease
```

공개 배포용 서명은 다음 환경변수로 주입합니다.

- `SOUNDMIRROR_KEYSTORE_PATH`
- `SOUNDMIRROR_KEYSTORE_PASSWORD`
- `SOUNDMIRROR_KEY_ALIAS`
- `SOUNDMIRROR_KEY_PASSWORD`

로컬 기기 테스트에만 debug 서명을 쓰려면
`.\gradlew.bat assembleRelease -PallowDebugSigning=true`를 사용합니다.

PC sender:

```powershell
cd pc_sender_rust
cargo build --release
```

Android 빌드에는 Android SDK/NDK와 CMake가 필요합니다. PC sender는 Windows
환경과 Rust MSVC toolchain이 필요합니다.

## 배포

GitHub Release에 APK를 배포할 때는 개인 release keystore로 서명해야 합니다.
저장소의 빌드 결과물과 로컬 테스트 파일은 `.gitignore`로 제외됩니다.

## 라이선스

프로젝트 코드는 MIT License로 제공됩니다. 누구나 사용, 수정, 재배포할 수
있습니다. 포함된 제3자 구성요소는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를
확인하세요.
