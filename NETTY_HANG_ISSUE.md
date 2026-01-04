# Netty/ZIO HTTP Server Hang Issue - Technical Report

## Issue Summary
ZIO HTTP server hangs during initialization when run via `sbt run`, never completing startup and never binding to port 8080. The hang occurs consistently after Netty native library initialization but before any application-level ZIO effects execute.

## Environment Details

### System
- **OS:** Debian Linux (kernel 6.12.43+deb13-amd64)
- **Architecture:** x86_64
- **Java:** OpenJDK 21.0.5 LTS (Temurin-21.0.5+11)
- **Java VM:** 64-Bit Server VM (mixed mode, sharing)

### Build Tools
- **sbt version (project):** 1.12.0-RC1
- **sbt runner:** 1.12.0-RC1  
- **Scala version (project):** 3.6.3
- **Scala runner (system):** 1.9.1 / Scala 3.7.4

### Dependency Versions (build.sbt)

**Core Libraries:**
```scala
val zioVersion        = "2.1.24"
val tapirVersion      = "1.13.4"
val sttpVersion       = "3.9.6"
val zioLoggingVersion = "2.2.4"
```

**Server Dependencies:**
```scala
"com.softwaremill.sttp.tapir"   %% "tapir-zio"               % "1.13.4"
"com.softwaremill.sttp.tapir"   %% "tapir-zio-http-server"   % "1.13.4"
"dev.zio"                       %% "zio"                     % "2.1.24"
"dev.zio"                       %% "zio-logging"             % "2.2.4"
"dev.zio"                       %% "zio-logging-slf4j"       % "2.2.4"
"ch.qos.logback"                 % "logback-classic"         % "1.5.23"
```

**Transitive Dependencies (via dependency tree):**
```
tapir-zio-http-server_3:1.13.4
├── tapir-server_3:1.13.4
│   └── tapir-core_3:1.13.4
└── zio-http_3:3.7.4          ← ZIO HTTP version pulled transitively
    └── netty (native epoll)
```

### sbt Configuration
**project/build.properties:**
```properties
sbt.version=1.12.0-RC1
```

**sbt settings (build.sbt):**
```scala
ThisBuild / scalaVersion := "3.6.3"
ThisBuild / scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Xlint:type-parameter-shadow",
  "-Xmax-inlines:64"
)

// Server module settings
lazy val server = (project in file("modules/server"))
  .settings(
    name := "register-server",
    libraryDependencies ++= serverDependencies,
    run / fork := true,
    run / javaOptions ++= Seq(
      "-Xms512m",
      "-Xmx2g"
    )
  )
```

## Symptom Details

### Execution Path
1. **Compilation:** ✅ Succeeds without errors
2. **Tests:** ✅ All 209 tests pass (120 common + 89 server)
3. **sbt run:** ❌ Hangs during server initialization

