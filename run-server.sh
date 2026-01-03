#!/bin/bash
cd /home/danago/projects/register

/tmp/scala-cli run \
  --dep com.softwaremill.sttp.tapir::tapir-zio-http-server:1.13.4 \
  --dep com.softwaremill.sttp.tapir::tapir-json-zio:1.13.4 \
  --dep com.softwaremill.sttp.tapir::tapir-swagger-ui-bundle:1.13.4 \
  --dep dev.zio::zio-logging-slf4j:2.2.4 \
  --dep ch.qos.logback:logback-classic:1.5.23 \
  --dep io.github.iltotore::iron-zio:3.2.1 \
  --dep org.scala-lang.modules::scala-parallel-collections:1.0.4 \
  --dep com.risquanter:simulation.util:0.8.0 \
  --scala 3.6.3 \
  --main-class com.risquanter.register.Application \
  modules/common/src/main/scala \
  modules/server/src/main/scala
