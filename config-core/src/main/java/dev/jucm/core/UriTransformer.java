package dev.jucm.core;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * The default `uri` transformer: **validation of the scheme and syntax**, not rewriting the value.
 *
 * <p>A URI is an address, not a notation with many variants — so normalisation comes down to checking
 * that the value is an address at all: correct syntax and a **mandatory scheme**. A bare text without a
 * scheme is a rejection, because there is no way to know how to read it.
 *
 * <p>The resource a URI points at is **not read here**: I/O belongs to the source/loader,
 * and the transformation must stay deterministic and stateless.
 */
final class UriTransformer implements Transformer {

    /** `uri` handles the `uri` type only — on a `str` it would do nothing. */
    @Override
    public Set<ConfigType> allowedTypes() {
        return Set.of(ConfigType.URI);
    }

    @Override
    public Optional<String> configProblem(Optional<String> config) {
        return config.map(value -> "`uri` has no configuration — unknown `" + value + "`");
    }

    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("expected an address as text — the incoming value has type `"
                + raw.type().yamlName() + "`");
        }
        String value = text.value().trim();
        if (value.isEmpty()) {
            return Transformer.rejected("an empty text is not an address");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException brokenSyntax) {
            return Transformer.rejected("this is not a valid URI: " + brokenSyntax.getMessage());
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            return Transformer.rejected("missing scheme — an address MUST say how to read it");
        }
        return Transformer.normalized(text);
    }
}
