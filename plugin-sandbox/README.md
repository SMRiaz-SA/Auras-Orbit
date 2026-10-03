# CloudStream Plugin Sandbox

This module is a compatibility analyzer for CloudStream plugins (`.cs3` files). It scans and exercises plugins with the app's class-loading rules; it is not an operating-system security sandbox or a guarantee that a plugin is safe. Only analyze plugins you trust.

Because Cloudstream plugins are built for Android, they often try to call Android-specific APIs (like `android.widget.Toast` or `android.util.Base64`). The Sandbox analyzes these plugins and runs them to tell you exactly which Android APIs are missing, which ones are safely faked (stubbed), and which ones are properly implemented for the Desktop port.

## 🌐 1. Fetching Plugins from a Repository

If you want to test an entire repository of plugins without downloading them by hand, we have built an automated fetcher!

From the repository root, run this PowerShell command with a CloudStream repository JSON URL:
```powershell
.\gradlew.bat :sandbox:runFetcher --args="https://raw.githubusercontent.com/recloudstream/cloudstream-extensions/master/plugins.json"
```

The script will:
1. Parse the repository JSON.
2. Follow the plugin list URLs.
3. Automatically create a folder named after the repository (e.g. `test_plugins/cs-karma/`).
4. Download every single `.cs3` file directly into that subfolder.

*(You can also just manually download `.cs3` files from the internet and drop them directly into `plugin-sandbox/test_plugins/` yourself!)*

## 🚀 2. How to Run the Analyzer

Once you have a `.cs3` file downloaded, run the following command and point it to the file you want to test.

**Example Usage:**
If you want to test `SoraStream.cs3` inside the `test_plugins` folder, run:
```powershell
.\gradlew.bat :sandbox:run --args="plugin-sandbox/test_plugins/SoraStream.cs3"
```

## 📊 What It Does
1. **Security Scan**: Runs the `PluginSecurityVerifier` using ASM Bytecode Analysis to check if the plugin tries to execute malicious code (like `java.lang.Runtime` or `java.io.File`).
2. **Stub Radar**: Uses ASM Bytecode analysis to scan the plugin and print a dashboard of all Android APIs the plugin relies on, categorizing them into:
   - ✅ **Implemented Core APIs**: Safe to use, fully implemented data logic.
   - ⚠️ **Stubbed UI APIs**: Safe to use, faked out so the app doesn't crash on UI calls.
   - ❌ **Missing APIs**: Danger! These need to be added to the `:android-stubs` module.
3. **Compatibility execution**

Once the static scans finish, the analyzer loads the plugin through a custom `SafePluginClassLoader`, invokes its `load()` method, and attempts basic `getMainPage()` and `search()` requests. This class loader does not isolate operating-system access. The analyzer saves a `.txt` report under its reports directory.

---

## 🏗️ Core vs Stub APIs (How it Works)

The analyzer scans plugin bytecode for Android API references and checks the `:android-stubs` module to categorize them.

**How do we decide what gets stubbed vs implemented?**

Desktop compatibility APIs are implemented in `android-stubs/src/main/java/android/...`.

### 1. Dummy Stubs (`@Stub`)
If the API is purely related to the Android User Interface or OS visual elements (e.g., `Toast`, `ScrollView`, `ProgressBar`, `Activity`, `WindowInsets`), we write an empty dummy file and annotate it with `@Stub`.
- **Why?** On Desktop, we build our UI using Compose, not Android Views. The plugin might import these because it expects an Android phone, but on a PC, we just need to prevent the Java Virtual Machine from throwing a `ClassNotFoundException` crash. A stub does absolutely nothing except prevent the crash.

### 2. Core Implementations (`@Implemented`)
If the API processes critical data that the scraper relies on (e.g., `android.util.Base64`, `android.net.Uri`, `android.util.Log`, `android.content.SharedPreferences`), we write real, functional code for it and annotate it with `@Implemented`.
- **Why?** If a plugin uses `Base64` to decrypt a hidden video URL, returning `null` from an empty stub would break the scraper entirely. We must write actual Java/Kotlin logic that perfectly mimics what the Android OS would do.

**The script tells you *what* is missing, but you (the developer) must decide *how* it should be implemented based on this golden rule!**
