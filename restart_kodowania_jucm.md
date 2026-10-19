# JUCM — restarting the session (19 X 2026)

A handoff, not a summary: the README says what the engine is, `docs/lifecycle.md` how a value travels
(interpolation → transformation → cast → constraints), `docs/adr/` why the logging and the layering are what they are, and
`TODO.md` what was decided but not built. This file says where the work stopped and what the next move is.

## WHERE IT STANDS, MEASURED

    version         1.0, group dev.jucm; every module applies maven-publish (build.gradle, line ~44)
    modules         config-core · config-yaml · config-env · config-picocli · config-casciian · config-toolbox
    yaml engine     org.snakeyaml:snakeyaml-engine:2.9 — the ENGINE, never classic snakeyaml, because only it hands back COMMENTS
    layering        defaults (code) < the YAML file < ENV < CLI
    env convention  PREFIX__PATH__TO__KEY (a prefix without a trailing `__` gets it appended); `___TYPE` marks a typed value
    tests           config-yaml green, including the five that hold `YamlDocument` (19 X 2026)

## WHAT WAS ADDED ON 19 X 2026 — YamlDocument (config-yaml)

`dev.jucm.yaml.YamlDocument`: a YAML file handled as a TREE, for the one job an application has on somebody else's settings file -
adding or removing an entry without rewriting what a person maintains.

    YamlDocument doc = YamlDocument.read(path);
    doc.putScalar(uri, "jidl", "connections", "dev");
    boolean written = doc.writeIfChanged(path);     // false = nothing changed, the file was NOT touched

    compose() with comments (never load()), mutate only the nodes asked for, emit with comments enabled
    writeIfChanged: atomic temp+move, `<name>.bak-<timestamp>` of the version it replaces, newest ten kept
    putScalar / putMapping / remove / has / scalar / keys  — nothing else

FIVE TESTS, each naming the failure it prevents:

1. adding an entry keeps EVERY comment and EVERY original line, in order (a URI stays on ONE line: `splitLines(false)`);
2. a scalar where a mapping is needed is REFUSED rather than replaced by a structure;
3. an unchanged document does not touch the file; a changed one backs up and re-reads identically;
4. thirteen changes leave exactly TEN copies, and the newest holds the state BEFORE the last change;
5. removal of an absent entry is not an error.

## THE TRAP THAT COST A ROUND

The first version used `FailsafeSchema` - the same schema `YamlSource` reads with - and the emitter then QUOTED every scalar,
because with that schema `2500` is a string and must stay one: the file came back as `'2500'`. The test comparing every original
line caught it. `YamlDocument` now composes with `CoreSchema`, so YAML 1.2 types survive emission; what JUCM reads is unchanged,
because reading goes through `YamlSource` and its own schema.

THE SECOND, HONEST LIMIT: composing and emitting preserves the CONTENT and the COMMENTS, not the FORMATTING (indentation, quoting,
where an inline comment sits). That is why `writeIfChanged` compares the rendered text with the file and does NOT write when they
match - a file nobody asked to change is never rewritten.

## WHAT IS DECIDED AND NOT BUILT

1. **A `sensitive` ConfigField** — see `TODO.md`, section "A sensitive ConfigField". Written down rather than built, because the
   first consumer keeps secrets in ENVIRONMENT VARIABLES only, so nothing is printed that would need masking. The item names the
   real work honestly: masking needs ONE door through which every human-facing value passes, and today there are four renderers.
   It becomes urgent the day a secret MUST live in a file.

2. **A published artefact that contains `YamlDocument`.** The class is IN THE REPOSITORY and in NO released version: version 1.0
   predates it. A consumer that wants to write its settings through it (maswerk) cannot, until a version is published. That is the
   one dependency blocking the database-connection work in the neighbouring project.

3. **A test that a MAP OF SCALARS with dynamic keys is expressible end to end.** `ConfigField.map/optionalMap(path, keyType,
   valueType)` exists in `config-core`, so the schema side is there; what nobody has yet written is the test that a definition with
   `optionalMap("jidl.connections", STR, STR)` accepts a YAML document whose keys are arbitrary names and whose values are scalars -
   which is exactly the shape the next consumer needs (`jidl.connections.dev`, `.test`, `.prod`).

## HOW TO RUN IT

    ./gradlew --no-daemon :config-yaml:test                       # the module this session touched
    ./gradlew --no-daemon :config-yaml:test --tests "dev.jucm.yaml.YamlDocumentTest"
    ./gradlew --no-daemon build                                    # everything
    ./gradlew --no-daemon publishToMavenLocal                      # what a consumer waits for

## WHAT THE NEXT CONSUMER NEEDS FROM THIS LIBRARY (recorded 19 X 2026)

maswerk wires JIDL's database workbench and wants:

- `jidl.connections` as a **map of scalars** (`name -> jdbc uri`), so a database is added by writing one line and never by
  rebuilding the application; plus `jidl.mcp_default` and `jidl.probe_at_start`;
- credentials in ENV, interpolated into the URI (`?user=${...}&password=${...}`) - no percent-encoding at prototype stage, the
  special-character breakage is a NAMED limit;
- a missing variable to be a REFUSAL naming the key and the connection;
- and the YAML writer (this session's `YamlDocument`) to persist a connection added at run time, keeping the operator's comments.

Field naming there follows this project's convention: `MASWERK__JIDL__DB__<NAME>__USER` and `..._PASSWORD`.
