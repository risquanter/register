ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "3.6.4"

ThisBuild / scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Xlint:type-parameter-shadow",
  "-Xmax-inlines:64"
)

ThisBuild / libraryDependencySchemes ++= Seq(
  "dev.zio" %% "zio-json" % VersionScheme.Always,
  "dev.zio" % "zio-json_sjs1_3" % VersionScheme.Always
)

ThisBuild / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

// Dependency versions
val sttpVersion       = "3.10.1"
val zioVersion        = "2.1.24"
val tapirVersion      = "1.13.4"
val zioLoggingVersion = "2.5.2"
val zioConfigVersion  = "4.0.2"
val quillVersion      = "4.8.6"
val ironVersion       = "3.2.2"
val zioTelemetryVersion = "3.1.13"
val openTelemetryVersion = "1.57.0"// Common dependencies (shared between JVM and JS)
val commonDependencies = Seq(
  "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client"       % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-json-zio"          % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-swagger-ui-bundle" % tapirVersion,
  "com.softwaremill.sttp.client3" %% "zio"                     % sttpVersion,
  "dev.zio"                       %% "zio"                     % zioVersion,
  "dev.zio"                       %% "zio-json"                % "0.8.0",
  "dev.zio"                       %% "zio-prelude"             % "1.0.0-RC44",
  "dev.zio"                       %% "zio-config"              % zioConfigVersion,
  "dev.zio"                       %% "zio-config-magnolia"     % zioConfigVersion,
  "dev.zio"                       %% "zio-config-typesafe"     % zioConfigVersion,
  "dev.zio"                       %% "zio-test"                % zioVersion   % Test,
  "dev.zio"                       %% "zio-test-sbt"            % zioVersion   % Test,
  "dev.zio"                       %% "zio-test-magnolia"       % zioVersion   % Test,
  "io.github.iltotore"            %% "iron"                    % ironVersion,
  "io.github.iltotore"            %% "iron-scalacheck"         % ironVersion % Test
)

// Server-specific dependencies (JVM only)
val serverDependencies = Seq(
  "com.softwaremill.sttp.tapir"   %% "tapir-zio"                         % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-zio-http-server"             % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-sttp-stub-server"            % tapirVersion % Test,
  "dev.zio"                       %% "zio-logging"                       % zioLoggingVersion,
  "dev.zio"                       %% "zio-logging-slf4j2"                % zioLoggingVersion,
  "ch.qos.logback"                 % "logback-classic"                   % "1.5.23",
  // OpenTelemetry / ZIO Telemetry
  "dev.zio"                       %% "zio-opentelemetry"                 % zioTelemetryVersion,
  "io.opentelemetry"               % "opentelemetry-sdk"                 % openTelemetryVersion,
  "io.opentelemetry"               % "opentelemetry-sdk-trace"           % openTelemetryVersion,
  "io.opentelemetry"               % "opentelemetry-sdk-metrics"         % openTelemetryVersion,
  "io.opentelemetry"               % "opentelemetry-exporter-logging"    % openTelemetryVersion,
  "io.opentelemetry"               % "opentelemetry-exporter-otlp"       % openTelemetryVersion,
  "io.opentelemetry.semconv"       % "opentelemetry-semconv"             % "1.27.0-alpha",
  // Test dependencies
  "dev.zio"                       %% "zio-test"                          % zioVersion   % Test,
  "dev.zio"                       %% "zio-test-sbt"                      % zioVersion   % Test,
  "dev.zio"                       %% "zio-test-magnolia"                 % zioVersion   % Test,
  // Other
  "io.github.iltotore"            %% "iron-zio"                          % ironVersion,
  "org.scala-lang.modules"        %% "scala-parallel-collections"        % "1.0.4",
  "com.risquanter"                 % "simulation.util"                   % "0.8.0"
)

// Common module (cross-compiled for JVM and JS)
lazy val common = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/common"))
  .settings(
    name := "register-common",
    libraryDependencies ++= commonDependencies
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.5.0"
    )
  )

// Server module (JVM only)
lazy val server = (project in file("modules/server"))
  .enablePlugins(BuildInfoPlugin, NativeImagePlugin)
  .settings(
    name := "register-server",
    libraryDependencies ++= serverDependencies,
    run / fork := true,
    run / javaOptions ++= Seq(
      "-Xms512m",
      "-Xmx2g"
    ),
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      "simulationUtilVersion" -> "0.8.0"
    ),
    buildInfoPackage := "com.risquanter.register",
    // Native image configuration
    Compile / mainClass := Some("com.risquanter.register.Application"),
    nativeImageInstalled := true,
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "--static",
      "--libc=musl",
      "-H:+ReportExceptionStackTraces",
      "-H:+AddAllCharsets",
      "--enable-url-protocols=http,https",
      "--allow-incomplete-classpath",
      "-H:IncludeResources=application.conf",
      "-H:IncludeResources=logback.xml",
      "-H:IncludeResources=reference.conf",
      // Include Swagger UI resources
      "-H:IncludeResources=META-INF/resources/webjars/.*",
      "-H:IncludeResources=swagger-ui/.*",
      "-H:ConfigurationFileDirectories=" + (Compile / resourceDirectory).value / "META-INF" / "native-image",
      // Initialize Netty at runtime (required by Netty's own native-image config)
      "--initialize-at-run-time=io.netty",
      // Initialize logging at runtime to avoid conflicts
      "--initialize-at-run-time=org.slf4j,ch.qos.logback",
      "--verbose"
    ),
    nativeImageOutput := target.value / "register-server"
  )
  .dependsOn(common.jvm)

// App module (ScalaJS frontend with Laminar)
lazy val app = (project in file("modules/app"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "register-app",
    libraryDependencies ++= Seq(
      "com.raquo"                     %%% "laminar"           % "17.2.0",
      "com.softwaremill.sttp.tapir"   %%% "tapir-sttp-client" % tapirVersion,
      "com.softwaremill.sttp.tapir"   %%% "tapir-json-zio"    % tapirVersion,
      "com.softwaremill.sttp.client3" %%% "core"              % sttpVersion,
      "dev.zio"                       %%% "zio-json"          % "0.8.0",
      "dev.zio"                       %%% "zio-prelude"       % "1.0.0-RC44",
      "io.github.iltotore"            %%% "iron"              % ironVersion,
      "io.github.cquiroz"             %%% "scala-java-time"   % "2.5.0"
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true
  )
  .dependsOn(common.js)

// Root project
lazy val root = (project in file("."))
  .settings(
    name := "register"
  )
  .aggregate(server, app)
  .dependsOn(server)
