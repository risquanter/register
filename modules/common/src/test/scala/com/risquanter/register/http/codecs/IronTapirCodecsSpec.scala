package com.risquanter.register.http.codecs

import zio.test.*

import sttp.tapir.{Codec, CodecFormat, DecodeResult}

import com.risquanter.register.domain.data.iron.{
  BranchChoice,
  CommitHash,
  NodeId,
  NonNegativeInt,
  NonNegativeLong,
  PositiveInt,
  SafeId,
  ScenarioName,
  SeedEntityId,
  TreeId,
  UserId,
  ValidationUtil,
  WorkspaceKeySecret
}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName}

/** The contract every Iron codec in this file keeps at the HTTP boundary.
  *
  * Tapir separates two failures, and the separation is the whole subject here.
  * `DecodeResult.Error` says the raw input could not be read at all.
  * `DecodeResult.InvalidValue` says a value was read and a constraint then
  * refused it. Each codec parses first and refines second, so reaching the
  * refinement already proves the input was readable: a refusal is always the
  * second kind, never the first.
  *
  * Three properties follow, and each is asserted for every codec rather than
  * for one representative. A refusal reports as a refusal. It carries the
  * refinement's own sentence, so the message a client reads is the one the
  * domain wrote. And it carries none of the submitted value, because a message
  * is a disclosure surface whether or not the value came from the same client
  * (ADR-035).
  */
