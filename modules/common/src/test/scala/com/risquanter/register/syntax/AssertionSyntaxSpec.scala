package com.risquanter.register.syntax

import zio.*
import zio.test.*
import zio.test.Assertion.*

/** Tests demonstrating the AssertionSyntax extension methods
  * 
  * Shows both forms:
  * 1. `.assert(predicate)` - using boolean predicates
  * 2. `.assert(assertion)` - using ZIO Test Assertions
  */
object AssertionSyntaxSpec extends ZIOSpecDefault {

  def spec = suite("AssertionSyntax")(
    suite("assert with boolean predicate")(
      test("simple equality check") {
        val program = ZIO.succeed(42)
        program.assert(_ == 42)
      },
      
      test("pattern matching") {
        val program = ZIO.succeed(Some("hello"))
        program.assert {
          case Some(value) => value == "hello"
        }
      },
      
      test("multiple conditions with &&") {
        val program = ZIO.succeed((1, "test", true))
        program.assert { case (num, str, flag) =>
          num > 0 && str.length == 4 && flag
        }
      }
    ),
    
    suite("assert with ZIO Test Assertion")(
      test("using built-in assertions") {
        val program = ZIO.succeed(List(1, 2, 3))
        program.assert(hasSize(equalTo(3)))
      },
      
      test("composing assertions") {
        val program = ZIO.succeed("hello world")
        program.assert(
          hasField("length", (s: String) => s.length, isGreaterThan(5))
        )
      }
    ),
    
    suite("practical examples")(
      test("validating service response") {
        case class User(id: Long, name: String, active: Boolean)
        val program = ZIO.succeed(User(1L, "Alice", true))
        
        program.assert { user =>
          user.id > 0 &&
          user.name.nonEmpty &&
          user.active
        }
      },
      
      test("checking option values") {
        val program = for {
          value <- ZIO.succeed(Some(42))
        } yield value
        
        program.assert {
          case Some(n) => n > 0 && n < 100
        }
      }
    )
  )
}
