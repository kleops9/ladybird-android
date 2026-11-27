# Ladybird for Android

An unofficial Android port of [Ladybird](https://ladybird.org), the truly independent web browser that uses a novel
engine based on web standards.

> [!IMPORTANT]
> This port is made outside the Ladybird project, which doesn't support it. Please report problems with it
> [here](https://github.com/kleops9/ladybird-android/issues), not to the Ladybird project. Like Ladybird itself, it's
> alpha software: expect sites that don't work, and crashes.

## Trying it

Download the APK of the [latest release](https://github.com/kleops9/ladybird-android/releases) on your phone, and open
it to install it. Android asks once to allow installing apps from your browser or file manager. It needs a 64-bit ARM
phone with Android 11 or later. New releases install over the old one.

The [changelog](UI/Android/CHANGELOG.md) lists what each release brings and what doesn't work yet.

## What's here

- `android`: the port, on top of the upstream engine. Releases are tagged `v0.x.y`.
- `master`: a mirror of [upstream Ladybird](https://github.com/LadybirdBrowser/ladybird).

The Android app is in [UI/Android](UI/Android), and the few changes to the engine are commits of their own on
`android`. [Documentation/BuildInstructionsAndroid.md](Documentation/BuildInstructionsAndroid.md) explains how to build
it, how it works, and how releases are made. Ladybird and this port are under the 2-clause BSD license, in
[LICENSE](LICENSE).

---

*The rest of this file is upstream Ladybird's README.*

# Ladybird

[Ladybird](https://ladybird.org) is a truly independent web browser, using a novel engine based on web standards.

> [!IMPORTANT]
> Ladybird is in a pre-alpha state, and only suitable for use by developers
>

## Features

We aim to build a complete, usable browser for the modern web.

Ladybird uses a [multi-process architecture](Documentation/ProcessArchitecture.md) with a main UI process,
WebContent renderer processes, and helper processes for tasks such as networking, image decoding, compositing and media.

Documents from different sites run in separate renderer processes, each sandboxed from the rest of the system.
Image decoding and network connections are done out of process, separately for each site, to be more robust against
malicious content.

At the moment, many core library support components are inherited from SerenityOS:

- LibWeb: Web rendering engine
- LibJS: JavaScript engine
- LibWasm: WebAssembly implementation
- LibCrypto/LibTLS: Cryptography primitives and Transport Layer Security
- LibHTTP: HTTP/1.1 client
- LibGfx: 2D Graphics Library, Image Decoding and Rendering
- LibUnicode: Unicode and locale support
- LibMedia: Audio and video playback
- LibCore: Event loop, OS abstraction layer
- LibIPC: Inter-process communication

## How do I build and run this?

See [build instructions](Documentation/BuildInstructionsLadybird.md) for information on how to build Ladybird.

Ladybird runs on Linux, macOS, Windows (with WSL2), and many other \*Nixes.

## How do I read the documentation?

Code-related documentation can be found in the [documentation](Documentation/) folder.

## Get in touch and participate!

Join [our Discord server](https://discord.gg/nvfjVJ4Svh) to participate in issue and development discussions.

Please read [Getting involved with Ladybird](Documentation/GettingStartedContributing.md) if you're new to Ladybird and want to help.

Before opening an issue, please see the [issue policy](CONTRIBUTING.md#issue-policy) and the [detailed issue-reporting guidelines](ISSUES.md).

The project participation guidelines can be found in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

Ladybird is licensed under a 2-clause BSD license.
