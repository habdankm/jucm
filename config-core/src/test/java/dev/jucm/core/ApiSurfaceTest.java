package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API surface test: a scan of the public signatures of the core.
 *
 * <p>A consumer must not be forced to depend on Vavr, so the build has to fail when {@code io.vavr}
 * appears in a public return type, a parameter type or a generic argument. We scan **all** public core
 * classes instead of a hand-written list — a hand-written list stops guarding exactly when someone adds
 * a new type.
 */
class ApiSurfaceTest {

    @Test
    @DisplayName("no public core signature contains io.vavr")
    void publicApiHasNoVavr() throws Exception {
        List<Class<?>> types = publicCoreTypes();

        assertThat(types)
            .as("the scan must find something — an empty scan always passes and guards nothing")
            .isNotEmpty()
            .contains(ConfigValue.class, ConfigPath.class, ConfigPatch.class, ConfigType.class, Layer.class);

        List<String> offenders = new ArrayList<>();
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                check(offenders, type, method.getReturnType());
                check(offenders, type, method.getGenericReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    check(offenders, type, parameter);
                }
                for (Type generic : method.getGenericParameterTypes()) {
                    check(offenders, type, generic);
                }
            }
        }

        assertThat(offenders)
            .as("Vavr has to stay an implementation detail — it leaked into the public API")
            .isEmpty();
    }

    private static void check(List<String> offenders, Class<?> owner, Type type) {
        String typeName = type.getTypeName();
        if (typeName.contains("io.vavr")) {
            offenders.add(owner.getSimpleName() + " -> " + typeName);
        }
    }

    /** Public core classes from the output directory — nested classes excluded. */
    private static List<Class<?>> publicCoreTypes() throws Exception {
        Path classes = Path.of(ConfigValue.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path core = classes.resolve("dev/jucm/core");
        try (Stream<Path> walk = Files.walk(core)) {
            return walk.filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .map(path -> classes.relativize(path).toString().replace('/', '.').replaceAll("\\.class$", ""))
                .map(ApiSurfaceTest::load)
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .toList();
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("a class from the output directory does not load: " + name, e);
        }
    }
}
