package dev.jucm.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * A handle to the **current** configuration: one central point through
 * which everyone reads the newest snapshot. The history of changes is a **consequence of the `parent`
 * chain** — there is no separate history structure and no `oldValue` field here.
 *
 * <p>A swap is **atomic**: readers see either the whole old model or the whole new one, never a mix,
 * and no read locks are needed for that.
 *
 * <p><b>A snapshot held in hand does not see later changes</b>. That is an advantage
 * (a consistent picture during an operation) and a trap at the same time: a long-living object
 * **MUST** hold a **handle**, not a copy of the value. The test `readerWithOldSnapshotSeesOldValues`
 * guards the one and only place where mutation enters the model — that place is the `AtomicReference`.
 *
 * <p>The handle **MUST NOT** force a singleton: whether the application keeps it statically or passes
 * it on is its own business — the library provides a type.
 *
 * <p>`compact()` is part of the API **without an implementation**: we do not deal
 * with retention, but the method must be loud so that nobody mistakes silence for success.
 */
public final class ConfigStore {

    private final AtomicReference<Config> current;

    private ConfigStore(Config initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /**
     * A handle to a snapshot.
     *
     * @param initial the snapshot to start from; must not be null
     * @return a new handle holding that snapshot
     */
    public static ConfigStore of(Config initial) {
        return new ConfigStore(initial);
    }

    /**
     * The snapshot that is current right now.
     *
     * @return the current snapshot, never null
     */
    public Config current() {
        return current.get();
    }

    /**
     * Swaps the snapshot — atomic.
     *
     * @param next the snapshot to install; must not be null
     * @return the snapshot that was current before the swap
     */
    public Config set(Config next) {
        Objects.requireNonNull(next, "next");
        return current.getAndSet(next);
    }

    /**
     * A swap computed from the current snapshot, e.g. `store.update(cfg -> cfg.apply(cliPatch))`.
     * The operation may run more than once under a race, so it **MUST** be free of side effects.
     *
     * @param operation the swap to compute; must not be null
     * @return the snapshot installed by the operation
     */
    public Config update(UnaryOperator<Config> operation) {
        Objects.requireNonNull(operation, "operation");
        return current.updateAndGet(operation);
    }

    /**
     * Retention of the old chain — **there is no implementation in this version**.
     *
     * @return never returns in this version — the method always throws
     * @throws UnsupportedOperationException always, with a message saying this is deliberate
     */
    public Config compact() {
        throw new UnsupportedOperationException(
            "compact() has no implementation yet — retention is not implemented in this version. "
                + "The method is in the API deliberately: when it arrives, it will not be a breaking change.");
    }
}
