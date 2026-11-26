# Build FarDriver Control APK using GitHub Actions

This is a **source project**, not an APK. GitHub Actions will attempt to build an unsigned-for-release, debug-signed APK. Compilation has not been verified.

1. On GitHub, create a **new private repository** (e.g. `FarDriverControl`). Do not select an initial README.
2. Choose **Add file > Upload files**. Upload the **contents** of this ZIP after extracting it, including the hidden `.github` folder. GitHub's web upload accepts files, not a ZIP that it will unpack automatically. The root of the repository must contain `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/`, and `.github/workflows/build-apk.yml`.
3. Commit files to the `main` branch.
4. Open **Actions > Build Android debug APK > Run workflow**, or wait for the push-triggered run.
5. If the build succeeds, open the run and download the **FarDriverControl-debug-apk** artifact. Unzip that artifact to obtain `app-debug.apk`.
6. Install on a personal Android device if you trust the source. Android may require allowing installation from the browser/files app. Do not test while riding.

If the workflow fails, open the failed step and copy the compiler error text to Copilot so it can be corrected.

**Limitations:** read-only BLE prototype, no verified telemetry, no persistent logs, no parameter writes. Slider ranges are placeholders. Never use this prototype to make safety-critical decisions.
