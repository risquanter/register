package com.risquanter.register.domain.data

import com.risquanter.register.domain.data.iron.{TreeId, ValidationUtil}
import zio.json.{JsonEncoder, JsonDecoder}

/** JSON codecs for TreeId (ULID). */
object TreeIdCodecs:
  given JsonEncoder[TreeId.TreeId] = JsonEncoder[String].contramap(_.value.toString)

  given JsonDecoder[TreeId.TreeId] =
    JsonDecoder[String].mapOrFail(s => ValidationUtil.refineTreeId(s).left.map(_.mkString(", ")))
