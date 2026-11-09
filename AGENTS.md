# Herald Android client

Native Android client for Hermes Agent, built with Kotlin Multiplatform and Compose. See README.md for features and setup and CONTRIBUTING.md for development conventions.

## Layout
- `shared/core`: protocol, authentication, networking and repositories; no UI.
- `shared/designsystem`: design tokens and Compose Unstyled components.
- `shared/ui`: common screens and view models.
- `androidApp`: Android host, notifications and manifest.
- `wearApp` and `shared/wearlink`: Wear OS companion and phone/watch link.
- Gateway protocol: `docs/hermes-protocol-research.md`; feature status: `docs/ROADMAP.md`.

## Validation
- Requires JDK 17+ and Android SDK API 37.
- Build: `./gradlew :androidApp:assembleDebug`.
- Phone checks: `./gradlew --continue :shared:core:testAndroidHostTest :shared:designsystem:testAndroidHostTest :shared:ui:testAndroidHostTest :androidApp:assembleDebug :androidApp:lintDebug`. Full CI also validates Wear OS; see `.github/workflows/ci.yml` for the current command.
- Behaviour changes need relevant tests under `shared/core/src/commonTest` or `shared/ui/src/commonTest`.
- Validate visible changes on a device, including 360 dp widths and both themes. Do not add new lint issues to the baseline.
- Windows host-test classpath failures mentioning `Files` can result from stray quote characters in PATH.

## Conventions
- Follow nearby Kotlin conventions; keep changes focused. Commit subjects use `Area: what changed`.
- Verify gateway calls against protocol documentation and upstream source before adding them.
- Use synthetic preview data for screenshots, never real chats or gateway addresses. See `HeraldPreviews.kt` and the debug `PreviewGalleryActivity` instructions in README.md.
