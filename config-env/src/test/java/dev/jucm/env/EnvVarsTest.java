package dev.jucm.env;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Absent and empty are different states — see {@link EnvVars}. */
class EnvVarsTest {

    private static final String NEVER_SET = "JUCM_NO_SUCH_VARIABLE_1234567890";

    @Test
    @DisplayName("a variable that is not set is ABSENT, and has no value")
    void nonexistentVariableIsAbsent() {
        assertThat(EnvVars.state(NEVER_SET)).isEqualTo(EnvVars.State.ABSENT);
        assertThat(EnvVars.value(NEVER_SET)).isEmpty();
    }

    @Test
    @DisplayName("a variable that is set is read (PATH is in every sensible environment)")
    void existingVariableIsRead() {
        assertThat(EnvVars.state("PATH")).isEqualTo(EnvVars.State.PRESENT);
        assertThat(EnvVars.value("PATH")).isPresent();
    }
}
