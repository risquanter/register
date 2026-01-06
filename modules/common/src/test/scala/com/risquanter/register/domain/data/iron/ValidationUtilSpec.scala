package com.risquanter.register.domain.data.iron

import zio.test.*
import io.github.iltotore.iron.*

object ValidationUtilSpec extends ZIOSpecDefault {

  def spec = suite("ValidationUtil")(
    suite("refineName")(
      test("accepts valid name") {
        val result = ValidationUtil.refineName("John Doe")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("John Doe")
        )
      },
      
      test("trims whitespace") {
        val result = ValidationUtil.refineName("  Jane Smith  ")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("Jane Smith")
        )
      },
      
      test("rejects blank string") {
        val result = ValidationUtil.refineName("   ")
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message.contains("failed constraint check"))
        )
      },
      
      test("rejects empty string") {
        val result = ValidationUtil.refineName("")
        assertTrue(result.isLeft)
      },
      
      test("rejects null") {
        val result = ValidationUtil.refineName(null)
        assertTrue(result.isLeft)
      },
      
      test("rejects string over 50 chars") {
        val longName = "a" * 51
        val result = ValidationUtil.refineName(longName)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message.contains("failed constraint check"))
        )
      },
      
      test("returns descriptive error message") {
        val result = ValidationUtil.refineName("")
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => 
            errors.head.message.contains("Name") && 
            errors.head.message.contains("failed constraint check")
          )
        )
      }
    ),
    
    suite("refineEmail")(
      test("accepts valid email with @") {
        val result = ValidationUtil.refineEmail("user@example.com")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("user@example.com")
        )
      },
      
      test("trims whitespace") {
        val result = ValidationUtil.refineEmail("  test@test.com  ")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("test@test.com")
        )
      },
      
      test("rejects blank email") {
        val result = ValidationUtil.refineEmail("   ")
        assertTrue(result.isLeft)
      },
      
      test("rejects email without @") {
        val result = ValidationUtil.refineEmail("notanemail")
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => 
            errors.head.message.toLowerCase.contains("email") || 
            errors.head.message.contains("@")
          )
        )
      },
      
      test("rejects email with multiple @") {
        val result = ValidationUtil.refineEmail("user@@example.com")
        assertTrue(result.isLeft)
      },
      
      test("rejects string over 50 chars") {
        val longEmail = "a" * 45 + "@test.com"
        val result = ValidationUtil.refineEmail(longEmail)
        assertTrue(result.isLeft)
      }
    ),
    
    suite("refineUrl")(
      test("accepts valid url with domain") {
        val result = ValidationUtil.refineUrl("example.com")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("example.com")
        )
      },
      
      test("accepts url with https protocol") {
        val result = ValidationUtil.refineUrl("https://example.com")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("https://example.com")
        )
      },
      
      test("accepts url with http protocol") {
        val result = ValidationUtil.refineUrl("http://test.org")
        assertTrue(result.isRight)
      },
      
      test("trims whitespace") {
        val result = ValidationUtil.refineUrl("  test.org  ")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("test.org")
        )
      },
      
      test("rejects blank url") {
        val result = ValidationUtil.refineUrl("")
        assertTrue(result.isLeft)
      },
      
      test("rejects url without dot") {
        val result = ValidationUtil.refineUrl("notaurl")
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => 
            errors.head.message.toLowerCase.contains("url") ||
            errors.head.message.contains(".")
          )
        )
      }
    ),
    
    suite("refineNonNegativeLong")(
      test("accepts zero") {
        val result = ValidationUtil.refineNonNegativeLong(0L, "id")
        assertTrue(
          result.isRight &&
          result.contains(0L)
        )
      },
      
      test("accepts positive number") {
        val result = ValidationUtil.refineNonNegativeLong(100L, "count")
        assertTrue(
          result.isRight &&
          result.contains(100L)
        )
      },
      
      test("rejects negative number") {
        val result = ValidationUtil.refineNonNegativeLong(-1L, "amount")
        assertTrue(result.isLeft)
      },
      
      test("error message includes parameter name") {
        val result = ValidationUtil.refineNonNegativeLong(-5L, "quantity")
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => errors.head.field.contains("quantity"))
        )
      }
    ),
    
    suite("refineProbability")(
      test("accepts value between 0 and 1") {
        val result = ValidationUtil.refineProbability(0.5)
        assertTrue(
          result.isRight &&
          result.contains(0.5)
        )
      },
      
      test("rejects 0.0") {
        val result = ValidationUtil.refineProbability(0.0)
        assertTrue(result.isLeft)
      },
      
      test("rejects 1.0") {
        val result = ValidationUtil.refineProbability(1.0)
        assertTrue(result.isLeft)
      },
      
      test("rejects negative value") {
        val result = ValidationUtil.refineProbability(-0.1)
        assertTrue(result.isLeft)
      },
      
      test("rejects value over 1") {
        val result = ValidationUtil.refineProbability(1.5)
        assertTrue(result.isLeft)
      }
    ),
    
    suite("refineShortOptText")(
      test("accepts None") {
        val result = ValidationUtil.refineShortOptText(None, "field")
        assertTrue(
          result.isRight &&
          result.contains(None)
        )
      },
      
      test("accepts Some with valid text") {
        val result = ValidationUtil.refineShortOptText(Some("Valid"), "field")
        assertTrue(
          result.isRight &&
          result.exists(_.isDefined)
        )
      },
      
      test("trims whitespace in Some") {
        val result = ValidationUtil.refineShortOptText(Some("  Valid  "), "field")
        assertTrue(
          result.isRight &&
          result.exists(opt => opt.map(_ == "Valid").getOrElse(false))
        )
      },
      
      test("converts Some with blank to None") {
        val result = ValidationUtil.refineShortOptText(Some("   "), "field")
        assertTrue(
          result.isRight &&
          result.contains(None)
        )
      },
      
      test("converts Some with empty string to None") {
        val result = ValidationUtil.refineShortOptText(Some(""), "field")
        assertTrue(
          result.isRight &&
          result.contains(None)
        )
      },
      
      test("rejects Some with string over 20 chars") {
        val result = ValidationUtil.refineShortOptText(Some("a" * 21), "tag")
        assertTrue(result.isLeft)
      },
      
      test("error message includes parameter name") {
        val result = ValidationUtil.refineShortOptText(Some("a" * 25), "category")
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => errors.head.field.contains("category"))
        )
      }
    ),
    
    suite("nonEmpty helper")(
      test("trims whitespace") {
        val result = ValidationUtil.nonEmpty("  hello  ")
        assertTrue(result == "hello")
      },
      
      test("handles null as empty string") {
        val result = ValidationUtil.nonEmpty(null)
        assertTrue(result == "")
      },
      
      test("preserves non-blank content") {
        val result = ValidationUtil.nonEmpty("Valid Name")
        assertTrue(result == "Valid Name")
      }
    )
  )
}
