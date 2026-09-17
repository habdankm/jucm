# Writing your own source

This guide is the *how* for the sixth kind of layer: an adapter of your own. The *why* — what a source
may and may not decide, and what the library guarantees in exchange — is in the root
[`README.md`](../../README.md) section 8; the everyday use of the shipped sources is in
[`configuration.md`](configuration.md). The adapter below is **not** part of the library: it was written
against this version for this guide, compiled and run, and every console block is its real output.

## 1. A layer is a patch, in the order the application gives

The application side of it is one sentence: a custom source takes part in the chain exactly like the
shipped ones, because a layer is nothing but a `ConfigPatch` in the order the application gives. An
adapter therefore provides one method:

```java
public ConfigResult<ConfigPatch> patch(ConfigDefinition definition)
```

Three facts follow from that signature alone: the result is a `ConfigResult`, not a thrown exception, so
a source that cannot be read is a **result** (`README.md` section 6); a `ConfigPatch` is a sparse set of
operations — set a value, `reset`, "delivered nothing" — so the adapter states only what the store really
delivered; and the `definition` is the application model, which an adapter carrying markers needs to tell
a declared field from a key outside the schema.

## 2. The adapter

A store that hands over **text**, keyed by the canonical address, in the two shapes the shipped adapters
use: like `EnvSource` it walks the entries in name order, so the result does not depend on the order the
store answered in; like `YamlSource` it hands back a `ConfigResult` instead of throwing.

```java
public final class RemoteStoreSource {

    private final Map<String, String> values;
    private final String origin;

    /** A {@code null} value means "the store delivered nothing for this key". */
    public static RemoteStoreSource of(Map<String, String> values, String origin) { … }

    /** A file the store exported: one that cannot be read is a RESULT, not an exception. */
    public static ConfigResult<RemoteStoreSource> from(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException unreadable) {
            return ConfigResult.error(new ConfigError.ParseFailed(
                ConfigSource.of(Layer.CUSTOM, file.toString()), unreadable.getMessage() == null
                    ? "the store file could not be read" : unreadable.getMessage()));
        }
        try {
            return ConfigResult.value(of(parse(text), file.toString()));
        } catch (IllegalArgumentException notOurFormat) {
            return ConfigResult.error(new ConfigError.ParseFailed(
                ConfigSource.of(Layer.CUSTOM, file.toString()), notOurFormat.getMessage()));
        }
    }

    public ConfigResult<ConfigPatch> patch(ConfigDefinition definition) {
        ConfigPatch patch = ConfigPatch.empty();
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            ConfigPath path;
            try {
                path = ConfigPath.of(entry.getKey());
            } catch (IllegalArgumentException notAnAddress) {
                // The key is the store's, so the problem is a WHOLE-SOURCE error: nothing in this layer can
                // be trusted for that key, and the message carries the key so the store can be fixed.
                return ConfigResult.error(new ConfigError.ParseFailed(ConfigSource.of(Layer.CUSTOM, origin),
                    "key `" + entry.getKey() + "` is not an address: " + notAnAddress.getMessage()));
            }
            ConfigSource source = ConfigSource.of(Layer.CUSTOM, origin + ":" + entry.getKey());
            if (entry.getValue() == null) {
                patch = patch.merge(ConfigPatch.nullDelivered(path, source));   // "I delivered nothing"
                continue;
            }
            patch = patch.with(path, ConfigString.of(entry.getValue()), source, SourceDeclaration.none());
        }
        return ConfigResult.value(patch);
    }
}
```

The store's own serialization (`key=value` per line, empty and `#` lines skipped) stays private to the
adapter: a format no other part of the library can read is nobody else's business.

## 3. It transports; the engine types, checks and reports

```java
ConfigDefinition definition = ConfigDefinition.of(
    ConfigField.required("server.host", ConfigType.STR, ConfigString.of("localhost")),
    ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080)),
    ConfigField.required("cache.size", ConfigType.INT, ConfigInt.of(1024))
        .transformer("bytes"));                                  // the HOW, from the declaration

Map<String, String> store = new LinkedHashMap<>();
store.put("server.port", "9001");
store.put("cache.size", "10MB");                                 // text, exactly as the store gave it

ConfigPatch storeLayer = ((ConfigResult.ValueResult<ConfigPatch>)
    RemoteStoreSource.of(store, "store.json").patch(definition)).value();
Config config = StandardPipeline.of(definition, registry, storeLayer);
```

Real output of the verdict over that chain (the report, then where each value came from, then the code):

```text
ok   server.host = localhost   [COMPILED:declaration server.host]
ok   server.port = 9001   [CUSTOM:store.json:server.port]
ok   cache.size = 10485760   [CUSTOM:store.json:cache.size]
  server.host <- COMPILED:declaration server.host
  server.port <- CUSTOM:store.json:server.port
  cache.size <- CUSTOM:store.json:cache.size
exit=0
```

