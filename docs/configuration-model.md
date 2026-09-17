# The configuration model

This is the **reference for the semantics** of the model: what a value is, how a written form becomes a
typed value, what a rule may say, how a source states something about an entry, and what the several
ways of saying "nothing" actually mean. The README describes the same model as an overview and a
guarantee set; where a distinction here is only named there, the link is given. The Javadoc of the
module that owns a type or a method is the contract for the API itself.

Nothing in this document describes classes: it describes behaviour, so it survives a refactor. Notation
appears only where the notation *is* the contract, because JUCM's notation (values, rules, markers) is
what an application and its users write.

Terminology is the README's glossary (section 11). Section numbers below refer to the README.

---

## 1. Structure — a tree of values under addresses

The configuration is one tree of values, addressed by **canonical paths**: segments joined by dots, so a
dot cannot occur inside a segment. `server.port` is an address; a source delivers a sparse set of
entries under addresses; a lookup walks the chain of layers from the newest node down to the root
(README, section 3.3).

**Address grammar.**

| rule | effect |
|---|---|
| segments separated by `.` | `server.port` is two segments; an empty segment (`a..b`, `a.b.`) is rejected |
| `__` in a key | rejected: the **hierarchy** separator belongs to the engine (that is how ENV spells a dot) |
| `___` in a key | rejected: the **marker** separator belongs to the engine (`___TYPE`, `___TCONF`, `___CONST`) |
| a single `_` | an ordinary character, and often part of a name (`max_connections`, `--server-max_connections`) |
| lower-case keys | a **recommendation**, not an enforced rule |

**How each source spells an address.** The address space is one; the spellings differ:

| source | spelling | example | note |
|---|---|---|---|
| YAML | a nested mapping; the key text is taken as written | `server:`\n`  port: 8080` | keys are **case-sensitive** here: `Port` is a different address than `port` |
| ENV | a prefix, then `__` between segments | `APP__SERVER__PORT` | segments are lower-cased; `APP__MAX_CONNECTIONS` becomes `max_connections`; a variable without the prefix is ignored |
| CLI | the canonical path with dots replaced by dashes | `--server-port` | an underscore stays: `server.max_connections` → `--server-max_connections` |

Case-sensitivity is thus **per source**, and the normalisation happens in the adapter, not in the
schema: the address an entry ends up under is the address a declaration must use.

How a YAML node is read follows from one rule — **the declaration under the address decides**:

* a **mapping** under a declared field is the **value of that field**: `m: {a: 1}` states the value of `m`
  (one piece, exactly as `APP__M={a: 1}` does), and the pairs inside it are the content of that value, not
  addresses — a marker inside them is rejected (section 5), and an empty mapping is an EMPTY VALUE, that is, an
  emptiness error (section 9);
* a **mapping** under an address outside the schema still never creates an entry for its key: entries come from the pairs
  inside it, so `server:` produces `server.host`, and the address `server` itself is not an entry (see section 8);
* a **sequence** value does create an entry (`ports: [1, 2]` is a value under `ports`), and its elements
  are addressable afterwards (`ports.1`) — a list is a value under its key for every type of field, so `seq` needs
  no declaration to be read;
* a duplicate key, a non-scalar key, and a document that is not a mapping are all reported as source
  errors while the file is read — there is nothing to choose from and nothing to guess.

A dotted key written in one line (`a.b: 5`) and the same key written as a nested pair (`a:`\n`  b: 5`)
produce the same canonical address, and addresses are compared by their canonical text. Write hierarchy
as nesting; a dot inside a key name is only accidentally equivalent.

---

## 2. Values and types

Values are a **closed set**, not an anything-goes map. Two families live in one dictionary:

```text
value types (the YAML dictionary)   str · int · float · bool · timestamp · seq · map · binary
cast targets identified by JUCM     duration · uri
```

The first eight are what may stand in the tree. The last two are **targets for declared fields**: a
value of such a field is read and checked through the type's own notation, but what travels in the tree
is a string in canonical form — which is why the value dictionary does not grow.

