# The Irmin service layers close their resource scope and then keep using the service

## What the code does

`Application.scala` chooses between the in-memory and the Irmin-backed
implementation of four services. Each choice has the same shape. Taking the
scenario-merge service as the example:

```scala
ZIO.scoped(irminScenarioMergeServiceLayer.build.map(_.get[ScenarioMergeService]))
```

Read left to right, that opens a `Scope`, builds the layer inside it, pulls the
service value out of the resulting environment, and then closes the scope. The
service value is returned and used for the rest of the process's life, after the
scope that owned its resources has already been released.

The same shape appears at four call sites: `chooseRepo`, `chooseScenarioService`,
`chooseTreeHistoryService` and `chooseScenarioMergeService`.

## Why it does not currently break

The resource the layer acquires is an HTTP backend, obtained inside
`IrminClientLive.layer` from `HttpClientZioBackend.scoped()`. Two details of
that library's construction make the release harmless:

- the release action is `close().ignore`, so a failure to close is swallowed;
- the backend is built with `closeClient = false`, which the library sets
  deliberately so that closing the wrapper does not shut down the executor it
  borrows.

Together those mean `close()` on this configuration does not tear down the
underlying `java.net.http.HttpClient` or its connection handling. Closing the
scope is, for this configuration, a no-op.

## Why it is still worth investigating

The type `ZIO[Scope, _, _]` is an assertion that the value must not outlive its
scope. This code closes the scope immediately and then uses the value anyway, so
it contradicts its own type. Nothing in this repository establishes that doing
so is safe. The safety comes entirely from the internal configuration of a
third-party library, which this code does not assert, pin, or test against.

Two changes would turn this into a real use-after-release failure, and neither
would be caught by anything here:

- an `sttp` upgrade that changes what `close()` does for this configuration;
- a change to how `IrminClientLive.layer` constructs its backend, for example
  one that stops passing `closeClient = false`.

No integration specification exercises these four functions. Each integration
test builds its own wiring, so the composition in `Application.scala` is not
covered by any test.

## What to decide

Three shapes are worth comparing before changing anything:

1. Leave it, and add a test that pins the property the code depends on — that
   the service still works after its build scope has closed.
2. Hold the scope open for the application's lifetime, so the type's assertion
   is true, by building the layer as part of the server's own scoped run rather
   than extracting a value from it.
3. Make the layer carry no resource at all, by passing an already-constructed
   backend in, so there is nothing for a scope to release.

All four call sites change together under 2 or 3; changing one and leaving the
others is the outcome to avoid.
