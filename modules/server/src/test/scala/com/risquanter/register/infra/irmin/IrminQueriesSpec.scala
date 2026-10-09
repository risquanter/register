package com.risquanter.register.infra.irmin

import zio.test.*

import com.risquanter.register.domain.data.iron.BranchRef
import com.risquanter.register.infra.irmin.model.IrminPath

/** Escaping of the free-text values `IrminQueries` interpolates into a GraphQL
  * string literal.
  *
  * The GraphQL grammar admits no unescaped source character below U+0020 in a
  * string literal, and a quote or a backslash would end the literal or start an
  * escape of its own. Every value the query builder interpolates is either an
  * Iron-refined type whose character set excludes all of those, or free text
  * passed through the escaper — so these cases pin the escaper for the second
  * group.
  */
object IrminQueriesSpec extends ZIOSpecDefault:

  private val path = IrminPath.unsafeFrom("nodes/n")

  /** The `value:` argument of a `set` mutation, which is where a stored blob is
    * interpolated. Returned with the surrounding quotes so a literal that ended
    * early is visible.
    */
  private def valueArgOf(raw: String): String =
    val query = IrminQueries.setValue(path, raw, "msg", "author", BranchRef.Main)
    query.linesIterator.find(_.trim.startsWith("value:")).map(_.trim).getOrElse("")

  override def spec = suite("IrminQueries.setValue — GraphQL string escaping")(

    test("a quote is escaped, so the value cannot end the literal early") {
      assertTrue(valueArgOf("""a"b""") == """value: "a\"b",""")
    },

    test("a backslash is doubled") {
      assertTrue(valueArgOf("""a\b""") == """value: "a\\b",""")
    },

    test("a backslash before a quote escapes both, and neither consumes the other") {
      // The single-pass form matters here: a chain of replacements gets this
      // right only if the backslash is replaced before the quote.
      assertTrue(valueArgOf("""\"""") == """value: "\\\"",""")
    },

    test("the five control characters with short escape forms use them") {
      assertTrue(
        valueArgOf("\n") == """value: "\n",""",
        valueArgOf("\r") == """value: "\r",""",
        valueArgOf("\t") == """value: "\t",""",
        valueArgOf("\b") == """value: "\b",""",
        valueArgOf("\f") == """value: "\f","""
      )
    },

    test("every other character below U+0020 becomes a \\uXXXX escape") {
      // The null character and the unit separator have no short form, so the
      // escaper must emit the numeric form rather than passing them through.
      //
      // The expected text is assembled rather than written as a literal: Scala
      // processes a \\u sequence in source even inside a triple-quoted string,
      // so writing it out would put the raw character in the expectation and
      // compare the escaper against its own input.
      val esc = (hex: String) => "value: \"" + "\\" + "u" + hex + "\","
      assertTrue(
        valueArgOf(0x00.toChar.toString) == esc("0000"),
        valueArgOf(0x1f.toChar.toString) == esc("001f"),
        valueArgOf(0x01.toChar.toString) == esc("0001")
      )
    },

    test("no character below U+0020 survives unescaped in the rendered query") {
      val everyControlChar = (0 until 0x20).map(_.toChar).mkString
      val query = IrminQueries.setValue(path, everyControlChar, "msg", "author", BranchRef.Main)
      // The query's own layout holds newlines, so only the value argument is
      // checked rather than the whole document.
      val valueArg = valueArgOf(everyControlChar)
      assertTrue(
        query.nonEmpty,
        !valueArg.exists(_ < ' ')
      )
    },

    test("ordinary text is passed through unchanged") {
      assertTrue(valueArgOf("""{"id":"n","name":"Server Outage"}""") ==
        """value: "{\"id\":\"n\",\"name\":\"Server Outage\"}",""")
    }
  )
