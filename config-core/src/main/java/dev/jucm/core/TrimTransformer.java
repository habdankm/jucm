package dev.jucm.core;

import java.util.Optional;
import java.util.Set;

/**
 * Trims leading and trailing whitespace from a string value.
 *
 * <p>Not registered as a default notation for {@code str}: whitespace is often significant, so
 * trimming is only applied when a field states this transformer explicitly.
 */
final class TrimTransformer implements Transformer {

    @Override
    public Set<ConfigType> allowedTypes() {
        return Set.of(ConfigType.STR);
    }

    /** This transformer takes no configuration. */
    @Override
    public Optional<String> configProblem(Optional<String> config) {
        return config.map(value -> "`trim` has no configuration — unknown `" + value + "`");
    }

    /**
     * Trims the supplied value.
     *
     * @param raw value to trim; must be a {@link ConfigString}
     * @param config ignored; this transformer takes no configuration
     * @return the trimmed value, or a rejection when {@code raw} is not a string
     */
    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("`trim` works on a string — the incoming value has type `"
                + raw.type().yamlName() + "`");
        }
        return Transformer.normalized(ConfigString.of(text.value().trim()));
    }
}