| type | what it is | shape of a legal value | what it displays as |
|---|---|---|---|
| `str` | text | anything textual; **no automatic trimming** (a default `trim` would change data in silence; trimming is an explicit transformer) | as written |
| `int` | 64-bit signed integer | an integer notation only: `8080`, `010` (8), `0x1F` (31), `1_000` | `8080` |
| `float` | floating point | a notation with a decimal point or an exponent (`1.0`, `1e6`); **no widening or narrowing**: `1` on a `float` field is an error, `1.0` on an `int` field is an error | `1.0` |
| `bool` | boolean | exactly `true` or `false`, case-insensitive; `yes`, `on`, `1` are **rejected** rather than read as `false` | `true` |
| `timestamp` | an exact moment **with a zone** | `2026-01-01T12:00:00Z`, or the same moment with an explicit offset (`+02:00`); a bare date or a zone-less date-time is rejected unless the declaration states the pattern | `2026-01-01T12:00:00Z` |
| `seq` | an ordered sequence | a non-empty sequence; `[]` is an empty value and therefore an error | `[1, 2]` |
| `map` | a map from string keys to values | a non-empty map; `{}` is an empty value and therefore an error | `{a: 1}` |
| `binary` | an encoded blob | a URI with a scheme; only `base64://…` is decoded today (`file://`/`https://` need a loader) | `binary[N B]`, never the content |
| `duration` | a cast target | `30s`, `5m`, `1h30m`, `500ms`, `2 hours`; a unit must end a word, so `5 months` is rejected | canonical, one unit: `90m` |
| `uri` | a cast target | an address **with a scheme**; the resource is not read | the same text, checked |