object IronTapirCodecsSpec extends ZIOSpecDefault:

  import IronTapirCodecs.given

  /** The location these refinements are called with. A codec supplies its own
    * when it runs; here the refinement is called directly, only to obtain the
    * sentence it produces, so the location is immaterial and one stands for
    * all. */
  private val canaryAt: ErrorLocation = ErrorLocation.requestField(FieldName.Id)

  /** A string a client could submit that no refinement accepts and that is
    * recognisable in any output. Long enough to break every length bound here,
    * and it carries the marker so a leak is visible wherever it surfaces.
    */
  private val StringCanary: String = "LEAKCANARY" + "x" * 64

  /** The numeric counterpart. Negative, so every refinement in this file
    * refuses it, and distinctive enough to find in rendered text.
    */
  private val NumberCanary: Long = -987654321L

  /** One codec under test, paired with an input its refinement refuses.
    *
    * `expected` is read from the smart constructor rather than written out, so
    * the assertion is that the codec reports what the domain said — not that it
    * reports one particular sentence this test happens to know.
    */
  private case class Refusal(
    name:     String,
    decode:   String => DecodeResult[Any],
    input:    String,
    expected: List[String],
    canary:   String
  )

  private def messagesOf(errs: List[com.risquanter.register.domain.errors.ValidationError]): List[String] =
    errs.map(_.message)

  private def codecFor[T](using c: Codec[String, T, CodecFormat.TextPlain]): String => DecodeResult[Any] =
    raw => c.decode(raw)

  /** Every codec in `IronTapirCodecs` that refines, with a refused input each.
    *
    * The list is the point of the suite: a codec added without a `refused` call
    * is a codec missing from here, and the count assertion below is what makes
    * that omission visible rather than silent.
    */
  private val refusals: List[Refusal] = List(
    Refusal(
      "PositiveInt",
      codecFor[PositiveInt],
      NumberCanary.toInt.toString,
      messagesOf(ValidationUtil.refinePositiveInt(NumberCanary.toInt, canaryAt).left.getOrElse(Nil)),
      NumberCanary.abs.toString
    ),
    Refusal(
      "NonNegativeInt",
      codecFor[NonNegativeInt],
      NumberCanary.toInt.toString,
      messagesOf(ValidationUtil.refineNonNegativeInt(NumberCanary.toInt, canaryAt).left.getOrElse(Nil)),
      NumberCanary.abs.toString
    ),
    Refusal(
      "NonNegativeLong",
      codecFor[NonNegativeLong],
      NumberCanary.toString,
      messagesOf(ValidationUtil.refineNonNegativeLong(NumberCanary, canaryAt).left.getOrElse(Nil)),
      NumberCanary.abs.toString
    ),
    Refusal(
      "SafeId",
      codecFor[SafeId.SafeId],
      StringCanary,
      messagesOf(SafeId.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    ),
    Refusal(
      "TreeId",
      codecFor[TreeId],
      StringCanary,
      messagesOf(TreeId.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    ),
    Refusal(
      "NodeId",
      codecFor[NodeId],
      StringCanary,
      messagesOf(NodeId.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    ),
    Refusal(
      "WorkspaceKeySecret",
      codecFor[WorkspaceKeySecret],
      StringCanary,
      messagesOf(WorkspaceKeySecret.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    ),
    Refusal(
      "UserId.Authenticated",
      codecFor[UserId.Authenticated],
      StringCanary,
      messagesOf(UserId.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    ),
    Refusal(
      "ScenarioName",
      codecFor[ScenarioName.ScenarioName],
      StringCanary,
      messagesOf(ScenarioName.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    ),
    Refusal(
      "CommitHash",
      codecFor[CommitHash],
      StringCanary,
      messagesOf(CommitHash.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    ),
    Refusal(
      "SeedEntityId",
      codecFor[SeedEntityId.SeedEntityId],
      NumberCanary.toString,
      messagesOf(SeedEntityId.fromLong(NumberCanary).left.getOrElse(Nil)),
      NumberCanary.abs.toString
    ),
    // The only codec here whose decode branches before it refines: "main" is
    // answered without consulting a refinement, and every other value goes
    // through ScenarioName, which is therefore the source of its sentence.
    Refusal(
      "BranchChoice",
      codecFor[BranchChoice],
      StringCanary,
      messagesOf(ScenarioName.fromString(StringCanary).left.getOrElse(Nil)),
      StringCanary
    )
  )

  /** How many codecs in `IronTapirCodecs` refine, counted as `refused` call
    * sites in that file. The assertion below compares the list against this
    * number, so a codec added there fails this suite until it is listed here.
    */
  private val RefiningCodecCount: Int = 12

  /** The messages a decode failure carries, however it carries them.
    *
    * An `InvalidValue` holds them in each error's `customMessage`. Reading them
    * through one function lets the assertions below speak about "the messages
    * the failure reports" without naming the shape.
    */
  private def reportedMessages(result: DecodeResult[Any]): List[String] =
    result match
      case DecodeResult.InvalidValue(errors) => errors.flatMap(_.customMessage)
      case _                                 => Nil

  override def spec = suite("IronTapirCodecs")(

    suite("a value the refinement refuses is reported as refused")(
      refusals.map { r =>
        test(r.name) {
          assertTrue(r.decode(r.input).isInstanceOf[DecodeResult.InvalidValue])
        }
      }*
    ),

    suite("the refusal carries the refinement's own sentence")(
      refusals.map { r =>
        test(r.name) {
          assertTrue(
            r.expected.nonEmpty,
            reportedMessages(r.decode(r.input)) == r.expected
          )
        }
      }*
    ),

    suite("the refusal repeats no part of the submitted value")(
      refusals.map { r =>
        test(r.name) {
          val rendered = r.decode(r.input).toString
          assertTrue(!rendered.contains(r.canary))
        }
      }*
    ),

    suite("input that cannot be read at all stays a read failure")(
      test("a non-numeric value for an Int codec is an Error, not a refusal") {
        assertTrue(codecFor[PositiveInt]("not-a-number").isInstanceOf[DecodeResult.Error])
      },
      test("a non-numeric value for a Long codec is an Error, not a refusal") {
        assertTrue(codecFor[NonNegativeLong]("not-a-number").isInstanceOf[DecodeResult.Error])
      }
    ),

    suite("an accepted value still decodes")(
      test("a positive integer decodes to a value") {
        assertTrue(codecFor[PositiveInt]("7").isInstanceOf[DecodeResult.Value[?]])
      },
      test("a well-formed scenario name decodes to a value") {
        assertTrue(codecFor[ScenarioName.ScenarioName]("my-scenario").isInstanceOf[DecodeResult.Value[?]])
      }
    ),

    test("every refining codec in the file is covered here") {
      assertTrue(
        refusals.size == RefiningCodecCount,
        refusals.map(_.name).distinct.size == refusals.size
      )
    }
  )