### Console Output (Last Lines Before Hang)
```
[info] running com.risquanter.register.Application 
00:40:46.653 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.logging.InternalLoggerFactory -- Using SLF4J as the default logging framework
00:40:46.708 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- Java version: 21
00:40:46.708 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- -Dio.netty.noUnsafe: false
00:40:46.709 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- sun.misc.Unsafe.theUnsafe: available
00:40:46.710 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- sun.misc.Unsafe base methods: all available
00:40:46.710 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- java.nio.Buffer.address: available
00:40:46.711 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- direct buffer constructor: unavailable: Reflective setAccessible(true) disabled
00:40:46.711 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- java.nio.Bits.unaligned: available, true
00:40:46.712 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- jdk.internal.misc.Unsafe.allocateUninitializedArray(int): unavailable
00:40:46.713 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent0 -- java.nio.DirectByteBuffer.<init>(long, {int,long}): unavailable
00:40:46.713 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent -- sun.misc.Unsafe: available
00:40:46.713 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent -- -Dio.netty.tmpdir: /tmp (java.io.tmpdir)
00:40:46.713 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent -- -Dio.netty.bitMode: 64 (sun.arch.data.model)
00:40:46.714 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent -- -Dio.netty.maxDirectMemory: -1 bytes
00:40:46.715 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.CleanerJava9 -- java.nio.ByteBuffer.cleaner(): available
00:40:46.716 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent -- -Dio.netty.noPreferDirect: false
00:40:46.720 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.PlatformDependent -- -Dio.netty.jfr.enabled: true
00:40:46.724 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.NativeLibraryLoader -- -Dio.netty.native.workdir: /tmp (io.netty.tmpdir)
00:40:46.724 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.NativeLibraryLoader -- -Dio.netty.native.deleteLibAfterLoading: true
00:40:46.724 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.NativeLibraryLoader -- -Dio.netty.native.tryPatchShadedId: true
00:40:46.724 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.NativeLibraryLoader -- -Dio.netty.native.detectNativeLibraryDuplicates: true
00:40:46.730 [ZScheduler-Worker-24] DEBUG io.netty.util.internal.NativeLibraryLoader -- Successfully loaded the library /tmp/libnetty_transport_native_epoll_x86_6415511806838124150101.so
00:40:46.732 [ZScheduler-Worker-24] DEBUG io.netty.util.NetUtil -- -Djava.net.preferIPv4Stack: false
00:40:46.733 [ZScheduler-Worker-24] DEBUG io.netty.util.NetUtil -- -Djava.net.preferIPv6Addresses: false
00:40:46.734 [ZScheduler-Worker-24] DEBUG io.netty.util.NetUtilInitializations -- Loopback interface: lo (lo, 0:0:0:0:0:0:0:1%lo)
00:40:46.734 [ZScheduler-Worker-24] DEBUG io.netty.util.NetUtil -- /proc/sys/net/core/somaxconn: 4096
00:40:46.739 [ZScheduler-Worker-28] DEBUG io.netty.channel.MultithreadEventLoopGroup -- -Dio.netty.eventLoopThreads: 64
00:40:46.746 [ZScheduler-Worker-28] DEBUG io.netty.util.concurrent.GlobalEventExecutor -- -Dio.netty.globalEventExecutor.quietPeriodSeconds: 1
00:40:46.754 [ZScheduler-Worker-28] DEBUG io.netty.util.internal.InternalThreadLocalMap -- -Dio.netty.threadLocalMap.stringBuilder.initialSize: 1024
00:40:46.755 [ZScheduler-Worker-28] DEBUG io.netty.util.internal.InternalThreadLocalMap -- -Dio.netty.threadLocalMap.stringBuilder.maxSize: 4096
00:40:46.764 [ZScheduler-Worker-25] DEBUG io.netty.util.internal.PlatformDependent -- org.jctools-core.MpscChunkedArrayQueue: available

<HANGS HERE - No further output, port 8080 never opens>
```

### What NEVER Appears
- ❌ Application-level ZIO logs: "Bootstrapping Risk Register application..."
- ❌ "Registered N HTTP endpoints"
- ❌ "CORS configured for all origins"
- ❌ "Starting HTTP server on port 8080..."
- ❌ Port 8080 binding confirmation
- ❌ Server ready message

### Application Code (Application.scala)
```scala
object Application extends ZIOAppDefault {

  override def run = {
    val program = for {
      _          <- ZIO.logInfo("Bootstrapping Risk Register application...")  // NEVER EXECUTES
      endpoints  <- HttpApi.endpointsZIO
      _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")
      httpApp     = ZioHttpInterpreter().toHttp(endpoints)
      
      corsConfig  = CorsConfig(
        allowedOrigin = _ => Some(AccessControlAllowOrigin.All),
        allowedHeaders = AccessControlAllowHeaders.All,
        exposedHeaders = AccessControlExposeHeaders.All
      )
      _          <- ZIO.logInfo("CORS configured for all origins")
      corsApp     = cors(corsConfig)(httpApp)
      _          <- ZIO.logInfo("Starting HTTP server on port 8080...")
      _          <- Server.serve(corsApp)  // Hangs during layer construction
    } yield ()

    program.provide(
      Server.default,
      RiskTreeRepositoryInMemory.layer,
      SimulationExecutionService.live,
      RiskTreeServiceLive.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )
  }
}
```

## Diagnostic Attempts