**A type carries its own rules** — a default notation (a transformer name) and, where it makes sense, a
default shape rule. `uri` must have a scheme (`~`^[a-z][a-z0-9+.\-]*://.*``); `duration` has a notation
rule but deliberately **no** shape rule, because a rule on the canonical form could never fail — and a
rule that never fails is a lie in the report. That is also why `int`/`float`/`timestamp` are separate
types: a rule compares a **typed** value (section 4), so the type of the value is the only type a rule
may use.

**Which rules belong to whom.** A field declaration adds its own rules; the type's rule is applied
**alongside** them and always, not "unless the field has its own". A rule from a source replaces the
field's rules and cannot replace the type's (section 5, README invariant 9).

---

## 3. Notation vs type

A value is **written** in some notation and **means** something of some type. The written form is the
source's business; the meaning is the declaration's. Two steps are easy to conflate:

```text
  NORMALISATION                                        CAST
  bring the written form to the canonical form          require the canonical form of the
  of that type (a transformer; `010` → 8)               DECLARED type (no widening, no guessing)
  failure: transform-failed (20)                        failure: cast-failed (41, 33 …)
```

**Consequences, with the deciding cases:**

```text
  010      an integer: eight. The same text is eight in a file, in an environment variable and on the
           command line, because argument parsers are not allowed to type values on their own.
  0x1F     thirty-one (YAML notations are the notation; underscore grouping `1_000` works too).
  1e6      a floating-point notation: on an `int` field it is cast-failed with "the notation read this
           as a floating-point number (`1000000.0`), but the field is `int`".
  1,000    ambiguous by construction — one thousand in one locale, one-point-zero in another — so it is
           REJECTED with the locale named as the fix. With the declaration stating a locale (the
           `number` transformer configured `en_US`) it is one thousand; the grouping must stand in the
           right places, so `1,5` in `en_US` is not silently fifteen.
  0673     is 443 (octal). A value that is another value's expression sees the NOTATION as written,
           because interpolation runs before the transformation and the cast — that is the contract, and
           it is why a `uri` may show `:0673` while the port field shows `443`.
  ""       an empty value, not an integer zero and not "no value" (section 6).
  " x "    is not trimmed on a `str`: spaces can be content.
  yes      is not a boolean.
```

**Comparisons run on a known type.** A rule compares either the notation (`=010` does not match `8`) or
the typed value (numbers numerically, moments chronologically). There is no third option: an ordering
rule on a value whose order is not defined is a **model error**, not a text comparison.

**Emptiness is checked before the cast**, so an empty field is reported as emptiness and not as
"expected type `bool`".

---

## 4. The notation of rules (constraints)

A constraint is one form only: **a chain of conditions in a single text**, written the same way in Java,
in the environment, on the command line and in a YAML comment. The chain is parsed where the statement
is built, so a broken chain is a model or source error with a position — never a surprise at the first
value.

**Evaluation is strictly from left to right, with no precedence.** An operator is both the separator and
the decision about the logic, so `'<2&>0' | '!=7'` means `((<2 & >0) | !=7)`, and **every element after
the first must start with `&` or `|`**. There are no brackets, no arithmetic and no nesting.

| condition | reads | meaning |
|---|---|---|
| `<20` `<=20` `>1` `>=1` | the **typed** value | order on a number (never the notation) |
| `<'2030-01-01T00:00:00Z'>` | the **typed** value | order on a moment; the operand stands in single quotes, in canonical form — a rule does not know the field's notation |
| `=x` `!=x` | the **notation** | equality of the written form; `=a b` keeps its spaces, `=010` does not match `8` |
| `=[1,2,3]` `!=[1,2,3]` | the value is a **list** of that shape | compared in canonical form, so `=[1,2,3]` matches `[1, 2, 3]`, and `=[010]` does not match `[8]` |
| `={a:1}` `!={a:1}` | the value is a **map** of that shape | same rule: canonical forms on both sides |
| `in[1,3,5]` `!in[1,3,5]` | the notation | one of the list / none of the list |
| `c[1,3,5]` `!c[1,3,5]` | a sequence or a map | the value **contains** those elements (a sequence) or those **keys** (a map); it may contain more |
| `c{a:1}` `!c{a:1}` | a map | the map contains those **entries** (key and value), not necessarily only those |
| `keys[1,3]` | a map | the keys are a **subset** of the list |
| `values[1,3]` | a map | every value of the map is in the list |
| `~`body`` `!~`body`` | the notation | a regular expression; the body in backticks matches the **whole** value, so `~`^h$`` and `~`h`` differ |

```text
  on a map, `[…]` speaks about KEYS and `{…}` speaks about PAIRS:
      c[a]    — the map has the key `a`          c{a: 1} — the map has the entry a: 1
  a list literal is not a condition by itself: write `in[…]`, `c[…]`, `c{…}` or `=[…]`.
```

**Operands.** `\<char>` protects one character inside an operand (`'=a\&b'` is a single comparison); in a
list `\,` protects a comma; in a map body `\:` protects a colon; inside backticks only the backtick
itself is escaped, so `\d` and `\.` reach the regular-expression engine unchanged. An operand may be a
**parameter** (`<${min_port}`, `=${limit}`), resolved by the same resolver that values go through: an
unresolved reference is a resolution error, and a parameter whose **type** is not the field's type is a
`rule-mismatch` — a rule compares values of one type, never their text, so a `str` holding `9000` cannot
feed an `int` comparison. A parameter inside a list (`in[${allowed}]`) brings the list's **elements**
instead, and it is the elements that have to be of that type.

Where a parameter gets the two things it needs is worth stating plainly, because the answer differs: its **TYPE**
comes from the declaration in code, while its **VALUE** may arrive from any medium — the YAML file, ENV, or a CLI
argument (`--min 9000`, `--min=9000`). Declare the parameter in the model (a default is enough) and feed it from
wherever it is convenient: the type stays in the code, the value travels. A parameter naming a key **outside** the
schema has no declaration behind it, so its type comes from the statement its source made — and a YAML scalar
states `str`, which makes an `int` comparison fed from such a key a `rule-mismatch`. Two ways out: declare the key
in code, or state its type in a carrier that can (`___TYPE=int` in ENV or CLI).

**Shape versus order.** A condition reads a value in one of two ways, and the strictest way in the chain
decides what the rule needs: shape (equality, patterns, lists) works for every type; order works only
for a number or a moment. Order on text, on `duration` or on `uri` is a **model error** (`rule-mismatch`,
category 5), because after the cast those are strings and `"10s" < "9s"` would invert the truth.

**A rule belongs to a TYPE — the type of the field, taken from the declaration.** The type is checked
against every operand in the moment the rule is processed for an entry: an operand that can never be a
value of that type has nothing to compare, so the rule is refused with the reason (`rule-mismatch`,
category 5) instead of being compared as text — `in[http,https]` on an `int` field, an order condition on a
`str` field, a `str` parameter where an `int` is compared. A list or a map obliges its **elements**, never
the collection itself: a list compared with a single value is nonsense. Operands are written in the
canonical form of the type (`int` → a whole number, `bool` → `true`/`false`, `timestamp` → a moment in the
canonical form), because a rule is written by a human and not by the cast.

---

## 5. Markers — what a source may state about an entry

A source may need to say three things about an entry: the **type**, the **notation configuration**, the
**rule**. Each kind of source spells the same three facts its own way, and the fact travels with the
value as a **statement**.

| | type | notation configuration | rule |
|---|---|---|---|
| YAML | from the form of the node (`str`/`seq`/`map`) | `# TCONF(…)` — same line as the value | `# CONST(…)` — same line as the value |
| ENV | `NAME___TYPE=str` | `NAME___TCONF=…` | `NAME___CONST='…'` |
| CLI | `--name--type str` (or `=str`) | `--name--tconf …` | `--name--const '…'` |

**The type of a key outside the schema is the one the statement brings — and `str` when it brings none.** The
schema is CODE and it is complete, so a key the code does not declare carries its type with the statement:
`___TYPE` in ENV and CLI, the form of the node in YAML, and **`str`** when nothing says otherwise — a value from
a source arrives as text. There is no state "a statement without a type": a `CONST(…)` is compared against that
type and a `TCONF(…)` is looked up for it, so a notation on a type that has none (`str`) is an error rather than
a silent skip. A statement that says nothing at all declares nothing and leaves the value as it came.
For a key **inside** the schema the type comes from the declaration, so a rule from a source always applies —
and replaces the rules stated in code.

**YAML.** A marker is a **section in a comment on the same line as the value of a pair**, and it counts
only when the comment **starts** with it; after prose a section is prose. At most one `TCONF(…)` and one
`CONST(…)` per comment, in any order; the payload ends at the first unescaped `)` outside backticks
(`\)` protects the bracket, and a `)` inside backticks is free, so regex groups work). A marker is
otherwise misplaced and **rejected**, because a marker where it cannot be read would be a rule that
silently does not work:

```text
  REJECTED   a comment on its own line — a file header, a line above a key, a line between pairs
  REJECTED   a marker on a pair whose value is a MAP that stays a set of ADDRESSES — there is no entry for
             it (rules and notation for such a map come from code, or from a field whose value is a
             collection written in one line). Under a declared field the mapping IS the value of
             that address, so a marker there is allowed, exactly as next to a list — but a marker INSIDE
             that value is rejected, because the pairs in it are content, not entries
  REJECTED   a marker on a LIST ELEMENT — notation and rules concern the whole value of a field
  REJECTED   a marker on both the key and the value of one pair — there is exactly one place for it
  REJECTED   a section that is not closed, is empty, or appears twice in one comment
```

**ENV.** A marker is a **suffix of the variable name**, and its payload is the variable's value. The cuts
are ordered by the contract, because `___` contains `__`: the prefix first, then a trailing marker, and
the split on `__` last — so `APP__SERVER__PORT___TYPE=seq` is the type of `server.port` and never a key
named `port__type`. A marker without a value, or without the variable it refers to, is a source error.
The three axes compose: `___TYPE` from one variable, `___TCONF` and `___CONST` from others, all about
the same entry.

**CLI.** A marker is its own option with the marker as a suffix (`--x--type`, `--x--tconf`, `--x--const`),
written with a value in the next argument or after `=`. A marker without its target option is a parse
error. On a shell, quote an operand containing `&` or `|`, which is also why the rule chain travels as
one quoted argument.

**What a statement can and cannot do.**

* A source **never states a transformer name**. The source says *how to read* (a notation
  configuration); the name comes from the declaration or from the type — a name points at an
  implementation in the application's registry.
* On a field **declared in code**, the declaration wins on type, and a marker cannot retype it. A rule
  from the source, however, **replaces** the field's rules — and the type's rule stays.
* On a key **outside the schema**, the type comes with the statement — and is `str` when the statement brings
  none. The statement is therefore always read against a type: a rule is compared, a notation is looked up.
  No carrier refuses a statement for lacking a type; a statement a type cannot use (a notation for `str`) is
  an error, reported wherever it arose.
* The statement is part of what a patch carries and part of patch equality, because `___TYPE`/`___TCONF`
  change the **effect** of a value, while the origin does not.

---

## 6. Defaults, absence, emptiness, `reset`, "delivered nothing", `dropped`

This is where most mistakes happen, so the six facts are separated explicitly. "Read" below means a
lookup of the address along the chain.

| fact | meaning | produced by | a read | a verdict |
|---|---|---|---|---|
| **default value** | a value the declaration supplies; it is the **data source of the root layer** | the declaration; materialised into the root layer | finds it when no layer above states one | none — it is a value like any other; an *empty collection* as a default is a **model error** at declaration time |
| **absent** | no layer has a value for a declared field | nobody — it is the absence of all the others | returns "no value in any layer", with the application as the origin; never `null` | `absent` (category 4, decade of the declaration) for a **required** field; a **valid state** for an optional one |
| **empty** | a value that **exists** and is empty: `""`, `[]`, `{}`, **written on purpose** | a source that spelled it out — a key with no value in YAML (`host:`), a key stated empty (`x: ""`), an explicit empty argument (`--host ""`), an empty collection. **Not** an empty environment variable: that one brings in nothing | **stops** at that layer; it does not fall through | `null-value` (category 0), with a message that names the shape ("empty list — … provide elements"). It is an error **wherever it stands**, inside a collection too — and then the NESTED address is named (`ports.0`) |
| **`reset`** | a layer takes off **its own** override | a patch; the configurator | skips exactly the layer directly below and continues below that | none — `reset` is lookup semantics, not a value |
| **"delivered nothing"** | the **adapter's own statement** that it knew of the value and could not obtain it; also the seam where a loader's failure reason travels | an adapter that says so explicitly (`ConfigPatch.nullDelivered`). The shipped adapters never do: ENV ignores a variable left without a value, a bare option — declared or not — is a usage error (`125`), a missing YAML key states no address | **stops** at that layer, like an empty value | `null-value` — empty means an error, whichever way it arrived |
| **`dropped`** | an expression produced nothing: `${?x}` with no target, or a collection whose **every** member vanished (a list's elements, a map's entries). `??` does **not** lead here: the right side, once taken, must deliver a value — otherwise it is a `rule-mismatch` | a source that wrote such an expression | the lookup continues below, as if that layer had not mentioned the address; a **value below answers** | with nothing below, the field is **`ABSENT`** — never `null-value`: `0` for an optional field, `14` for a required one |

Notes that the table cannot carry:

* `reset` cannot mask a lower layer's value in general — but the layer it skips is the one *directly*
  below, and that layer may be the materialised root. A `reset` whose neighbour below is the root
  therefore takes off the declaration's default as well, and the read reports absence (or, for a
  required field, an error). In the configurator this is the intended meaning of "undo my edit".
