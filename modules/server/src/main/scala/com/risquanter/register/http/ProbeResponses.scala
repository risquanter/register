package com.risquanter.register.http

import com.risquanter.register.http.responses.{ProbeResponse, ProbeStatus}

/** The probe bodies, shared between the API port's `/health`
  * (`SystemController`) and the probe port's `/health` and `/ready`
  * (`HealthProbeServer`), so both ports answer the same body.
  *
  * The API port's `/health` is read by the frontend's `HealthState` and runs
  * through the full Tapir pipeline with CORS and security headers. The probe
  * port is read by kubelet alone, on a bare interpreter, so it can accept
  * plaintext probes while the API port enforces mTLS.
  */
object ProbeResponses:

  /** The deployment's name as an operator reads it in a probe body. Free text
    * with no narrower contract, chosen by this project. */
  val ServiceName: String = "risk-register"

  /** Liveness: the process is up and serving HTTP. It says nothing about the
    * dependencies, which is the readiness probe's question. */
  val healthy: ProbeResponse = ProbeResponse(ProbeStatus.Healthy, ServiceName)

  /** Readiness, unconditional today: the application has no slow-start
    * dependency to wait for. A dependency check, when one is added, gates this
    * value on its result. */
  val ready: ProbeResponse = ProbeResponse(ProbeStatus.Ready, ServiceName)
