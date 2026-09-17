package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The Vavr carrier in the core — persistent layers and no mutation. */
class ProvenanceTest {

    @Test
    void keepsOrderAndDoesNotMutate() {
        Provenance base = Provenance.of("compiled", "yaml");
        Provenance extended = base.above("env");

        assertThat(base.layers()).as("the old object must not change")
            .containsExactly("compiled", "yaml");
        assertThat(extended.layers()).containsExactly("compiled", "yaml", "env");
        assertThat(extended.top()).isEqualTo("env");
        assertThat(extended.toString()).isEqualTo("compiled > yaml > env");
    }

    @Test
    void emptyProvenanceHasNoTop() {
        assertThat(Provenance.of().layers()).isEqualTo(List.of());
        assertThat(Provenance.of().top()).isEmpty();
    }
}
