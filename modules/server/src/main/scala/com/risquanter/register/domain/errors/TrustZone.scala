package com.risquanter.register.domain.errors

/** Where a value came from, as the side of a trust boundary it was on.
  *
  * This is the field every failure carries and the wire never sees. A refusal at
  * `Client` is the caller's to fix; a refusal anywhere else is content a peer
  * returned, or this service's own, and answers as an internal fault (ADR-039
  * §4).
  */
enum TrustZone:
  /** The client, through its request's body, path, headers and query. */
  case Client

  /** The deployment: configuration, and the capabilities the wired backends
    * have. A value here is the operator's, not the caller's. */
  case Environment

  /** The service mesh, through the typed claim header it forwards. */
  case MeshHeader

  /** A peer this service calls, named by `PeerZone`. */
  case Peer(peer: PeerZone)

  /** This service's own computation over values already inside the region. */
  case Service

/** The peers this service calls and reads answers from. */
enum PeerZone:
  /** The Irmin store: a stored value, or a GraphQL response. */
  case Store

  /** The Postgres database: a row, or the driver. */
  case Database

  /** The SpiceDB authorization service: a check or listing response. */
  case Authorization