* required and default are independent: required **with** a default answers with the default when nobody
  states anything; required without one makes absence an error; optional without one makes absence a
  valid state. An **optional collection that is never stated is not empty — it is uninitialised** (`ABSENT`),
  and that is exactly what optional is for; emptiness is the thing that must be written out, and that is why
  it is an error.
* a layer that states **nothing at all** (an empty patch) does not create a layer in the chain — it is
  allowed and changes nothing.
* `null` does not exist as a value anywhere in the model. There are three ways of saying nothing, and they
  are different facts: a source that has nothing to say **omits the address** (invisible — a lower layer
  answers); an expression that yielded nothing (`dropped`) contributes nothing and the read descends the
  same way; and an adapter that **states "delivered nothing"** is an error in its own layer.
* a **member** that may be missing does not make the whole collection nothing: it vanishes, and the value keeps
  the members that are there. In a **list** the addresses of the neighbours stay what they were (`ports.2`
  remains `ports.2` after `ports.1` vanished), and a reference to a vanished address is `unknown-path`, never
  the neighbour's value. In a **map** the entry simply goes away. Only when **every** member vanished does the
  whole value become `dropped` — for a map as much as for a list; an explicit `[]` or `{}` is emptiness
  (`null-value`), because that is a statement, not "nothing was said".
