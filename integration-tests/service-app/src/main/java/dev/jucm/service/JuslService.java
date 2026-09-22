package dev.jucm.service;

import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.toolbox.ConfigTool;
import dev.jusl.ServiceDefinition;
import dev.jusl.ServiceRegistrar;
import dev.jusl.ServiceRegistrars;
import dev.jusl.ServiceScope;
import module java.base;

/**
 * The whole point of the exercise in one native binary: JUCM inside (the configuration), JUSL inside (the service
 * registration), and no JVM anywhere on the machine that runs it.
 *
 * <p>It is its own installer. It is meant to be carried into a guest as ONE file and to register ITSELF there -
 * which is only possible because the registration logic is linked in, not added alongside.
 *
 * <p>Actions:
 *
 * <pre>
 *   jusl-service                 hover as the service (systemd stops it with SIGTERM)
 *   jusl-service install         register this very binary as a service
 *   jusl-service reinstall       replace the registration
 *   jusl-service remove          unregister
 *   jusl-service status          what the service manager says, and what our marks say
 *   jusl-service cycle           install, start, check, stop, remove - the whole thing, in one command
 * </pre>
 *
 * <p>The configuration comes from JUCM, so a caller can move the marks or rename the service with an argument or
 * an environment variable without touching this code. That is the difference between a demo and an application.
 */
public final class JuslService {

    /** The environment prefix: `JUSLSERVICE__NAME=...`. */
    private static final String ENV_PREFIX = "JUSLSERVICE";

    /**
     * A public no-argument constructor, although nothing here calls it: the JVM launcher needs it to run an
     * INSTANCE main (Java 25), and Native Image builds the same way. Without it `run` fails with "no non-private
     * zero argument constructor found".
     */
    public JuslService() {
    }

    void main(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "install" -> {
                    var registrar = ServiceRegistrars.current();
                    registrar.install(definition(registrar, true));
                    System.out.println("installed: " + registrar.idFor(definition(registrar, true)));
                    return;
                }
                case "reinstall" -> {
                    var registrar = ServiceRegistrars.current();
                    registrar.reinstall(definition(registrar, true));
                    System.out.println("reinstalled: " + registrar.idFor(definition(registrar, true)));
                    return;
                }
                case "remove" -> {
                    var registrar = ServiceRegistrars.current();
                    registrar.remove(definition(registrar, true));
                    System.out.println("removed: " + registrar.idFor(definition(registrar, true)));
                    return;
                }
                case "status" -> {
                    System.exit(status());
                }
                case "cycle" -> {
                    System.exit(cycle());
                }
                default -> {
                    // not ours: let JUCM have it (help, tui, configuration printing, its own actions)
                }
            }
        }
        System.exit(ConfigTool.of(model())
                .program("jusl-service")
                .sources(Path.of("."))
                .env(ENV_PREFIX)
                .onRun(config -> {
                    try {
                        new JuslService().hover(java.nio.file.Path.of(ConfigTool.display(config, "mark.dir")));
                    } catch (Exception broken) {
                        throw new RuntimeException(broken);
                    }
                })
                .run(args, System.out, System.err));
    }

    /** Hovers until the service manager stops us. A latch, not a sleep loop: a sleeping loop still wakes up. */
    private void hover(Path markDirectory) throws Exception {
        Files.createDirectories(markDirectory);
        Path running = markDirectory.resolve("running");
        Path stopped = markDirectory.resolve("stopped");
        Files.deleteIfExists(stopped);
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.writeString(stopped, "stopped " + Instant.now() + System.lineSeparator());
            } catch (Exception ignored) {
                // nothing useful can be done from a shutdown hook, and saying so would be noise
            }
            done.countDown();
        }, "shutdown"));
        Files.writeString(running, "started " + Instant.now() + System.lineSeparator());
        System.out.println("service up, mark at " + running);
        done.await();
    }

    /** What the manager says and what our marks say - two witnesses, because they fail differently. */
    private int status() throws Exception {
        var registrar = ServiceRegistrars.current();
        var definition = definition(registrar, false);
        System.out.println("mechanism:  " + registrar.mechanism());
        System.out.println("service id: " + registrar.idFor(definition));
        System.out.println("installed:  " + registrar.isInstalled(definition));
        System.out.println("running:    " + registrar.isRunning(definition));
        return 0;
    }

    /** The end-to-end test, inside the binary, so that a guest needs nothing but this file. */
    private int cycle() throws Exception {
        var registrar = ServiceRegistrars.current();
        var definition = definition(registrar, false);
        String id = registrar.idFor(definition);
        Path markDirectory = Path.of(System.getenv().getOrDefault("JUSLSERVICE__MARK_DIR", "/tmp/jusl-service"));
        Path running = markDirectory.resolve("running");
        Path stopped = markDirectory.resolve("stopped");
        int failures = 0;

        System.out.println("mechanism:  " + registrar.mechanism());
        System.out.println("service id: " + id);
        System.out.println("self:       " + definition.executable());

        Files.deleteIfExists(running);
        Files.deleteIfExists(stopped);
        try {
            registrar.remove(definition);
            failures += check("nothing is registered before we register it", !registrar.isInstalled(definition));

            registrar.install(definition);
            failures += check("install registers it", registrar.isInstalled(definition));

            registrar.start(definition);
            failures += check("start makes the manager run it", registrar.isRunning(definition));
            failures += check("and our process really started (its own mark)", waitFor(running));

            registrar.stop(definition);
            failures += check("stop makes it not run", !registrar.isRunning(definition));
            failures += check("and our process really stopped, gracefully (its own mark)", waitFor(stopped));

            registrar.remove(definition);
            failures += check("remove unregisters it", !registrar.isInstalled(definition));
        } finally {
            try {
                registrar.remove(definition);
            } catch (Exception cleanup) {
                System.err.println("cleanup said: " + cleanup.getMessage());
            }
        }
        System.out.println(failures == 0 ? "OK" : "FAIL: " + failures + " assertion(s) did not hold");
        return failures == 0 ? 0 : 1;
    }

    /**
     * The service as this binary describes it. The executable is the binary ITSELF, read from the running process -
     * a self-installing service cannot know its own path any other way, and hard-coding it would make the binary
     * movable only if somebody remembered to edit it.
     */
    private static ServiceDefinition definition(ServiceRegistrar registrar, boolean forRegistration) {
        return new ServiceDefinition("JUCM Service", null, selfPath(), List.of(), false, ServiceScope.USER);
    }

    /** This binary, read from the running process: a self-installing service cannot know its path any other way. */
    static Path selfPath() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .orElseThrow(() -> new IllegalStateException("this binary does not know its own path"));
    }

    private static boolean waitFor(Path mark) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (Files.exists(mark)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private static int check(String what, boolean held) {
        System.out.println((held ? "  true  " : "  FALSE ") + what);
        return held ? 0 : 1;
    }

    /** The application model: one source of metadata, read through JUCM like any other consumer's. */
    public static ConfigDefinition model() {
        return ConfigDefinition.of(
                ConfigField.required("name", ConfigType.STR, ConfigString.of("JUCM Service"))
                        .described("The service name as a human writes it; the identifier is derived by the system."),
                ConfigField.required("exec", ConfigType.STR, ConfigString.of(selfPath().toString()))
                        .described("The executable to register. The default is this very binary, read from the"
                                + " running process - not an empty string, because JUCM (rightly) refuses empty"
                                + " values and an empty default would only hide that."),
                ConfigField.required("mark.dir", ConfigType.STR, ConfigString.of("/tmp/jusl-service"))
                        .described("Where the service leaves its marks, so that 'running' can be told from 'started'."));
    }
}
