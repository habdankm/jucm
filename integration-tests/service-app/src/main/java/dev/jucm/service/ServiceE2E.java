package dev.jucm.service;

import dev.jusl.ServiceDefinition;
import dev.jusl.ServiceRegistrar;
import dev.jusl.ServiceRegistrars;
import dev.jusl.ServiceScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The end-to-end test: JUCM is registered as a REAL service on this machine, started, asked about, stopped, and
 * removed - through JUSL, which is what the service library exists to do.
 *
 * <p>It asserts on three different things, because they fail differently: what the registrar says (systemd), what
 * the service's own marks say (our code really ran and really stopped), and what is left behind (nothing).
 */
public final class ServiceE2E {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        String java = System.getProperty("serviceApp.java");
        String classpath = System.getProperty("serviceApp.classpath");
        Path running = Path.of(System.getProperty("serviceApp.runningMark", "/tmp/jucm-service-running"));
        Path stopped = Path.of(System.getProperty("serviceApp.stoppedMark", "/tmp/jucm-service-stopped"));

        Files.deleteIfExists(running);
        Files.deleteIfExists(stopped);

        // NEVER the machine this runs on, unless somebody says so in as many words. Registering a service is a
        // change to someone's system, and the whole reason the VM harness exists (entry 17 of maswerk_integrations)
        // is to keep experiments away from it. Absent explicit permission, this refuses and says where to go.
        if (!"host".equals(System.getProperty("service.e2e.target", ""))) {
            System.err.println("REFUSED: this test registers a real service on the machine it runs on, and it was"
                    + " not pointed at a disposable target. Nothing was changed.");
            System.err.println("  a VM:  entry 17 of maswerk_integrations (the place experiments belong)");
            System.err.println("  this machine, deliberately: ./gradlew :integration-tests:service-app:serviceE2E"
                    + " -PallowHost=yes");
            System.exit(2);
        }
        System.out.println("target: THIS machine, allowed explicitly with -PallowHost=yes");

        ServiceRegistrar registrar = ServiceRegistrars.current();
        System.out.println("mechanism: " + registrar);

        ServiceDefinition definition = new ServiceDefinition(
                "JUCM Service E2E", null, Path.of(java),
                List.of("-cp", classpath,
                        "-Djucm.service.heartbeat=" + running,
                        "-Djucm.service.stopped=" + stopped,
                        JucmService.class.getName()),
                false, ServiceScope.USER);

        String id = registrar.idFor(definition);
        System.out.println("service id: " + id);
        System.out.println("exec:       " + java + " -cp <classpath> " + JucmService.class.getName());

        try {
            registrar.remove(definition);        // whatever a previous run left, so this one starts clean
            check("nothing is registered before we register it", !registrar.isInstalled(definition));

            registrar.install(definition);
            check("install registers it (systemd)", registrar.isInstalled(definition));

            registrar.start(definition);
            check("start makes the systemd service run (systemd)", registrar.isRunning(definition));
            check("and OUR code really started (its own mark)", waitFor(running));

            registrar.stop(definition);
            check("stop makes it not run (systemd)", !registrar.isRunning(definition));
            check("and OUR code really stopped, gracefully (its own mark)", waitFor(stopped));

            registrar.remove(definition);
            check("remove unregisters it", !registrar.isInstalled(definition));
            check("the stop mark survives the removal", Files.exists(stopped));
        } finally {
            try {
                registrar.remove(definition);
            } catch (Exception cleanup) {
                System.err.println("cleanup said: " + cleanup.getMessage());
            }
            Files.deleteIfExists(running);
            Files.deleteIfExists(stopped);
        }

        System.out.println(failures == 0 ? "OK" : "FAIL: " + failures + " assertion(s) did not hold");
        System.exit(failures == 0 ? 0 : 1);
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

    private static void check(String what, boolean held) {
        System.out.println((held ? "  true  " : "  FALSE ") + what);
        if (!held) {
            failures++;
        }
    }

    private ServiceE2E() {
    }
}