* after the flow, an entry's origin is the layer that **won the address**. When that layer's expression
  contributed nothing and a lower layer answered, the winning layer's entry carries the resolved value —
  so a report may name a layer whose text was not the value's source. That is materialisation (README,
  section 3.6 and section 10, item 12), not a second origin: before the flow, the same chain shows what
  each layer really stated.
* an address that has **no entry of its own** — an element of a collection (`ports.1`), a nested value
  (`server.tls.ciphers.0`) — is reported with the source of the **nearest address that has one**: it lives inside
  a value some layer stated, and that layer is where it came from. Without that walk a problem inside a file's
  value was reported as `CUSTOM:application code`, with the application's exit-code decade instead of the file's.

---

## 7. Keys outside the schema (auxiliary)

A key outside the schema is **legal**, needs no declaration and is kept in the form the source gave it.
It is not an error and it is not a free-for-all:

* **Composability.** Values may refer to auxiliary keys (`${default_port}`), also through `??`, and a
  reference chain through them resolves like any other. An operand of `??` must fit the declared type of the
  value: a literal states its type by its form (`9999` is `int`, `"9999"` is `str`), an address takes it from its
  declaration — and for an auxiliary from its statement, `str` when it states nothing.
* **Reachability is static.** It is computed from the fields of the schema over references, transitively
  — never from what happened to be read at runtime — so the answer does not depend on which branch of
  `??` fired.
