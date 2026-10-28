# Test case catalogue (read by the test-engineer for `/unit-tests`; `scripts/TestMatrix.java` uses the same keys)

A unit test suite is complete when every public method has been exercised against every case class that applies to
its parameters and its behaviour, and every case that does not apply is listed with the reason. The keys below are
what the matrix tool prints; a test "covers" a key when it feeds that kind of input and asserts the outcome.

## 1. Cases by parameter type

| Type | Keys | What the test feeds |
|---|---|---|
| `String`, `CharSequence` | `null` `empty` `blank` `single` `long` `trim` `case` `unicode` `special` `numeric` `newline` | `null`; `""`; `"   "` and `"\t"`; one character; a 10k-character value (or the documented maximum plus one); `" x "` with leading and trailing spaces; `"abc"` vs `"ABC"` vs `"aBc"`; `"Zoë 名前 🙂"`; `"' OR 1=1 --"`, `"<script>"`, `"a,b;c|d"`, `"%s %n"`; `"123"` where a word is expected; `"a\nb"` |
| `int` `long` `short` `byte` and boxed | `zero` `one` `negative` `max` `min` `boundary` (+ `null` when boxed) | `0`; `1`; `-1`; `Integer.MAX_VALUE`; `Integer.MIN_VALUE`; the documented limit, the limit minus one, the limit plus one (`maxQuantity` and `maxQuantity + 1`) |
| `double` `float` `BigDecimal` and boxed | `zero` `negative` `max` `rounding` `nan` (floating only) (+ `null` when boxed or `BigDecimal`) | `0.0`; `-0.01`; `Double.MAX_VALUE` or the largest allowed amount; `0.005` and `0.015` through the rounding mode; `NaN` and `Infinity` |
| `boolean` `Boolean` | `true` `false` (+ `null` when boxed) | both branches, each asserting a different outcome |
| `char` `Character` | `special` `unicode` | `'\0'`, `'\''`, a non-ASCII character |
| `List` `Set` `Collection` `Iterable` arrays varargs | `null` `empty` `single` `many` `duplicates` `nullelement` `order` | `null`; `List.of()`; one element; several; the same element twice; `Arrays.asList("a", null)`; two orders of the same elements when order could matter |
| `Map` | `null` `empty` `single` `many` `nullvalue` | as for collections, plus a key mapped to `null` |
| `Optional` | `empty` `present` | `Optional.empty()`; a value |
| `LocalDate` `LocalDateTime` `Instant` `ZonedDateTime` `OffsetDateTime` `Date` `Duration` `Period` | `null` `past` `future` `boundary` `zone` | `null`; yesterday; tomorrow; `1970-01-01`, `2024-02-29`, the 31st, `23:59:59.999`, a DST switch; the same instant in two zones |
| `UUID` `URI` `URL` `Path` `File` `Pattern` | `null` `malformed` `valid` | `null`; `"not-a-uuid"`, `"ht!tp://"`; a well-formed value |
| Enums | `each` `null` | every constant through the method at least once; `null` |
| Domain objects (your own types) | `null` `valid` `invalid` `nullfield` | `null`; a valid instance from the module's builder; an instance that violates its own rules; a valid instance with one `null` field |
| Money, quantities, percentages | `zero` `negative` `max` `rounding` `currency` | as numbers, plus two currencies in one operation |
| Exception parameters (`@ExceptionHandler`, error mappers) | `message` `nullmessage` | the mapped status and body with a message; the same exception constructed without a message (`new IllegalArgumentException()`), which breaks `Map.of("error", e.getMessage())` |

## 2. Cases by behaviour (every public method, independent of its parameters)

| Key | What the test proves |
|---|---|
| `happy` | the documented outcome for valid input, asserted on the return value or the state change, not on "no exception" |
| `exception` | each `throw` site reached: exception type and message, and that nothing was changed before it |
| `idempotent` | calling twice is either the same result or the documented second-call behaviour (cancel a cancelled order) |
| `state` | each state transition the class defines, including the forbidden ones |
| `collaborator` | the collaborator throws, returns empty, or returns `null`: the method's documented reaction |
| `concurrency` | only when the class is documented thread-safe: two interleaved calls through a `CountDownLatch`, deterministic |

## 3. What does not need a test (say so instead of writing one)

- Getters, setters, records' accessors, `equals`/`hashCode`/`toString` unless hand-written with logic.
- Private methods: tested through the public method that uses them. If a private method needs its own test, that is a
  design finding for `issues.md`, not a reason to use reflection.
- A case the type system already excludes (`null` for a primitive, `empty` for an enum).
- A case an earlier layer already rejects **and** a test of that layer exists: name the test.

## 4. How to write them

- One `@ParameterizedTest` per key group with `@NullSource`, `@EmptySource`, `@NullAndEmptySource`, `@ValueSource`,
  `@CsvSource` or `@MethodSource`; one `@Test` per behaviour key. The method name carries the key:
  `cancel_nullId_throwsIllegalArgument`, `place_lineQuantity_boundary`.
- Every test asserts a value, a state, or an exception with its message. "Does not throw" is not an assertion.
- For each test, be able to say which one-line change in the production code would make it fail. If none, delete it.
- A case that does not apply is one line in the test class, in this exact form so the matrix tool can read it:
  `// not applicable: <method> <key> [<key>...] - <reason>` (inside a block comment: ` * not applicable: get empty newline - the route does not match`).
  The tool then reports those keys as `n/a` instead of `missing`.
- The matrix tool marks a key `+` when a test that calls the method also mentions that case; that is a hint, not proof.
  Confirm by reading only those tests. Tests of an `@ExceptionHandler` are found through the exception type they throw.
