package com.risquanter.register.infra.irmin

import zio.*
import _root_.zio.json.*
import zio.prelude.NonEmptyList
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.{Uri, StatusCode}
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.domain.errors.*
// The store's GraphQL model declares its own `ErrorLocation`; a failure's
// location is the domain one.
import com.risquanter.register.domain.errors.ErrorLocation
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, MergeStagingRef, PositiveInt, StoreBranch}
import com.risquanter.register.infra.irmin.model.*
import com.risquanter.register.infra.irmin.model.ListTreeResponse
import com.risquanter.register.infra.irmin.model.GraphQLError.given
import com.risquanter.register.infra.irmin.model.SetValueResponse.given

import java.util.concurrent.TimeoutException as JTimeoutException

/**
  * Live implementation of IrminClient using sttp HTTP client.
  *
  * Uses ZIO's HTTP client backend for async requests. A request Irmin did not
  * answer fails with `Unanswered` at the store (ADR-010).
  *
  * No retry logic - delegated to service mesh per ADR-012.
  */
final class IrminClientLive private (
    config: IrminConfig,
    backend: SttpBackend[Task, Any]
) extends IrminClient:

  private val defaultAuthor = "zio-client"

  /** Error-message prefix emitted by the patched irmin-graphql merge
    * resolvers on a refused merge
    * (containers/builders/patches/irmin-graphql-3.11.0-merge-conflict.patch).
    */
  private val MergeConflictPrefix = "merge conflict: "

  private def branchLog(branch: StoreBranch): String =
    branch match
      case BranchRef.Main => ""
      case named          => s" [branch=${named.name}]"

  override def get(path: IrminPath, branch: BranchRef = BranchRef.Main): IO[IrminError | Failure, Option[String]] =
    for
      _        <- ZIO.logDebug(s"Irmin GET: ${path.value}${branchLog(branch)}")
      response <- executeQuery[GetValueResponse](IrminQueries.getValue(path, branch))
      value     = response.data.flatMap(_.main).flatMap(_.tree.get)
      _        <- ZIO.logDebug(s"Irmin GET result: ${value.map(_.take(50))}")
    yield value

  override def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main): IO[IrminError | Failure, IrminCommit] =
    setOn(path, value, message, branch)

  override def set(path: IrminPath, value: String, message: CommitMessage, staging: MergeStagingRef): IO[IrminError | Failure, IrminCommit] =
    setOn(path, value, message, staging)

  /** The single `set` mutation both overloads issue. Private, so the branch
    * types each overload accepts stay the public contract. */
  private def setOn(path: IrminPath, value: String, message: CommitMessage, branch: StoreBranch): IO[IrminError | Failure, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin SET: ${path.value} (${value.length} bytes)${branchLog(branch)}")
      query     = IrminQueries.setValue(path, value, message, defaultAuthor, branch)
      response <- executeQuery[SetValueResponse](query)
      commit   <- extractCommit(response)
      _        <- ZIO.logInfo(s"Irmin SET committed: ${commit.hash.value.take(12)}")
    yield commit

  override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main): IO[IrminError | Failure, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin SET_TREE: ${path.value} (${entries.size} entries)${branchLog(branch)}")
      query     = IrminQueries.setTree(path, entries, message, defaultAuthor, branch)
      response <- executeQuery[SetTreeResponse](query)
      commit   <- response.data.flatMap(_.set_tree) match
                    case Some(c) => refineCommit(c)
                    case None    => failWithError(response.errors)
      _        <- ZIO.logInfo(s"Irmin SET_TREE committed: ${commit.hash.value.take(12)}")
    yield commit

  override def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main): IO[IrminError | Failure, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin REMOVE: ${path.value}${branchLog(branch)}")
      query     = IrminQueries.removeValue(path, message, defaultAuthor, branch)
      response <- executeQuery[RemoveValueResponse](query)
      commit   <- extractRemoveCommit(response)
      _        <- ZIO.logInfo(s"Irmin REMOVE committed: ${commit.hash.value.take(12)}")
    yield commit

  override def branches: IO[IrminError | Failure, List[StoreBranch]] =
    for
      _        <- ZIO.logDebug("Irmin LIST BRANCHES")
      response <- executeQuery[BranchesResponse](IrminQueries.listBranches)
      listed   <- ZIO.foreach(response.data.map(_.branches.map(_.name)).getOrElse(Nil))(refineBranch)
      _        <- ZIO.logDebug(s"Irmin branches: ${listed.map(_.name).mkString(", ")}")
    yield listed

  override def mainBranch: IO[IrminError | Failure, Option[IrminBranch]] =
    branchInfo(BranchRef.Main)

  override def getBranch(branch: StoreBranch): IO[IrminError | Failure, Option[IrminBranch]] =
    branchInfo(branch)

  private def branchInfo(branch: StoreBranch): IO[IrminError | Failure, Option[IrminBranch]] =
    for
      _        <- ZIO.logDebug(s"Irmin GET BRANCH${branchLog(branch)}")
      response <- executeQuery[MainBranchResponse](IrminQueries.getBranchInfo(branch))
      info     <- ZIO.foreach(response.data.flatMap(_.main))(b =>
                    refineBranch(b.name).zipWith(ZIO.foreach(b.head)(refineCommit))(IrminBranch.apply)
                  )
    yield info

  override def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage): IO[IrminError | Failure, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin MERGE: ${from.value.take(12)} → ${into.name}")
      query     = IrminQueries.mergeWithCommit(from, into, message, defaultAuthor)
      response <- executeQuery[MergeCommitResponse](query)
      commit   <- response.data.flatMap(_.merge_with_commit) match
                    case Some(c) => refineCommit(c)
                    case None    =>
                      // The patched irmin-graphql resolver reports a refused
                      // merge as a GraphQL error prefixed "merge conflict: "
                      // (containers/builders/patches/); discriminate it from
                      // other GraphQL failures of the same mutation here so
                      // callers get a typed conflict.
                      val (messages, _) = collectGraphQl(response.errors)
                      messages.find(_.startsWith(MergeConflictPrefix)) match
                        case Some(m) => ZIO.fail(IrminMergeConflict(m.stripPrefix(MergeConflictPrefix)))
                        case None    => ZIO.fail(graphQlFailure(response.errors))
      _        <- ZIO.logInfo(s"Irmin MERGE committed: ${commit.hash.value.take(12)}")
    yield commit

  override def revert(commit: CommitHash, branch: BranchRef): IO[IrminError | Failure, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin REVERT to ${commit.value.take(12)}${branchLog(branch)}")
      response <- executeQuery[RevertResponse](IrminQueries.revert(commit, branch))
      newHead  <- response.data.flatMap(_.revert) match
                    case Some(c) => refineCommit(c)
                    case None    => failWithError(response.errors)
      _        <- ZIO.logInfo(s"Irmin REVERT new head: ${newHead.hash.value.take(12)}")
    yield newHead

  override def createBranchAt(branch: StoreBranch, at: CommitHash): IO[IrminError | Failure, Unit] =
    for
      _        <- ZIO.logInfo(s"Irmin CREATE BRANCH: ${branch.name} at ${at.value.take(12)}")
      query     = IrminQueries.testAndSetBranch(branch, test = None, set = Some(at))
      response <- executeQuery[TestAndSetBranchResponse](query)
      applied  <- extractCasResult(response)
      _        <- ZIO.fail(BranchAlreadyExists(branch)).unless(applied)
      _        <- ZIO.logInfo(s"Irmin CREATE BRANCH ${branch.name}: applied")
    yield ()

  override def deleteBranch(branch: StoreBranch, currentHead: CommitHash): IO[IrminError | Failure, Unit] =
    for
      _        <- ZIO.logInfo(s"Irmin DELETE BRANCH: ${branch.name} at ${currentHead.value.take(12)}")
      query     = IrminQueries.testAndSetBranch(branch, test = Some(currentHead), set = None)
      response <- executeQuery[TestAndSetBranchResponse](query)
      applied  <- extractCasResult(response)
      _        <- ZIO.fail(BranchHeadStale(branch, currentHead)).unless(applied)
      _        <- ZIO.logInfo(s"Irmin DELETE BRANCH ${branch.name}: applied")
    yield ()

  override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[IrminError | Failure, Unit] =
    for
      _        <- ZIO.logInfo(s"Irmin MOVE BRANCH: ${branch.name} ${expectedHead.value.take(12)} -> ${to.value.take(12)}")
      query     = IrminQueries.testAndSetBranch(branch, test = Some(expectedHead), set = Some(to))
      response <- executeQuery[TestAndSetBranchResponse](query)
      applied  <- extractCasResult(response)
      _        <- ZIO.fail(BranchHeadStale(branch, expectedHead)).unless(applied)
      _        <- ZIO.logInfo(s"Irmin MOVE BRANCH ${branch.name}: applied")
    yield ()

  override def getCommit(hash: CommitHash): IO[IrminError | Failure, Option[IrminCommit]] =
    for
      _        <- ZIO.logDebug(s"Irmin GET COMMIT: ${hash.value.take(12)}")
      response <- executeQuery[CommitQueryResponse](IrminQueries.getCommit(hash))
      commit   <- ZIO.foreach(response.data.flatMap(_.commit))(refineCommit)
    yield commit

  override def getAtCommit(commit: CommitHash, path: IrminPath): IO[IrminError | Failure, Option[String]] =
    for
      _        <- ZIO.logDebug(s"Irmin GET@${commit.value.take(12)}: ${path.value}")
      response <- executeQuery[CommitTreeGetResponse](IrminQueries.getValueAtCommit(commit, path))
      value     = response.data.flatMap(_.commit).flatMap(_.tree.get)
      _        <- ZIO.logDebug(s"Irmin GET@commit result: ${value.map(_.take(50))}")
    yield value

  override def listAtCommit(commit: CommitHash, path: IrminPath): IO[IrminError | Failure, List[IrminSegment]] =
    for
      _        <- ZIO.logDebug(s"Irmin LIST@${commit.value.take(12)}: ${path.value}")
      response <- executeQuery[CommitTreeListResponse](IrminQueries.listTreeAtCommit(commit, path))
      paths    <- extractListAtCommit(path, response)
      _        <- ZIO.logDebug(s"Irmin LIST@commit result (${paths.size}): ${paths.map(_.value).mkString(", ")}")
    yield paths

  override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): IO[IrminError | Failure, List[IrminCommit]] =
    for
      _        <- ZIO.logDebug(s"Irmin HISTORY: ${path.value} (n=$n)${branchLog(branch)}")
      response <- executeQuery[HistoryResponse](IrminQueries.getHistory(path, n, branch))
      commits  <- ZIO.foreach(response.data.flatMap(_.main).map(_.last_modified).getOrElse(Nil))(refineCommit)
    yield commits

  override def lca(branch: BranchRef, commit: CommitHash): IO[IrminError | Failure, List[IrminCommit]] =
    for
      _        <- ZIO.logDebug(s"Irmin LCA: ${commit.value.take(12)}${branchLog(branch)}")
      response <- executeQuery[LcaResponse](IrminQueries.lca(branch, commit))
      commits  <- ZIO.foreach(response.data.flatMap(_.main).map(_.lcas).getOrElse(Nil))(refineCommit)
    yield commits

  override def healthCheck: IO[IrminError | Failure, Unit] =
    // Typed error carries the real cause (connection refused, HTTP status, parse
    // failure) through the readiness gate's retries to the final failure (ADR-031).
    executeQuery[BranchesResponse](IrminQueries.listBranches).unit

  override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main): IO[IrminError | Failure, List[IrminSegment]] =
    for
      _        <- ZIO.logDebug(s"Irmin LIST: ${prefix.value}${branchLog(branch)}")
      response <- executeQuery[ListTreeResponse](IrminQueries.listTree(prefix, branch))
      paths    <- extractList(prefix, response)
      _        <- ZIO.logDebug(s"Irmin LIST result (${paths.size}): ${paths.map(_.value).mkString(", ")}")
    yield paths

  // ============================================================================
  // Private helpers
  // ============================================================================

  private def executeQuery[R: JsonDecoder](query: String): IO[Failure, R] =
    val request = GraphQLRequest(query)
    val requestJson = request.toJson
    val uri = Uri.unsafeParse(config.graphqlUrl)
    
    basicRequest
      .post(uri)
      .contentType("application/json")
      .body(requestJson)
      .response(asJson[R])
      .readTimeout(config.timeout.asScala)
      .send(backend)
      .flatMap { response =>
        response.body match
          case Right(value) => ZIO.succeed(value)
          case Left(error)  =>
            val status = response.code
            val detail = error match
              case HttpError(body, _)                 => s"HttpError status=${status.code} body=$body"
              case DeserializationException(orig, msg) => s"Deserialization error: $msg; original=$orig"
            ZIO.logError(s"Irmin executeQuery failed: $detail") *> ZIO.fail(parseError(error, status))
      }
      .mapError(mapNetworkError)

  /** A response Irmin sent that is not an answer: a status other than success,
    * whose body is kept for the log, or a body that did not decode. */
  private def parseError(error: ResponseException[String, String], status: StatusCode): Failure =
    error match
      case HttpError(body, _)                => outage(OutageMode.Status(status), List(Diagnostic.PeerBody(body)))
      case DeserializationException(body, _) => outage(OutageMode.Protocol, List(Diagnostic.PeerBody(body)))

  /** Two kinds of failure reach this one channel and both become a `Failure`:
    * a typed failure this client already produced, and a raw exception thrown
    * by the HTTP backend. The parameter is the union of the two because a
    * failure value is not a `Throwable`, so neither can stand for the other.
    */
  private def mapNetworkError(error: Throwable | Failure): Failure = error match
    case f: Failure           => f
    case _: JTimeoutException => outage(OutageMode.Timeout(config.timeout), Nil)
    case e: java.io.IOException if Option(e.getMessage).exists(_.contains("timeout")) =>
      outage(OutageMode.Timeout(config.timeout), Nil)
    case e: Throwable         => outage(OutageMode.ConnectionRefused, Option(e.getMessage).map(Diagnostic.PeerMessage(_)).toList)

  /** Irmin did not answer, in the given way. */
  private def outage(mode: OutageMode, diagnostics: List[Diagnostic]): Failure =
    Failure.Unanswered(Core(PeerZone.Store, NonEmptyList((ErrorLocation.Whole, mode)), diagnostics))

  /** Irmin answered with GraphQL `errors` and no data: its messages and the
    * path they name are kept for the log. */
  private def graphQlFailure(errors: Option[List[GraphQLError]]): Failure =
    val (messages, path) = collectGraphQl(errors)
    outage(OutageMode.Protocol, Diagnostic.PeerMessage(messages.mkString("; ")) :: path.map(Diagnostic.GraphQlPath(_)).toList)

  private def extractCommit(response: SetValueResponse): IO[IrminError | Failure, IrminCommit] =
    response.data.flatMap(_.set) match
      case Some(c) => refineCommit(c)
      case None    => failWithError(response.errors)

  private def extractRemoveCommit(response: RemoveValueResponse): IO[IrminError | Failure, IrminCommit] =
    response.data.flatMap(_.remove) match
      case Some(c) => refineCommit(c)
      case None    => failWithError(response.errors)

  private def extractCasResult(response: TestAndSetBranchResponse): IO[IrminError | Failure, Boolean] =
    response.data.flatMap(_.test_and_set_branch) match
      case Some(applied) => ZIO.succeed(applied)
      case None          => ZIO.fail(graphQlFailure(response.errors))

  /** Shared tail of the branch-scoped and commit-scoped list extractors: turn
    * the optional `list` payload into the child names one level below
    * `prefix`, each refined to a segment where it arrives (ADR-039). A name
    * that is not a segment is content the store returned and this service
    * refuses, kept for the log. A null payload (path absent / empty store /
    * unknown commit) is an empty listing unless Irmin reported actual errors. */
  private def childPaths(prefix: IrminPath, list: Option[List[TreeNode]], errors: Option[List[GraphQLError]]): IO[Failure, List[IrminSegment]] =
    list match
      case Some(nodes) =>
        val base = if prefix.value.isEmpty then "" else s"${prefix.value}/"
        val childNames = nodes.map(_.path.stripPrefix("/").stripPrefix(base)).filter(_.nonEmpty)
        ZIO.foreach(childNames)(name => ZIO.fromEither(IrminSegment.from(name).left.map(refusals =>
          Failure.rejected(TrustZone.Peer(PeerZone.Store), refusals, List(Diagnostic.RawValue(name))))))
      case None =>
        errors match
          case Some(errs) if errs.nonEmpty => failWithListError(errors)
          case _                           => ZIO.succeed(List.empty)

  private def extractList(prefix: IrminPath, response: ListTreeResponse): IO[IrminError | Failure, List[IrminSegment]] =
    childPaths(prefix, response.data.flatMap(_.main).flatMap(_.tree.get_tree).map(_.list), response.errors)

  private def extractListAtCommit(prefix: IrminPath, response: CommitTreeListResponse): IO[IrminError | Failure, List[IrminSegment]] =
    childPaths(prefix, response.data.flatMap(_.commit).flatMap(_.tree.get_tree).map(_.list), response.errors)

  /** Refines a commit where Irmin's answer arrives (ADR-039). A hash that is
    * not a commit hash, a date that is not an instant or a message this
    * service did not write is content the store returned and this service
    * refuses; the hash and the message are kept for the log. */
  private def refineCommit(raw: IrminCommit.Raw): IO[Failure, IrminCommit] =
    ZIO.fromEither(IrminCommit.refine(raw).left.map(refusals =>
      Failure.rejected(TrustZone.Peer(PeerZone.Store), refusals, List(Diagnostic.RawValue(raw.hash), Diagnostic.RawValue(raw.info.message)))))

  /** Refines a branch name where Irmin's answer arrives (ADR-039). A name that
    * is not one this service gives a branch is content the store returned and
    * this service refuses, kept for the log. */
  private def refineBranch(name: String): IO[Failure, StoreBranch] =
    ZIO.fromEither(StoreBranch.fromString(name, ErrorLocation.Whole).left.map(refusals =>
      Failure.rejected(TrustZone.Peer(PeerZone.Store), NonEmptyList(refusals.head, refusals.tail*), List(Diagnostic.RawValue(name)))))

  private def failWithError(errors: Option[List[GraphQLError]]): IO[Failure, IrminCommit] =
    ZIO.fail(graphQlFailure(errors))

  private def failWithListError(errors: Option[List[GraphQLError]]): IO[Failure, List[IrminSegment]] =
    ZIO.fail(graphQlFailure(errors))

  private def collectGraphQl(errors: Option[List[GraphQLError]]): (List[String], Option[List[String]]) =
    val msgs = errors.map(_.map(_.message)).getOrElse(List("Unknown error"))
    val path = errors.flatMap(_.flatMap(_.path).headOption)
    (msgs, path)

