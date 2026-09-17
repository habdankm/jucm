# Consumer test data

This is where the consumer's explicit, deterministic inputs belong:

```text
valid.yaml          a complete configuration
references.yaml     with references and the `??` fallback
invalid.yaml        deliberately broken (casting, empty values)
```

The directory is empty because there is **nothing to read yet**: the YAML adapter and the engine are
not wired into this module. We do not plant files nobody parses — that would be decoration, not a test.