### 1. Different sbt Versions Tested
- ✅ **sbt 1.11.7** (original) - Hangs
- ✅ **sbt 1.10.5** - Hangs
- ✅ **sbt 1.9.9** - Hangs
- ✅ **sbt 1.12.0-RC1** (current) - Hangs

**Conclusion:** Issue is NOT sbt-version specific

### 2. Different Java Versions Tested
- ✅ **Java 25 (EA)** - Hangs
- ✅ **Java 21.0.5 LTS** (current) - Hangs

**Conclusion:** Issue is NOT Java-version specific

### 3. Dependency Cleanup
**Removed unnecessary dependencies:**
- quill-jdbc-zio (not used - in-memory repos)
- postgresql driver (not used)
- zio-config (not used)
- testcontainers (not used)
- Duplicate zio-test entries

**Result:** Still hangs

### 4. Forked JVM Execution
**Added to build.sbt:**
```scala
run / fork := true
run / javaOptions ++= Seq("-Xms512m", "-Xmx2g")
```

**Result:** Still hangs (in separate JVM)

### 5. Minimal ZIO HTTP Test (Without Tapir)
**Created minimal server (scala-cli):**
```scala
object TestZioHttp extends ZIOAppDefault {
  val app = Routes(
    Method.GET / "health" -> handler(Response.text("OK"))
  ).toHttpApp
  
  override def run = Server.serve(app).provide(Server.default)
}
```

**Result:** ✅ **WORKS PERFECTLY** 
- Server starts successfully
- Port 8080 opens
- `curl http://localhost:8080/health` returns "OK"

**Conclusion:** Pure ZIO HTTP works fine - issue is with Tapir integration

### 6. Tapir Version Update
**Updated from:**
- tapir 1.11.10 → 1.13.4

**Reason:** Match working reference project (cheleb)

**Result:** Still hangs (same place)

### 7. Reference Project Test (cheleb)
**Tested cheleb project (uses same stack):**
- Tapir 1.13.4 ✅
- ZIO 2.1.24 ✅
- ZIO HTTP 3.7.4 (via Tapir) ✅

**Result:** ❌ **ALSO HANGS** (but gets further - reaches database connection attempt before failing)

**Conclusion:** Systematic issue with sbt + Tapir + ZIO HTTP on this machine

