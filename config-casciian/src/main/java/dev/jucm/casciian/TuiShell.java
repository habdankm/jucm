package dev.jucm.casciian;

import casciian.TApplication;

/**
 * Adapter to the TUI library: reports the base class the module is built on.
 *
 * <p>No terminal is opened. Referring to a type from Casciian proves that the dependency is on the
 * classpath and that this module compiles against it.
 *
 * <p>Casciian 1.6.0 ships as a <b>modular jar</b> with a package named {@code casciian} and a module
 * descriptor of the same name, which matters on the module path and under Native Image.
 *
 * @since 1.0
 */
public final class TuiShell {

    private TuiShell() {
    }

    /**
     * Returns a description of this module, naming the base class it is built on.
     *
     * @return a one-line description; never {@code null}
     * @since 1.0
     */
    public static String describe() {
        return "TUI (Casciian, base class " + TApplication.class.getSimpleName() + ") — pending";
    }
}