// Response type for main branch query
private final case class MainBranchResponse(
    data: Option[MainBranchResponseData],
    errors: Option[List[GraphQLError]]
)

private final case class MainBranchResponseData(
    main: Option[MainBranchInfo]
)

private final case class MainBranchInfo(
    name: String,
    head: Option[IrminCommit.Raw]
)

private object MainBranchResponse:
  given JsonCodec[MainBranchInfo] = DeriveJsonCodec.gen[MainBranchInfo]
  given JsonCodec[MainBranchResponseData] = DeriveJsonCodec.gen[MainBranchResponseData]
  given JsonCodec[MainBranchResponse] = DeriveJsonCodec.gen[MainBranchResponse]

object IrminClientLive:
  /**
    * ZLayer that provides IrminClient.
    *
    * Requires IrminConfig for endpoint configuration.
    * Creates and manages the HTTP backend lifecycle.
    */
  val layer: ZLayer[IrminConfig, Throwable, IrminClient] =
    ZLayer.scoped {
      for
        config  <- ZIO.service[IrminConfig]
        backend <- HttpClientZioBackend.scoped()
        _       <- ZIO.logInfo(s"IrminClient initialized: ${config.graphqlUrl}")
      yield IrminClientLive(config, backend)
    }

  /**
    * ZLayer with default config from application.conf.
    */
  val layerWithConfig: ZLayer[Any, Throwable, IrminClient] =
    IrminConfig.layer >>> layer