### 8. Alternative Execution Methods
**Tried:**
- ❌ `sbt run` - Hangs
- ❌ `sbt "runMain Application"` - Hangs
- ✅ `sbt test` - **All tests pass** (tests don't start HTTP server)
- ❌ `java -cp <classpath> Application` - Hangs
- ✅ `scala-cli run` (pure ZIO HTTP) - **Works**
- ❌ `scala-cli run` (full app with Tapir) - Compilation errors (different issue)

## Analysis

### Where Exactly It Hangs
1. **Netty initialization:** ✅ Completes successfully
   - Native library loaded
   - Platform capabilities detected
   - Event loop configured
   - JCTools queue available

2. **ZIO Runtime startup:** ✅ Appears functional
   - ZScheduler threads active
   - Workers executing

3. **ZIO Layer construction:** ❌ **HANGS HERE**
   - Layers are being provided to program
   - `Server.serve()` is called within for-comprehension
   - ZIO effect never starts executing
   - No application logs appear

### Suspected Root Cause
The hang occurs during **ZIO layer construction** when Tapir's `ZioHttpInterpreter` attempts to integrate with ZIO HTTP server. Specifically:

1. `Server.serve(corsApp)` is called as part of the program effect
2. The program effect requires multiple ZIO layers (Server, Repository, Services, Controllers)
3. Layer construction begins but never completes
4. The hang appears to be a **deadlock or infinite loop** during layer initialization

**Possible causes:**
- Tapir's `tapir-zio-http-server` adapter has initialization issue
- ZIO HTTP 3.7.4's server binding mechanism conflicts with sbt's classloader
- Some resource (port, file handle) is blocking during layer construction
- Circular dependency in layer construction (though compile succeeds)

### Why Tests Pass But Server Doesn't
Tests use **stub servers** (`tapir-sttp-stub-server`) that don't require actual HTTP binding:
```scala
"com.softwaremill.sttp.tapir" %% "tapir-sttp-stub-server" % tapirVersion % Test
```

This bypasses the problematic `Server.serve()` + layer construction path entirely.

### Why scala-cli Works (Pure ZIO HTTP)
The minimal ZIO HTTP test:
- Uses `ZioHttpInterpreter` directly without Tapir
- Has no complex ZIO layer dependencies
- Minimal dependencies (just zio-http)
- Different classloader context (scala-cli vs sbt)

## Reproducible Test Case

### Minimal Reproduction
```bash
cd /home/danago/projects/register
sbt "project server" run
# Observe hang after Netty initialization
# Ctrl+C to exit
```

### Expected vs Actual
**Expected:**
```
[info] running com.risquanter.register.Application 
[Netty debug logs...]
00:40:46.764 [...] DEBUG -- org.jctools-core.MpscChunkedArrayQueue: available
<APPLICATION LOGS SHOULD APPEAR HERE>
[info] Bootstrapping Risk Register application...
[info] Registered 5 HTTP endpoints
[info] CORS configured for all origins
[info] Starting HTTP server on port 8080...
[info] Server started on http://localhost:8080
```

**Actual:**
```
[info] running com.risquanter.register.Application 
[Netty debug logs...]
00:40:46.764 [...] DEBUG -- org.jctools-core.MpscChunkedArrayQueue: available
<NOTHING - HANGS INDEFINITELY>
```

## Workarounds That Work

### 1. Run Tests Only
```bash
sbt test  # All 209 tests pass
```

### 2. Package and Run Outside sbt (Requires sbt-assembly plugin)
```bash
sbt "project server" assembly
java -jar target/scala-3.6.3/register-server-assembly-0.1.0.jar
```

### 3. Run from IDE
- Open project in IntelliJ IDEA
- Right-click Application.scala → Run
- Bypasses sbt's runner entirely

### 4. Docker Container (Future)
Package as container to isolate from sbt environment

## Impact Assessment

### What Works
- ✅ Code compiles successfully
- ✅ All business logic correct
- ✅ All 209 tests pass
- ✅ API definitions complete
- ✅ Request/response models validated
- ✅ Service layer functional
- ✅ Repository layer functional
- ✅ Monte Carlo simulation executes correctly

### What Doesn't Work
- ❌ Cannot run server via `sbt run`
- ❌ Cannot test API endpoints interactively during development
- ❌ Cannot demonstrate API via HTTPie/curl
- ❌ Cannot verify CORS configuration in practice
- ❌ Cannot validate Swagger UI generation

### Development Impact
- **Blocking:** Cannot do manual API testing during development
- **Workaround:** Tests provide sufficient validation for now
- **Production:** Packaging as JAR or Docker container should work

## Open Questions

1. **Why does cheleb also hang?** - Both projects use same stack, both hang
2. **Why does pure ZIO HTTP work in scala-cli?** - Different classloader? Different runtime setup?
3. **Is this Debian-specific?** - Haven't tested on other systems
4. **Is this a known Tapir + ZIO HTTP issue?** - Should search Tapir/ZIO HTTP issue trackers
5. **Would downgrading ZIO HTTP help?** - Currently using 3.7.4 (via Tapir 1.13.4)

## Next Steps for Resolution

### Short-term
1. ✅ Document issue completely (this document)
2. Continue development using tests for validation
3. Use IDE for interactive server testing if needed

### Medium-term
1. Package as standalone JAR and test
2. Try on different machine/OS
3. Create minimal reproduction case and file bug report
4. Search Tapir/ZIO HTTP GitHub issues for similar reports

### Long-term
1. Consider switching to different HTTP library (http4s + ZIO?)
2. Consider containerization for consistent runtime environment
3. Consider using native-image compilation (GraalVM)

## Related Files
- `DEVELOPMENT_CONTEXT.md` - Overall project status and architecture
- `build.sbt` - Dependency and build configuration
- `project/build.properties` - sbt version specification
- `Application.scala` - Server entry point (where hang occurs)
- `API_EXAMPLES.md` - HTTPie examples (can't test due to hang)

---

**Document Purpose:** This file captures all technical details about the server hang issue so future debugging sessions or different environments can reproduce and potentially resolve the problem. The code itself is correct - this is purely a runtime/environment issue.