`9001` went in as the **text** the store gave and came out typed; `10MB` went in as text too and came out
as `10485760`, because the declaration names the `bytes` transformer — a name points at an implementation
in the application's registry, and a source can never state one (`README.md` section 3.6). The origin of
every entry is the adapter's own address inside the layer (`CUSTOM:store.json:server.port`), which is
exactly what the diagnostics reach for.

**The adapter never casts.** Text that is not an int enters as text — `server.port = "later"` becomes a
`ConfigString` under the adapter's origin — and the verdict is `CAST_FAILED` on the **field**. The
adapter has no part in that problem and nothing to report about it.

## 4. A failure is a result, never an exception in the middle of a walk

A file the store exported and the application cannot read comes back as an error, and the delivered
facade ends the run there — the message names the source, and the code is the layer's decade plus the
category of the cause:

```text
$ # the demo's third case: RemoteStoreSource.from(Path.of("no-such-store.txt"))
error=parse-failed: the source cannot be read: no-such-store.txt [source CUSTOM:no-such-store.txt]
exit=50
```

A **key** that is not an address is the other kind of failure: the store is the only place that can fix
it, so it is a whole-source error rather than a problem of one entry. Its message carries the key, and it
has **no path** — verified: `ConfigError.pathOf(thatError)` is empty, the same rule as for a YAML file
with a misplaced marker.

## 5. "Delivered nothing" is a statement of its own

A store that answers with a key and no value is not the same as a store that never mentioned the key.
The adapter says the first with `ConfigPatch.nullDelivered(path, source)`, and the engine turns it into
the emptiness error, with the adapter's origin — the reason travels with the statement:

```text
ok   server.port = 9001   [CUSTOM:store.json:server.port]
ok   cache.size = 1024   [COMPILED:declaration cache.size]
ERROR null-value: empty value - remove the key/variable or provide a value [path `server.host`] [source CUSTOM:store.json:server.host]
exit=50
```

The entry never reached the cast — it was empty before it — which is why the same report under a
two-layer pipeline ([`COMPILED`, `CUSTOM`]) gives `20`, not `21`.

## 6. Exit codes: `STANDARD` already numbers the layer you contribute

`ConfigExitCode.Pipeline.STANDARD` numbers every layer an entry can come from — `COMPILED`, `YAML`,
`ENV`, `CLI`, `CUSTOM`, `TUI` — so a problem whose origin is your adapter's layer gets a code without
any work from you. `CUSTOM` sits at position `5`, just below the window, which is the user's last word:

```java
System.exit(ConfigExitCode.lowestOf(report.errors(), ConfigExitCode.Pipeline.STANDARD));
```

`50` above is that pipeline read aloud: `CUSTOM` at position `5`, and the category `0` of an input
failure. State your own order only when your layers differ from the shipped one — the contract is the
same, only the positions are yours:

```java
ConfigExitCode.Pipeline pipeline = new ConfigExitCode.Pipeline(
    List.of(Layer.CUSTOM, Layer.YAML, Layer.ENV, Layer.CLI));
```

The decade is **positional**, so a kind inserted before `YAML` shifts the shipped numbers —
`CUSTOM` placed first makes a file violation `23` instead of `33`.

## 7. A transformer of your own travels by name

The adapter delivers text; if that text is written in a notation of your own (`10MB`), the declaration
names the transformer and the application registers it:

```java
TransformerRegistry registry = TransformerRegistry.defaults()
    .register("bytes", BytesTransformer::new);
```

```java
public final class BytesTransformer implements Transformer {

    private static final Pattern FORM = Pattern.compile("([0-9]+)\\s*(B|KB|MB|GB)");

    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (raw instanceof ConfigInt) {
            return Transformer.normalized(raw);       // already canonical: nothing to normalise
        }
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("expected a size in the `10MB` form — the incoming value has type `"
                + raw.type().yamlName() + "`");
        }
        Matcher form = FORM.matcher(text.value().trim().toUpperCase(Locale.ROOT));
        if (!form.matches()) {
            return Transformer.rejected("not a size: expected `<number><unit>` with one of B, KB, MB, GB");
        }
        long factor = switch (form.group(2)) {
            case "KB" -> 1024L;
            case "MB" -> 1024L * 1024L;
            case "GB" -> 1024L * 1024L * 1024L;
            default -> 1L;
        };
        return Transformer.normalized(ConfigInt.of(Long.parseLong(form.group(1)) * factor));
    }
}
```

It is deterministic, stateless, has no I/O and no clock — so one instance serves every field at once, and
the whole class is testable with a plain assertion (see [`testing.md`](testing.md)).

## Where to go next

| you want | go to |
|---|---|
| what a source may decide, and what the library guarantees | [`README.md`](../../README.md) section 8 |
| the shipped sources, in order, as an application uses them | [`configuration.md`](configuration.md) |
| testing an adapter, a rule and an exit code | [`testing.md`](testing.md) |
| the ENV adapter (markers, ordered cuts, text values) | `config-env/src/main/java/dev/jucm/env/EnvSource.java` |
| the YAML adapter (a file, raw scalars, a patch out of a tree) | `config-yaml/src/main/java/dev/jucm/yaml/YamlSource.java` |
