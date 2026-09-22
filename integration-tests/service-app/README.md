# service-app - JUCM as a service, and the end-to-end test over it

JUCM has no service of its own to offer. What it can do is **pretend to be one**, so that the service registration
library (JUSL, in ../jusm) has something real to register, start, ask about and stop - on a real machine, with a
real service manager, not a mock.

    JucmService   shape exactly as ../maswerk/multi_oss.md asks for: start() / shutdown() / awaitTermination()
                  hovers on a latch, not on a sleep loop - a sleeping loop is a loop that still wakes up
                  stops on SIGTERM through a shutdown hook, which is how systemd stops a service on Linux
                  leaves two MARKS: one on the way up, one when the stop method really ran
    ServiceE2E    registers the service through JUSL, exercises it, and cleans up even when something fails

Run it:

    ./gradlew :integration-tests:service-app:serviceE2E

## Why two witnesses for every claim

Because they fail differently, and one can be true while the other is false:

    systemd says      isInstalled / isRunning - what the SERVICE MANAGER believes
    the marks say     our code reached start() and reached shutdown() - what OUR PROCESS really did
    what is left      after remove, nothing is registered, and the stop mark is still on disk

A service manager reporting "active" while the process died on the first line is exactly the case this test is
built to catch, and it is why "the domain is running" is never accepted as evidence anywhere in this entry line.

## The one thing this test does NOT do

It does not run in a virtual machine. It runs HERE, as a per-user service, because that is what JUSL's Linux
backend supports and what this machine can prove. The VM harness for Windows is entry 17 in
maswerk_integrations; this module is the payload it will carry, unchanged.
