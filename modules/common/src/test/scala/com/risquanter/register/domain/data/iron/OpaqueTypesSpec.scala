package com.risquanter.register.domain.data.iron

import zio.test.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.MaxLength

object OpaqueTypesSpec extends ZIOSpecDefault {

  def spec = suite("OpaqueTypes")(
    suite("SafeShortStr type alias")(
      test("accepts valid non-blank string under 50 chars") {
        val validString = "Valid Name"
        val result: Either[String, SafeShortStr] = validString.refineEither
        assertTrue(result.isRight)
      },
      
      test("rejects blank string") {
        val blankString = "   "
        val result: Either[String, SafeShortStr] = blankString.refineEither
        assertTrue(result.isLeft)
      },
      
      test("rejects string over 50 chars") {
        val longString = "a" * 51
        val result: Either[String, SafeShortStr] = longString.refineEither
        assertTrue(result.isLeft)
      },
      
      test("can be used as a SafeShortStr value") {
        val refined: SafeShortStr = "Valid Name".refineUnsafe
        assertTrue(refined == "Valid Name")
      }
    ),
    
    suite("SafeExtraShortStr type alias")(
      test("accepts string under 20 chars") {
        val validString = "Short"
        val result: Either[String, SafeExtraShortStr] = validString.refineEither
        assertTrue(result.isRight)
      },
      
      test("rejects string over 20 chars") {
        val longString = "a" * 21
        val result: Either[String, SafeExtraShortStr] = longString.refineEither
        assertTrue(result.isLeft)
      }
    ),
    
    suite("NonNegativeLong type alias")(
      test("accepts zero") {
        val result: Either[String, NonNegativeLong] = 0L.refineEither
        assertTrue(result.isRight)
      },
      
      test("accepts positive number") {
        val result: Either[String, NonNegativeLong] = 100L.refineEither
        assertTrue(result.isRight)
      },
      
      test("rejects negative number") {
        val result: Either[String, NonNegativeLong] = (-1L).refineEither
        assertTrue(result.isLeft)
      },
      
      test("can be used as NonNegativeLong value") {
        val id: NonNegativeLong = 42L.refineUnsafe
        assertTrue(id == 42L)
      }
    ),
    
    suite("Probability type alias")(
      test("accepts value between 0 and 1") {
        val result: Either[String, Probability] = 0.5.refineEither
        assertTrue(result.isRight)
      },
      
      test("rejects 0.0") {
        val result: Either[String, Probability] = 0.0.refineEither
        assertTrue(result.isLeft)
      },
      
      test("rejects 1.0") {
        val result: Either[String, Probability] = 1.0.refineEither
        assertTrue(result.isLeft)
      },
      
      test("can be used as Probability value") {
        val prob: Probability = 0.75.refineUnsafe
        assertTrue(prob == 0.75)
      }
    ),
    
    suite("SafeName opaque type")(
      test("can be created from SafeShortStr") {
        val validStr: SafeShortStr = "John Doe".refineUnsafe
        val name: SafeName.SafeName = SafeName.SafeName(validStr)
        assertTrue(name.value == "John Doe")
      },
      
      test("value method returns underlying string") {
        val name: SafeName.SafeName = SafeName.SafeName("Jane Smith".refineUnsafe)
        val extracted: String = name.value
        assertTrue(extracted == "Jane Smith")
      },
      
      test("unapply allows pattern matching") {
        val name: SafeName.SafeName = SafeName.SafeName("Alice".refineUnsafe)
        
        val result = name match {
          case SafeName.SafeName(value) => value
        }
        
        assertTrue(result == "Alice")
      },
      
      test("is distinct from Email type at compile time") {
        val name: SafeName.SafeName = SafeName.SafeName("John".refineUnsafe)
        val email: Email.Email = Email.Email("john@test.com".refineUnsafe)
        
        // If we tried: val x: SafeName.SafeName = email
        // Compiler would reject it because they're different opaque types
        assertTrue(
          name.value == "John" &&
          email.value == "john@test.com"
        )
      },
      
      test("fromString accepts valid name") {
        val result = SafeName.fromString("Valid Name")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("Valid Name")
        )
      },
      
      test("fromString rejects invalid name") {
        val result = SafeName.fromString("")
        assertTrue(result.isLeft)
      }
    ),
    
    suite("Email opaque type")(
      test("can be created from SafeShortStr") {
        val email: Email.Email = Email.Email("test@example.com".refineUnsafe)
        assertTrue(email.value == "test@example.com")
      },
      
      test("unapply works for Email") {
        val email: Email.Email = Email.Email("user@test.com".refineUnsafe)
        
        val result = email match {
          case Email.Email(value) => value
        }
        
        assertTrue(result == "user@test.com")
      },
      
      test("fromString accepts valid email") {
        val result = Email.fromString("user@test.com")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("user@test.com")
        )
      },
      
      test("fromString rejects invalid email") {
        val result = Email.fromString("notanemail")
        assertTrue(result.isLeft)
      }
    ),
    
    suite("Url opaque type")(
      test("can be created from SafeShortStr") {
        val url: Url.Url = Url.Url("example.com".refineUnsafe)
        assertTrue(url.value == "example.com")
      },
      
      test("unapply works for Url") {
        val url: Url.Url = Url.Url("test.org".refineUnsafe)
        
        val result = url match {
          case Url.Url(value) => value
        }
        
        assertTrue(result == "test.org")
      },
      
      test("fromString accepts valid url") {
        val result = Url.fromString("test.org")
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("test.org")
        )
      },
      
      test("fromString rejects invalid url") {
        val result = Url.fromString("notaurl")
        assertTrue(result.isLeft)
      }
    )
  )
}
