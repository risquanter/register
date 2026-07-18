package com.risquanter.register.infra.irmin

import zio.*
import _root_.zio.json.*
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.{Uri, StatusCode}
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.domain.errors.*
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, PositiveInt}
import com.risquanter.register.infra.irmin.model.*
import com.risquanter.register.infra.irmin.model.ListTreeResponse

import java.net.ConnectException
import java.util.concurrent.TimeoutException as JTimeoutException
import scala.concurrent.duration.Duration as ScalaDuration

/**
  * Live implementation of IrminClient using sttp HTTP client.
  *
  * Uses ZIO's HTTP client backend for async requests.
  * Maps network errors to AppError/IrminError hierarchy per ADR-010.
  *
  * No retry logic - delegated to service mesh per ADR-012.
  */
final class IrminClientLive private (
    config: IrminConfig,
    backend: SttpBackend[Task, Any]
) extends IrminClient:

  private val defaultAuthor = "zio-client"

  // Log suffix + query argument for branch-parameterized ops (None = main)
  private def branchName(branch: Option[BranchRef]): Option[String] =
    branch.map(_.toBranchRef)

  private def branchLog(branch: Option[BranchRef]): String =
    branch.fold("")(b => s" [branch=${b.toBranchRef}]")

  override def get(path: IrminPath, branch: Option[BranchRef] = None): IO[IrminError, Option[String]] =
    for
      _        <- ZIO.logDebug(s"Irmin GET: ${path.value}${branchLog(branch)}")
      response <- executeQuery[GetValueResponse](IrminQueries.getValue(path, branchName(branch)))
      value     = response.data.flatMap(_.main).flatMap(_.tree.get)
      _        <- ZIO.logDebug(s"Irmin GET result: ${value.map(_.take(50))}")
    yield value

  override def set(path: IrminPath, value: String, message: String, branch: Option[BranchRef] = None): IO[IrminError, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin SET: ${path.value} (${value.length} bytes)${branchLog(branch)}")
      query     = IrminQueries.setValue(path, value, message, defaultAuthor, branchName(branch))
      response <- executeQuery[SetValueResponse](query)
      commit   <- extractCommit(response)
      _        <- ZIO.logInfo(s"Irmin SET committed: ${commit.hash.take(12)}")
    yield commit

  override def remove(path: IrminPath, message: String, branch: Option[BranchRef] = None): IO[IrminError, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin REMOVE: ${path.value}${branchLog(branch)}")
      query     = IrminQueries.removeValue(path, message, defaultAuthor, branchName(branch))
      response <- executeQuery[RemoveValueResponse](query)
      commit   <- extractRemoveCommit(response)
      _        <- ZIO.logInfo(s"Irmin REMOVE committed: ${commit.hash.take(12)}")
    yield commit

  override def branches: IO[IrminError, List[String]] =
    for
      _        <- ZIO.logDebug("Irmin LIST BRANCHES")
      response <- executeQuery[BranchesResponse](IrminQueries.listBranches)
      names     = response.data.map(_.branches.map(_.name)).getOrElse(Nil)
      _        <- ZIO.logDebug(s"Irmin branches: ${names.mkString(", ")}")
    yield names

  override def mainBranch: IO[IrminError, Option[IrminBranch]] =
    branchInfo(None)

  override def getBranch(branch: BranchRef): IO[IrminError, Option[IrminBranch]] =
    branchInfo(Some(branch))

  private def branchInfo(branch: Option[BranchRef]): IO[IrminError, Option[IrminBranch]] =
    for
      _        <- ZIO.logDebug(s"Irmin GET BRANCH${branchLog(branch)}")
      response <- executeQuery[MainBranchResponse](IrminQueries.getBranchInfo(branchName(branch)))
      info      = response.data.flatMap(_.main).map(b =>
                    IrminBranch(
                      name = b.name,
                      head = b.head.map(h =>
                        IrminCommit(
                          hash = h.hash,
                          key = h.key,
                          parents = Nil, // Not fetched in this query
                          info = IrminInfo(
                            date = h.info.date,
                            author = h.info.author,
                            message = h.info.message
                          )
                        )
                      )
                    )
                  )
    yield info

  override def mergeBranch(from: BranchRef, into: Option[BranchRef], message: String): IO[IrminError, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin MERGE: ${from.toBranchRef} → ${into.fold("main")(_.toBranchRef)}")
      query     = IrminQueries.mergeWithBranch(from.toBranchRef, branchName(into), message, defaultAuthor)
      response <- executeQuery[MergeBranchResponse](query)
      commit   <- response.data.flatMap(_.merge_with_branch) match
                    case Some(c) => commitFromData(c)
                    case None    => failWithError(response.errors)
      _        <- ZIO.logInfo(s"Irmin MERGE committed: ${commit.hash.take(12)}")
    yield commit

  override def revert(commit: CommitHash, branch: Option[BranchRef]): IO[IrminError, IrminCommit] =
    for
      _        <- ZIO.logInfo(s"Irmin REVERT to ${commit.value.take(12)}${branchLog(branch)}")
      response <- executeQuery[RevertResponse](IrminQueries.revert(commit.value, branchName(branch)))
      newHead  <- response.data.flatMap(_.revert) match
                    case Some(c) => commitFromData(c)
                    case None    => failWithError(response.errors)
      _        <- ZIO.logInfo(s"Irmin REVERT new head: ${newHead.hash.take(12)}")
    yield newHead

  override def getCommit(hash: CommitHash): IO[IrminError, Option[IrminCommit]] =
    for
      _        <- ZIO.logDebug(s"Irmin GET COMMIT: ${hash.value.take(12)}")
      response <- executeQuery[CommitQueryResponse](IrminQueries.getCommit(hash.value))
      commit   <- ZIO.foreach(response.data.flatMap(_.commit))(commitFromData)
    yield commit

  override def getHistory(path: IrminPath, n: PositiveInt, branch: Option[BranchRef] = None): IO[IrminError, List[IrminCommit]] =
    for
      _        <- ZIO.logDebug(s"Irmin HISTORY: ${path.value} (n=$n)${branchLog(branch)}")
      response <- executeQuery[HistoryResponse](IrminQueries.getHistory(path, n, branchName(branch)))
      commits  <- ZIO.foreach(response.data.flatMap(_.main).map(_.last_modified).getOrElse(Nil))(commitFromData)
    yield commits

  override def lca(branch: Option[BranchRef], commit: CommitHash): IO[IrminError, List[IrminCommit]] =
    for
      _        <- ZIO.logDebug(s"Irmin LCA: ${commit.value.take(12)}${branchLog(branch)}")
      response <- executeQuery[LcaResponse](IrminQueries.lca(branchName(branch), commit.value))
      commits  <- ZIO.foreach(response.data.flatMap(_.main).map(_.lcas).getOrElse(Nil))(commitFromData)
    yield commits

  override def healthCheck: IO[IrminError, Unit] =
    // Typed error carries the real cause (connection refused, HTTP status, parse
    // failure) through the readiness gate's retries to the final failure (ADR-031).
    executeQuery[BranchesResponse](IrminQueries.listBranches).unit

  override def list(prefix: IrminPath, branch: Option[BranchRef] = None): IO[IrminError, List[IrminPath]] =
    for
      _        <- ZIO.logDebug(s"Irmin LIST: ${prefix.value}${branchLog(branch)}")
      response <- executeQuery[ListTreeResponse](IrminQueries.listTree(prefix, branchName(branch)))
      paths    <- extractList(prefix, response)
      _        <- ZIO.logDebug(s"Irmin LIST result (${paths.size}): ${paths.map(_.value).mkString(", ")}")
    yield paths

  // ============================================================================
  // Private helpers
  // ============================================================================

  private def executeQuery[R: JsonDecoder](query: String): IO[IrminError, R] =
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

  private def parseError(error: ResponseException[String, String], status: StatusCode): IrminError =
    error match
      case HttpError(body, _) if status == StatusCode.ServiceUnavailable =>
        IrminUnavailable(s"Service returned 503: $body")
      case HttpError(body, _) =>
        IrminHttpError(status, body)
      case DeserializationException(_, jsonError) =>
        IrminHttpError(StatusCode.InternalServerError, s"Invalid response: $jsonError")

  private def mapNetworkError(error: Throwable): IrminError = error match
    case e: IrminError        => e
    case _: ConnectException  => IrminUnavailable("Connection refused")
    case _: JTimeoutException => NetworkTimeout("GraphQL request", ScalaDuration.fromNanos(config.timeout.toNanos))
    case e: java.io.IOException if e.getMessage != null && e.getMessage.contains("timeout") =>
      NetworkTimeout("GraphQL request", ScalaDuration.fromNanos(config.timeout.toNanos))
    case e => IrminUnavailable(s"Network error: ${e.getMessage}")

  private def extractCommit(response: SetValueResponse): IO[IrminError, IrminCommit] =
    response.data.flatMap(_.set) match
      case Some(c) => commitFromData(c)
      case None    => failWithError(response.errors)

  private def extractRemoveCommit(response: RemoveValueResponse): IO[IrminError, IrminCommit] =
    response.data.flatMap(_.remove) match
      case Some(c) => commitFromData(c)
      case None    => failWithError(response.errors)

  private def extractList(prefix: IrminPath, response: ListTreeResponse): IO[IrminError, List[IrminPath]] =
    response.data.flatMap(_.main).flatMap(_.tree.get_tree).map(_.list) match
      case Some(nodes) =>
        val base = if prefix.value.isEmpty then "" else s"${prefix.value}/"
        val cleanedPaths = nodes.map(_.path.stripPrefix("/"))
        val childNames = cleanedPaths.map(_.stripPrefix(base)).filter(_.nonEmpty)
        ZIO.foreach(childNames)(name => ZIO.fromEither(IrminPath.from(name).left.map(IrminUnavailable(_))))
      case None =>
        // get_tree returns null when the path doesn't exist yet (e.g. empty store).
        // Treat as empty list unless Irmin reported actual errors.
        response.errors match
          case Some(errs) if errs.nonEmpty => failWithListError(response.errors)
          case _                          => ZIO.succeed(List.empty)

  private def commitFromData(c: CommitData): IO[IrminError, IrminCommit] =
    ZIO.succeed(IrminCommit(
      hash = c.hash,
      key = c.key,
      parents = Nil, // Not returned in mutation response
      info = IrminInfo(
        date = c.info.date,
        author = c.info.author,
        message = c.info.message
      )
    ))

  private def failWithError(errors: Option[List[GraphQLError]]): IO[IrminError, IrminCommit] =
    val (messages, path) = collectGraphQl(errors)
    ZIO.fail(IrminGraphQLError(messages, path))

  private def failWithListError(errors: Option[List[GraphQLError]]): IO[IrminError, List[IrminPath]] =
    val (messages, path) = collectGraphQl(errors)
    ZIO.fail(IrminGraphQLError(messages, path))

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
    head: Option[HeadCommit]
)

private final case class HeadCommit(
    hash: String,
    key: String,
    info: InfoData
)

private object MainBranchResponse:
  import SetValueResponse.given  // For InfoData codec
  import GraphQLError.given      // For GraphQLError codec
  given JsonCodec[HeadCommit] = DeriveJsonCodec.gen[HeadCommit]
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
