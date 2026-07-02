## Step-by-Step Guide: Implementing UserId as Sum Type

### 1. Understand Current State and Requirements

First, review these key files to understand what needs to be changed:
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala` (contains WorkspaceId)
- `modules/server/src/main/scala/com/risquanter/register/auth/UserContext.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/UserContextExtractor.scala`

### 2. Create UserId Sum Type

Create or modify the UserId type in `OpaqueTypes.scala`:

```scala
// In modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
sealed trait UserId {
  def raw: UUID
}

object UserId {
  final case class Authenticated(raw: UUID) extends UserId
  final case class Anonymous(raw: UUID) extends UserId
  
  // Keep existing utility methods if needed
}
```

### 3. Update AuthorizationService Trait

Modify `AuthorizationService.scala` to enforce only authenticated users:

```scala
// In modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationService.scala
trait AuthorizationService {
  
  def check[P <: Permission](
    user:       UserId.Authenticated, // Changed from UserId
    permission: P,
    resource:   ResourceRef
  ): IO[AuthError, Checked[P]]
  
  def listAccessible(
    user:         UserId.Authenticated, // Changed from UserId
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]]
}
```

### 4. Update AuthorizationServiceNoOp

Modify `AuthorizationServiceNoOp.scala`:

```scala
// In modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationServiceNoOp.scala
object AuthorizationServiceNoOp extends AuthorizationService {
  
  def check[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[AuthError, Checked[P]] =
    ZIO.succeed(Checked[P]())
    
  def listAccessible(
    user:         UserId.Authenticated,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]] =
    ZIO.succeed(List.empty)
}
```

### 5. Update AuthorizationServiceStub

Modify `AuthorizationServiceStub.scala`:

```scala
// In modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationServiceStub.scala
class AuthorizationServiceStub(allowed: Set[(UserId.Authenticated, Permission, ResourceRef)])
    extends AuthorizationService {
  
  def check[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[AuthError, Checked[P]] =
    if allowed.contains((user, permission, resource)) then 
      ZIO.succeed(Checked[P]())
    else 
      ZIO.fail(AuthForbidden(...))
      
  def listAccessible(
    user:         UserId.Authenticated,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]] =
    // Implementation here...
}
```

### 6. Update UserContext

Modify `UserContext.scala` to use the new type:

```scala
// In modules/server/src/main/scala/com/risquanter/register/auth/UserContext.scala
final case class UserContext(
  userId: UserId,
  email: String,
  roles: Set[Role]
)

object UserContext {
  def fromClaim(claim: Option[String]): Either[String, UserContext] = {
    // Updated implementation that handles both Authenticated and Anonymous
  }
}
```

### 7. Update UserContextExtractor

Modify `UserContextExtractor.scala`:

```scala
// In modules/server/src/main/scala/com/risquanter/register/auth/UserContextExtractor.scala
trait UserContextExtractor {
  
  def requirePresent: ZIO[Scope, AuthError, UserContext] = {
    // Updated to properly extract and validate UserId.Authenticated
  }
}
```

### 8. Ensure All Call Sites Are Updated

Check all implementations that call authorization methods:
- `WorkspaceController.scala`
- Any service method that calls `check()` or `listAccessible()`
- Update callers to use `UserId.Authenticated` explicitly when needed

## Key Design Considerations:

1. **Type Safety**: Only `UserId.Authenticated` should be accepted by check operations
2. **Backwards Compatibility**: Ensure all existing code paths work with the new type system
3. **Compilation Checks**: Missing proofs at call sites must fail compilation (as per ADR-024)
4. **Security**: The PEP pattern requires that authorization decisions are made in a controlled manner

## Review Against Constraints:

This implementation aligns with:
- ADR-024: Pure PEP Pattern - no grant/revoke operations
- Security boundaries from PROMPT-CODE-QUALITY-REVIEW.md regarding credential handling
- The sum type design pattern described in the planning documents
- All methods now properly require `UserId.Authenticated` for access control operations

## Testing Approach:

1. Compile-time verification: Ensure existing code compiles with new constraints
2. Unit tests for stub implementations 
3. Integration test verification that authentication flows work correctly
4. Check that unauthorized users are handled appropriately (should fail at compile time or runtime)

Would you like me to elaborate on any of these steps, particularly around the compilation requirements or specific implementation details in particular files?