# service-app - JUCM as a service, as ONE native binary

JUCM has no service of its own. What it can do is pretend to be one, so that the service registration library
(JUSL, in ../jusm) has something real to register - and this module delivers that as a **single binary with
nothing behind it**: no JVM, no jars, no runtime to install.

    jusl-service                 hover as the service (systemd stops it with SIGTERM)
    jusl-service install         register this very binary as a service
    jusl-service reinstall       replace the registration
    jusl-service remove          unregister
    jusl-service status          what the service manager says, and what our marks say
    jusl-service cycle           install, start, check, stop, remove - the whole thing in one command

## Why one binary, and not a JVM plus jars

Because that is the product's shape, not a convenience: the binary is its own installer. It knows its own path
from the running process, so it can register ITSELF - which is only possible because the registration logic is
linked in, not standing next to a JVM that the target machine would have to have.

    build:   ./gradlew :integration-tests:service-app:nativeCompile \
                 -Porg.gradle.java.installations.paths=/path/to/graalvm
    binary:  build/native/nativeCompile/jusl-service

## Two witnesses for every claim

The cycle asserts on two things that fail differently, and one can be true while the other is false:

    the manager says   isInstalled / isRunning - what systemd believes
    the marks say      our process reached the run path, and its stop path really ran
    what is left       after remove: nothing registered, and no stale failure in the manager

A service manager reporting "active" about a process that died on the first line is exactly what this catches,
which is why "the machine is up" is never accepted as evidence anywhere in this line of work.

## Where it is tested, and where it is NOT

In a guest. Not on the machine this repository is developed on: registering a service is a change to somebody's
system, and the whole reason the VM harness exists (entry 17 of maswerk_integrations) is to keep experiments out
of it. `-PallowHost=yes` is the deliberate, spoken exception - and nothing else.

The host builds the binary. The guest runs it, and has no Java at all.
