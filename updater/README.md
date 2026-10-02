# Verified in-app updater

`UpdateController(activity, "phone" | "tv")`: call `check()` from a user action, `onResume()` from the Activity, and `close()` from `onDestroy()`.

The only metadata endpoint is `https://raw.githubusercontent.com/amateurish-programmer/lan-cast/updates/manifest.json`. Schema:

```json
{"schemaVersion":1,"versionName":"0.2.0","versionCode":2,"notes":"Release notes","artifacts":{"phone":{"packageName":"dev.lancast.phone","url":"https://raw.githubusercontent.com/amateurish-programmer/lan-cast/<40-lowercase-hex-commit>/apks/v0.2.0/phone.apk","sha256":"<64-hex-digest>","size":123},"tv":{"packageName":"dev.lancast.tv","url":"https://raw.githubusercontent.com/amateurish-programmer/lan-cast/<40-lowercase-hex-commit>/apks/v0.2.0/tv.apk","sha256":"<64-hex-digest>","size":123}}}
```

The metadata branch may advance, but APK URLs must pin immutable commits. No GitHub credentials, release API, browser, or public signing-key upload is required. Publish APK blobs first, then atomically update the manifest to their commit. APK versionCode and versionName must exactly match metadata. Keep the same signing key as the installed app. A public debug key is not appropriate for production authenticity; this implementation compares the actual installed certificate and does not generate or publish keys.

Downloads are limited to 100 MiB, metadata to 1 MiB. Only the exact repository metadata path and immutable APK paths are permitted, without ports, credentials, query strings, or redirects. SHA-256, application ID, strictly increasing versionCode, exact manifest version, device minimum SDK, and exact current signer set are checked before offering installation and again just before starting Android's system package installer. Signer rotation is intentionally not supported. The Android installer remains the final authority on APK validity and platform policy.

An explicit in-app confirmation precedes the system installer confirmation. Missing unknown-source permission opens the device's settings only after a prompt. A verified cache file and expected digest/version survive Activity/process recreation during that settings trip; returning re-verifies and asks again. If cache was removed or modified, installation is blocked. No successful-install claim is made when merely launching the system installer. Cancellation/Activity destruction cancels work and disconnects network IO; partial downloads are deleted. Cold-process orphan files older than a day are cleaned up.

Unit coverage checks manifest parsing and security policy. Device acceptance still needs real phone/TV tests for download cancellation, permission denial/grant, recreation during permission settings, system installer cancellation/completion, unsupported installers/device policy, and successful same-key upgrade. Android API action `ACTION_INSTALL_PACKAGE` is supported but deprecated; it deliberately delegates the install transaction and final approval to the platform installer.
