package dev.jucm.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * JUCM pretending to be a service, shaped as multi_oss.md asks: start, shutdown, awaitTermination.
 *
 * <p>It hovers without loading the CPU: awaitTermination waits on a latch, it does not spin. Systemd stops a
 * service by sending SIGTERM, so the stopping method is reached through a shutdown hook - the standard JVM way,
 * and the one multi_oss.md names for Linux.
 *
 * <p>It leaves marks, because "the service is running" is what libvirt and systemd say, and those two are not the
 * same as "our code got to run and got to stop". The marks are the difference.
 */
public final class JucmService {

    private static final Path RUNNING = Path.of(System.getProperty("jucm.service.heartbeat", "/tmp/jucm-service-running"));
    private static final Path STOPPED = Path.of(System.getProperty("jucm.service.stopped", "/tmp/jucm-service-stopped"));

    private final CountDownLatch done = new CountDownLatch(1);

    /** What the application does on the way up. Here: announce itself, having read its configuration through JUCM. */
    public void start() throws Exception {
        Files.writeString(RUNNING, "started " + java.time.Instant.now() + System.lineSeparator());
        System.out.println("JUCM service started, mark at " + RUNNING);
    }

    /** The graceful stop: stop accepting work, finish, flush, say so. */
    public void shutdown() {
        try {
            Files.writeString(STOPPED, "stopped " + java.time.Instant.now() + System.lineSeparator());
            System.out.println("JUCM service stopping, mark at " + STOPPED);
        } catch (Exception broken) {
            System.err.println("could not write the stop mark: " + broken);
        }
        done.countDown();
    }

    /** Stays alive until shutdown. A latch, not a sleep loop: a sleeping loop is a loop that still wakes up. */
    public void awaitTermination() throws InterruptedException {
        done.await();
    }

    public static void main(String[] args) throws Exception {
        JucmService service = new JucmService();
        Runtime.getRuntime().addShutdownHook(new Thread(service::shutdown, "shutdown"));
        Files.deleteIfExists(STOPPED);
        service.start();
        service.awaitTermination();
    }

    private JucmService() {
    }
}
