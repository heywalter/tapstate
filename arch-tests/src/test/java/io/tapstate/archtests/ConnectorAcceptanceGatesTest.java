package io.tapstate.archtests;

import io.tapstate.app.ConnectorPluginProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate behind "the mechanism is here and it is not open": runtime registration exists, and the set
 * of connectors it accepts ships closed.
 *
 * <p>The accepted set is widenable per deployment, and that is exactly what makes this worth a gate.
 * The guard refusing an unsupported connector is a boundary only for as long as nothing a user is
 * handed has already opened it — and opening it takes one line in a compose file or a quickstart
 * script, not a code change. No module test can make that claim: a module sees its own classes, while
 * this would be opened in a file no module compiles.
 *
 * <p>A proof of absence fails by passing vacuously — a scan that reached nothing reports the same
 * green as a scan that found nothing. So two of the tests here police the scan rather than the
 * product: it must have read the artifacts that could open the hatch, and the single file allowed to
 * name the setting must still be naming it.
 */
class ConnectorAcceptanceGatesTest {

    /** Surefire runs a module from its own directory, so the repository root is one level up. */
    private static final Path REPOSITORY = Path.of("..");

    /**
     * How the setting can be spelled: the property, the field it binds to, and the environment-variable
     * form a container hands the process. All three are searched, because they are interchangeable ways
     * to say the same thing and a gate that knew only one would be trivially side-stepped.
     */
    private static final List<String> WIDENING_SPELLINGS =
            List.of("also-accept-ids", "alsoAcceptIds", "ALSO_ACCEPT_IDS");

    /** The one shipped file that must name the setting: the class that declares it. */
    private static final String DECLARATION =
            "app/src/main/java/io/tapstate/app/ConnectorPluginProperties.java";

    /**
     * Artifacts the scan is required to have read. Named individually rather than counted: these are the
     * files a deployment is actually configured in, so a scan that missed them is not a smaller scan, it
     * is the wrong one.
     */
    private static final List<String> MUST_BE_SCANNED = List.of(
            "deploy/quickstart/docker-compose.yml",
            "deploy/quickstart/quickstart.sh",
            "docs/quickstart-online.md");

    /** Directories that are neither shipped nor written by hand. */
    private static final Set<String> PRUNED = Set.of(".git", "target", "node_modules");

    @Test
    @DisplayName("the accepted connector set ships closed - nothing a release carries widens it")
    void noShippedArtifactWidensTheAcceptedSet() {
        Set<String> offenders = new TreeSet<>();
        for (Path file : shippedFiles()) {
            String relative = relative(file);
            if (relative.equals(DECLARATION)) {
                continue;
            }
            String text = read(file);
            WIDENING_SPELLINGS.stream()
                    .filter(text::contains)
                    .forEach(spelling -> offenders.add(relative + " names " + spelling));
        }

        assertThat(offenders)
                .as("the accepted set is widenable per deployment and ships empty everywhere - a release "
                        + "artifact, script or document that names the setting hands users a way past the "
                        + "guard, and turns a supported-connector boundary into a suggestion")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan reaches the artifacts that could open it")
    void theScanReachesTheArtifactsThatCouldOpenIt() {
        Set<String> scanned = new TreeSet<>();
        shippedFiles().forEach(file -> scanned.add(relative(file)));

        assertThat(scanned)
                .as("this gate asserts an absence, so a scan that reached nothing would pass exactly like "
                        + "a scan that found nothing - it has to be pinned to files that really exist")
                .containsAll(MUST_BE_SCANNED);
    }

    @Test
    @DisplayName("the file allowed to name the setting still declares it")
    void theOneFileAllowedToNameTheSettingStillDoes() {
        // A skipped file that no longer declares the setting would sit here forever, quietly excusing
        // whatever else that path came to hold.
        Path declaration = REPOSITORY.resolve(DECLARATION);

        assertThat(Files.isRegularFile(declaration))
                .as("the skipped file must exist: %s", DECLARATION)
                .isTrue();
        assertThat(read(declaration))
                .as("the skipped file is skipped because it declares the setting - if it no longer does, "
                        + "the exception is stale and something else is being let through under it")
                .contains("alsoAcceptIds");
    }

    @Test
    @DisplayName("the accepted set is empty until a deployment names something")
    void theAcceptedSetIsEmptyUntilADeploymentNamesSomething() {
        assertThat(new ConnectorPluginProperties().getAlsoAcceptIds())
                .as("the default is what every deployment gets that does not say otherwise, so it is the "
                        + "value that decides whether the guard is on by default")
                .isEmpty();
    }

    /** Everything a release carries or a user reads: shipped sources, deployment assets, documentation. */
    private static List<Path> shippedFiles() {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(REPOSITORY, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return PRUNED.contains(directory.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (isShipped(relative(file))) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("walking the repository at " + REPOSITORY, e);
        }
        return files;
    }

    /** Shipped = a module's main sources, the deployment assets, and the documentation. Tests are not. */
    private static boolean isShipped(String relative) {
        return relative.contains("/src/main/")
                || relative.startsWith("deploy/")
                || relative.startsWith("docs/");
    }

    private static String relative(Path file) {
        return REPOSITORY.relativize(file).toString().replace('\\', '/');
    }

    /**
     * The file's text, decoded so that no byte sequence can throw: the scan crosses whatever a deployment
     * directory happens to hold, and the spellings it looks for are ASCII either way.
     */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }
}
