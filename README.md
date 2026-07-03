# mideakt

[![build](https://github.com/hafaio/mideakt/actions/workflows/build.yml/badge.svg)](https://github.com/hafaio/mideakt/actions/workflows/build.yml)
[![jitpack](https://jitpack.io/v/hafaio/mideakt.svg)](https://jitpack.io/#hafaio/mideakt)

Native Kotlin library for **local** control of Midea (and rebranded) WiFi air
conditioners over the LAN — no cloud after a one-time key fetch.
This is a Kotlin port of the [msmart-ng](https://github.com/mill1000/midea-msmart).

Pure JVM (`java.net` sockets, `javax.crypto`), so it runs on plain JVM and on
Android (see [Android](#android)). The only dependency is
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization), used to
parse the cloud's JSON.

## Features

- **Discovery** of devices on the LAN (UDP broadcast).
- **Cloud key fetch** (NetHome Plus) to obtain a device's token/key once.
- **Setup**: discover → fetch key → verify → credentials.
- **Local control**: power, mode, temperature, fan, eco/turbo/swing, display
  toggle; reads full state. Persistent connection.

Calls are **blocking** — run them off the main thread (e.g. `Dispatchers.IO`).

## Installation

Available from [JitPack](https://jitpack.io/#hafaio/mideakt). Add the repository,
then the dependency:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.hafaio:mideakt:0.1.0")
}
```

<details>
<summary>Maven</summary>

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.hafaio</groupId>
  <artifactId>mideakt</artifactId>
  <version>0.1.0</version>
</dependency>
```
</details>

## Usage

```kotlin
// One-time setup (touches the cloud once). Uses a shared community account for
// the region by default; pass your own NetHome Plus account for reliability:
val credentials = Setup.run().first()                       // Region.US default
// val credentials = Setup.run(Region.DE).first()           // another region
// val credentials = Setup.run("you@example.com", "pw").first()  // your account

// All local from here — store the credentials and reuse:
val client = MideaClient(credentials)
val state = client.refresh()
println("${state.targetTemperature} ${OperationalMode.fromRaw(state.mode)}")

client.update { set ->
    set.powerOn = true
    set.targetTemperature = 22.0
    set.mode = OperationalMode.COOL.raw
}
```

### Manual setup

`Setup.run` is the high-level path. The same steps are exposed individually if you
want to drive setup yourself — discover, fetch the key from the cloud, then connect:

```kotlin
val cloud = NetHomePlusCloud("you@example.com", "pw")  // or NetHomePlusCloud.forRegion()
cloud.login()

val device = Discovery.discover().first()              // a DiscoveredDevice on the LAN

// The cloud keys the token on the udpid; the byte order isn't discoverable, so try
// both and use whichever the cloud answers (see "Design notes").
val (token, key) = listOf(false, true).firstNotNullOf { bigEndian ->
    runCatching { cloud.getToken(UDPID.compute(device.id, bigEndian)) }.getOrNull()
}

val client = MideaClient(
    DeviceCredentials(device.name, device.id, device.ip, device.port, device.version, token, key),
)
println(OperationalMode.fromRaw(client.refresh().mode))
```

## Android

Requires **minSdk 26** (Android 8.0): the protocol timestamp uses `java.time`,
which on older API levels needs
[core-library desugaring](https://developer.android.com/studio/write/java8-support-table).
API 26 covers essentially all active devices, so this is rarely a real
constraint. Receiving UDP discovery replies may also require a
`WifiManager.MulticastLock` on some devices.

## Design notes

### Post-authentication warm-up

A freshly authenticated device drops or ignores queries sent in the first moment
after the handshake. We send one throwaway `getState` probe and proceed the
instant its reply begins arriving, bounded by a ~1.2s ceiling — a fast unit
answers in a fraction of a second. `getState` is idempotent, so the probe is
harmless.

If a device answers *slower* than the ceiling, its probe reply is left queued;
before every call we drain any such buffered frames, so a slow probe reply is
discarded rather than mistaken for the next call's response. The only unclosable
residual is a probe reply still in flight during that drain — the protocol has no
request/reply correlation id to fully close it — but the next call self-corrects.

### Concurrency

`MideaClient` owns one stateful connection, so interleaving calls would corrupt
the stream. It deliberately does not serialize internally: a blocking lock would
block a thread mid-round-trip (wrong for coroutine callers), and a suspending
`Mutex` would pull in a coroutines dependency. Instead it fails fast (an
`AtomicBoolean` guard that throws on concurrent entry); correct callers that
already serialize never trip it.

### Token endianness

The cloud stores a device's token/key under a *udpid* derived from its device id.
The official app computed that udpid using a particular byte order of the id when
it registered the device, and that order varies across firmware/app versions.
Nothing in the device's discovery reply or the cloud API reports which order was
used, so it can't be computed or detected locally — the only signal is the cloud
itself. So setup computes both candidates and calls `getToken` for each; whichever
returns a token is correct. Hence `Setup` (and the manual example) try
little-endian, then big-endian.

### Device ids and JSON

Device ids arrive as raw bytes from UDP discovery (6 bytes, ≤ 2^48), never from
JSON, and the cloud JSON carries only strings — so the JSON number representation
never affects them.

### Cross-validation

`src/test/resources/vectors.json` was generated from the canonical Python
reference ([msmart-ng](https://github.com/mill1000/midea-msmart)); the tests assert
mideakt's framing, CRC, command encodings, and state parsing match it.