* **A reference to a target that exists nowhere** is `unresolved-reference` (category 2).
* **An auxiliary key nothing leads to** is reported as `unused-auxiliary` (category 3) — a typo
  detector. It fires when the verdict is asked for the **chain of layers**, before the symbols are
  resolved and the raw notation is gone. In the standard flow the report is asked for a chain that has
  already been through the flow, so the check does not fire and a typo'd *option* **with a value** does not
  fail the run: an unknown option carrying a value stays an auxiliary key and the run exits `0`. A
  **bare** option token — `--prot` with nothing after it — never becomes a key at all: it is a usage
  error (`125`) for **every** option name, declared or not, because someone meant to set a parameter and
  set nothing. The boundary is the value, not the name. An auxiliary entry that carries a
  statement which produced a problem is reported as that problem instead — one mistake, one message.
* **Auxiliary entries are not an API**: an application reads what it declared. Declare what you intend to
  read.
* A key outside the schema is the one place where a **statement from a source** is meant to carry the
  type, because there is no declaration to type it (section 5).

---

## 8. Hierarchy and merging

**There is no object merging, and there never was.** A layer states **values under addresses**; it does
not merge a document. Everything that looks like a merge follows from that one rule:

| situation | what happens |
|---|---|
| a lower layer states `server.host`, a higher one `server.port` | both survive: deep merging "by construction", at leaf addresses — the address is the unit of precedence |
| a sequence | replaced **whole**; no element-wise merge and no `+=` |
| a whole object stored under one key | turns that key into a **leaf** (a value, not a set of addresses) — that is how a `map`-typed field is given a value by a patch, by an ENV flow literal, or by a mapping in a file (where the DECLARATION under the address decides the reading — section 1) |
| a mapping in YAML | under a declared field it becomes the **value** of that address; under an address outside the schema it stays **addresses**: `server:` produces `server.host` (see section 9) |
| a sequence in YAML | becomes a **value** under its key (in any style, block or flow) |
| a `reset` in a higher layer | does not mask anything below it — it only takes off its own override (section 6) |
| the same file read twice (file, then overlay) | two layers in order, so the overlay's addresses win one by one; the file's other addresses stay |

