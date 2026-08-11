# jvm-sdk

Official Kotlin/Java SDK for [Pickpoint](https://pickpoint.io) — a geolocation platform with four APIs under one key:

| API | What it does |
|-----|----------------|
| **Geocoding** | Address ↔ coordinates (forward, reverse, place lookup) |
| **Address search** | Typeahead / autocomplete for address inputs |
| **Routing** | Routes, matrices, optimized multi-stop, elevation |
| **Device tracking** | Register devices over HTTP; stream live GPS over WebSocket |

Built for maps, delivery, logistics, and anything that needs places, routes, or live location. Docs: [pickpoint.io/docs](https://pickpoint.io/docs).

**This artifact** targets JVM 17+ (servers and Android via OkHttp):

| Package | Role |
|---------|------|
| `io.pickpoint` | HTTP: geocode, search, routing, devices, mint |
| `io.pickpoint.tracking` | Realtime tracks (WebSocket, `tracking.v2.proto`) |
| `io.pickpoint.tracking.v2` | Generated protobuf messages |

Apache-2.0. Siblings: [go-sdk](https://github.com/pickpoint/go-sdk), [python-sdk](https://github.com/pickpoint/python-sdk), [@pickpoint/sdk](https://github.com/pickpoint/pickpoint-js). Wire schema: [pickpoint-proto](https://github.com/pickpoint/pickpoint-proto).

```kotlin
// Gradle
implementation("io.pickpoint:pickpoint:2.0.0")
```

Requires: JDK 17+, OkHttp (pulled transitively). Works on Android when the app `minSdk` can run OkHttp 4 / your chosen desugaring.

---

## Public API — `io.pickpoint`

One `Client`, one auth session, whole public HTTP surface (same idea as Go / JS).

### Kotlin

```kotlin
import io.pickpoint.Client
import io.pickpoint.Config

val pp = Client(Config(apiKey = System.getenv("PICKPOINT_API_KEY")))

val places = pp.forward(mapOf("q" to "Berlin", "limit" to "5"))
val rev = pp.reverse(mapOf("lat" to "52.52", "lon" to "13.405"))
val search = pp.search(mapOf("q" to "Alexanderplatz"))
val route = pp.route(
    mapOf(
        "locations" to listOf(
            mapOf("lat" to 52.52, "lon" to 13.40),
            mapOf("lat" to 52.53, "lon" to 13.42),
        ),
        "costing" to "auto",
    ),
)
val devices = pp.devices.list()
```

### Java

```java
import io.pickpoint.Client;
import io.pickpoint.Config;

var pp = Client.create(Config.builder()
    .apiKey(System.getenv("PICKPOINT_API_KEY"))
    .build());

var places = pp.forward(Map.of("q", "Berlin", "limit", "5"));
var list = pp.getDevices().list();
```

Auth: exactly one of `apiKey` / `clientAuth` / `accessToken`. Client-auth refreshes at ~50% TTL and once on HTTP 401.

| Method | HTTP |
|--------|------|
| `forward` / `reverse` / `lookup` (+ batch) | geocode / address lookup |
| `search` | `GET /v2/address/search` |
| `route` / `optimizedRoute` / `matrix` / `locate` / `elevation` | routing |
| `devices.*` | `/v2/devices` |
| `mintClientTokens` | `POST /v2/client-tokens` (server-side; needs secret API key) |

Geocoding soft-fails non-auth 4xx to empty/`null` (batch-friendly). Address / routing / devices throw `APIException`.

---

## Tracking — `io.pickpoint.tracking`

```kotlin
import io.pickpoint.tracking.Config
import io.pickpoint.tracking.DeviceAuth
import io.pickpoint.tracking.connectBlocking
import io.pickpoint.tracking.latLng

val session = connectBlocking(
    Config(
        endpoint = "wss://tracking.pickpoint.io",
        device = DeviceAuth(clientId = deviceUid, clientSecret = deviceSecret),
    ),
)

val trackUid = session.startTrackBlocking(latLng(52.52, 13.405))
session.publishBlocking(latLng(52.521, 13.406))
session.stopTrackBlocking()
session.closeBlocking()
```

Suspend APIs (`connect`, `startTrack`, `publish`, …) are available for coroutine-based apps. Default transport is binary WebSocket with subprotocol `tracking.v2.proto`.

---

## Develop

```bash
./gradlew test
./gradlew publishToMavenLocal
```

### Publish to Maven Central

**Local** — credentials/GPG in `~/.gradle/gradle.properties` (never commit):

```properties
mavenCentralUsername=<token username from central.sonatype.com>
mavenCentralPassword=<token password>
signingInMemoryKey=<ascii-armored secret key>
signingInMemoryKeyId=<last 8 of key id>
signingInMemoryKeyPassword=<gpg passphrase>
```

```bash
./gradlew publishToMavenCentral
```

**GitHub Actions** — push to `main` auto-bumps patch in `VERSION`, tags `vX.Y.Z`, publishes to Maven Central, and creates a GitHub Release. Manual tag `v*` also publishes (skips if already on Central).

Repo secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|--------|--------|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `SIGNING_KEY` | **Prefer base64** of the armored private key (single line). Example: `gpg --export-secret-keys --armor 03AB60E58869B5C7 \| base64 \| pbcopy`. Also accepts raw armored text. |
| `SIGNING_KEY_ID` | `8869B5C7` or long id `03AB60E58869B5C7` |
| `SIGNING_PASSWORD` | GPG key passphrase (same as `signingInMemoryKeyPassword`) |

Skip auto-release on a commit: include `[skip release]` in the message. For a minor/major bump, edit `VERSION` in a PR with `[skip release]`, merge, then tag/push or let the next main push continue from there.

Artifact: `io.pickpoint:pickpoint` (version from `VERSION`).

Proto stubs are generated from `src/main/proto/tracking/v2/messages.proto` (synced from `pickpoint-proto`).
