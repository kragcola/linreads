---
name: tdd
description: Use when implementing features or fixing bugs with test-driven development. Enforces RED-GREEN-REFACTOR. Supports Jest/Vitest (Web), JUnit/Kotlin (Android), ohosTest (HarmonyOS).
---

# Test-Driven Development

**Core constraint:** Test Writer never sees implementation. Implementer never sees the spec. This prevents leaking intent into test design.

## Cycle

```
RED   → Write a failing test that describes ONE behavior
GREEN → Write the MINIMUM code to make it pass (nothing more)
REFACTOR → Improve without changing behavior; tests must stay green
```

**Never skip RED.** A test that passes immediately means either the behavior already exists (skip the slice) or the test is wrong.

## Vertical Slicing — ordered inside-out

1. **Domain/pure logic** — no dependencies, no mocks
2. **Service layer** — using real domain objects
3. **Application/use case** — in-memory fakes for ports
4. **Infrastructure/adapter** — repos, network, platform APIs

Start inner, build outward. Never mock domain objects — construct real instances.

## Per-platform test commands

| Platform | Single test | All tests |
|----------|------------|-----------|
| Web | `npx vitest run src/__tests__/foo.test.ts` | `npx vitest run` |
| Android | `./gradlew test --tests "dev.readflow.FooTest"` | `./gradlew test` |
| HarmonyOS | DevEco → Run → ohosTest | DevEco → Run All |

## Rules

- **One slice at a time.** Don't write all tests upfront.
- **Failing test = specific assertion failure**, not import/compile error. Fix imports first, then confirm assertion fails.
- **If test passes immediately:** behavior already exists — skip slice, move on.
- **5 failed GREEN attempts:** stop, show user the error, ask for direction.
- **Regression after GREEN:** fix regression before moving to REFACTOR.

## Anti-patterns

- ❌ Modifying a test to make it pass (change implementation, not tests)
- ❌ Writing implementation before the test exists
- ❌ Mocking domain objects (construct real instances)
- ❌ Testing implementation details (test behavior and output)
- ❌ `assert True` / tautological assertions

## Readflow-specific examples

```kotlin
// Android — domain slice (pure, no mocks)
@Test fun `reading position advances by one page on next()`() {
    val pos = ReadingPosition(bookId = "b1", cfi = "epubcfi(/6/4!/4/2/2)", pageIndex = 3)
    val next = pos.advance()
    assertThat(next.pageIndex).isEqualTo(4)
}

// Web — service slice
it('returns cached metadata when Calibre unreachable', async () => {
  const cache = new MetadataCache()
  cache.set(42, mockBook)
  const svc = new BookService(unreachableClient, cache)
  expect(await svc.getMeta(42)).toEqual(mockBook)
})
```