Precedence is therefore **per address**, and the order of layers is the application's contract — a
library-side convention would be a lie about who wins (README, section 5, rules 1–2, and section 9 on
the toolbox's file → overlay → ENV → CLI order with window edits on top).

---

## 9. Behaviour worth knowing (verified against this version)

These are consequences of the rules above that are easy to read past. They are stated here because the
README leaves them implicit; each was checked by running the library.

1. **An empty mapping under a DECLARED field is emptiness, not absence.** `m: {}` states an EMPTY VALUE of that
   field, so a map field fails as `empty map` (`20` in a file) exactly as `features: []` fails as an
   empty list — "the field may have no elements" is expressed by making the field **optional**: a field with no
   entry at all is `ABSENT` (a valid state for an optional field, `14` for a required one). A mapping under an
   address outside the schema still produces no entry for its key, so nothing is stated there.
2. **A declared field IS filled from a YAML mapping.** The declaration under the address decides how a
   mapping is read: `m: {a: 1}` states the value of `m` (`m = {a: 1}`, and there is no entry `m.a`), while under
   an address outside the schema a mapping stays a set of addresses (`server:` produces `server.host`). A map value also
   reaches a `map` field from ENV/a flow literal (`APP__M={a: 1}`), from a patch, or from a default. Two edges
   worth knowing: a **dotted** key is a different address, not a nested one (`m.a: 1` states `m.a` and leaves `m`
   alone — the spelling is only accidentally hierarchical), and a key written with **nothing after the colon**
   (`m:`) has no node at all, so it states an empty SCALAR for every type, not an empty map.
3. **`dropped` is not "delivered nothing".** A layer whose expression produced nothing contributes
   nothing, so the read descends exactly as if that layer had not mentioned the address; with nothing
   below, the field is `ABSENT` — an optional one runs (`0`) and a required one fails as missing (`14`,
   the declaration's decade). An adapter that *states* it could not deliver is a different fact: it stops
   the read in its own layer with `null-value` (decade of that layer). The two cases differ in the exit
   code and in the decade, and that is the honest way to tell them apart.
4. **A key outside the schema is typed by its statement — or `str`.** A rule from ENV or CLI that brings no
   `___TYPE` is compared against `str`; a notation is an error there, because no type here has one for `str`.
   Both are reported in every route — including the one an application builds (`StandardPipeline`, then
   `ConfigValidator.build`): the verdicts of an entry outside the schema used to be visible only to a config
   that had NOT been through the pipeline, which is not how an application builds one.
5. **An error whose origin is the application layer IS numbered.** The standard exit-code pipeline is
   `COMPILED · YAML · ENV · CLI · TUI`, with the application layer between `CLI` and `TUI` — below the
   window, which is the user's last word. An error whose origin falls back to application code (a value
   contributed programmatically, or a problem about a container element, where no layer can be named)
   therefore gets its own decade instead of surfacing as an exception. An application that composes a
   different order states its own pipeline; the shipped one numbers every layer an entry can come from.
6. **A dotted key is only accidentally hierarchical.** `a.b: 5` and a nested `a:` / `  b: 5` produce the
   same canonical address, while a dotted *segment* inside an address object is not the same list of
   segments. Addresses are compared by canonical text, so both work — but the two spellings are not the
   same thing to a reader of diagnostics.
