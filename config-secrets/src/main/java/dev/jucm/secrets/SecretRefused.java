package dev.jucm.secrets;

/**
 * A REFUSAL FROM THE SECRET STORE, in the project's word for it: a sentence that says what could not be done and why, never a stack trace and never a
 * silent empty value.
 *
 * <p>It is unchecked on purpose - a missing key, a wrong key or a tampered value are not conditions a caller recovers from inside a flow: the
 * configuration cannot be honoured, and the operator has to act.
 */
public class SecretRefused extends RuntimeException {

    public SecretRefused(String message) {
        super(message);
    }
}
