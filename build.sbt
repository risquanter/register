ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "3.6.3"

ThisBuild / scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Xlint:type-parameter-shadow",
  "-Xmax-inlines:64"
)

ThisBuild / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

// Dependency versions
val sttpVersion       = "3.10.1"
val zioVersion        = "2.1.9"
val tapirVersion      = "1.11.10"
val zioLoggingVersion = "2.3.1"
val zioConfigVersion  = "4.0.2"
val quillVersion      = "4.7.3"
val ironVersion       = "3.2.1"

// Common dependencies (shared between JVM and JS)
val commonDependencies = Seq(
  "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client"       % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-json-zio"          % tapirVersion,
  "com.softwaremill.sttp.tapir"   %% "tapir-swagger-ui-bundle" % tapirVersion,
  "com.softwaremill.sttp.client3" %% "zio"                     % sttpVersion,
  "dev.zio"                       %% "zio"                     % zioVersion,
  "dev.zio"                       %% "zio-json"                % "0.7.1",
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
  "com.softwaremill.sttp.client3" %% "zio"                               % sttpVersion,
  "dev.zio"                       %% "zio-logging"                       % zioLoggingVersion,
  "dev.zio"                       %% "zio-logging-slf4j"                 % zioLoggingVersion,
  "ch.qos.logback"                 % "logback-classic"                   % "1.4.12",
  "dev.zio"                       %% "zio-test"                          % zioVersion,
  "dev.zio"                       %% "zio-test"                          % zioVersion   % Test,
  "dev.zio"                       %% "zio-test-sbt"                      % zioVersion   % Test,
  "dev.zio"                       %% "zio-test-magnolia"                 % zioVersion   % Test,
  "dev.zio"                       %% "zio-config"                        % zioConfigVersion,
  "dev.zio"                       %% "zio-config-magnolia"               % zioConfigVersion,
  "dev.zio"                       %% "zio-config-typesafe"               % zioConfigVersion,
  "io.getquill"                   %% "quill-jdbc-zio"                    % quillVersion,
  "org.postgresql"                 % "postgresql"                        % "42.7.3",
  "io.github.scottweaver"         %% "zio-2-0-testcontainers-postgresql" % "0.10.0"     % Test,
  "io.github.iltotore"            %% "iron-zio"                          % ironVersion
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
  .settings(
    name := "register-server",
    libraryDependencies ++= serverDependencies
  )
  .dependsOn(common.jvm)

// App module (ScalaJS frontend - placeholder for future)
lazy val app = (project in file("modules/app"))
  .settings(
    name := "register-app",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir"   %%% "tapir-sttp-client" % tapirVersion,
      "com.softwaremill.sttp.tapir"   %%% "tapir-json-zio"    % tapirVersion,
      "com.softwaremill.sttp.client3" %%% "zio"               % sttpVersion,
      "dev.zio"                       %%% "zio-json"          % "0.7.1"
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalaJSUseMainModuleInitializer := true
  )
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(common.js)

// Root project
lazy val root = (project in file("."))
  .settings(
    name := "register"
  )
  .aggregate(server, app)
  .dependsOn(server)
