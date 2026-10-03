> **Auras Orbit source note:** This describes source sets in the upstream CloudStream reference tree. The Auras Orbit product currently builds as a Windows desktop application; see the [repository README](../../../README.md) for its supported platform and build instructions.

https://kotlinlang.org/docs/multiplatform/compose-multiplatform-create-first-app.html#examine-the-project-structure

`jvmMain` contains source files for the desktop target, which uses Kotlin/JVM.

`androidMain` contains Android source files and targets Kotlin/JVM.

`iosMain` contains Kotlin code for iOS and targets Kotlin/Native.

`jsMain` contains JavaScript-specific Kotlin code and targets Kotlin/JS.

`wasmJsMain` contains Wasm-specific Kotlin code and targets Kotlin/Wasm.
