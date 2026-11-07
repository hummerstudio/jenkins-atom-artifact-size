package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artifact-size atom, started the way the runner starts it.
 *
 * <p>What used to be {@code tools/smoke.sh}: sizes are exact (4 KiB + 8 KiB against a 6 KiB budget expectation), the
 * budget gate trips, a pattern that matches nothing is an error, and {@code **} means zero directories.
 */
class ArtifactSizeIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("totals and the largest file come from the artifacts, and the report lists them")
    void totalsAndLargest() throws Exception {
        AtomResult run = atom.file("dist/app.jar", "a".repeat(4096))
                .file("dist/lib.jar", "b".repeat(8192))
                .file("dist/notes.txt", "not a jar\n")
                .input("{\"pattern\": \"dist/*.jar\", \"maxMb\": \"0\", \"topN\": \"10\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("2", run.field("files"), "the .txt is not an artifact");
        assertEquals("12288", run.field("totalBytes"));
        assertEquals("dist/lib.jar", run.field("largest"));
        assertTrue(run.exists("out/artifact-size/index.html"), "no report");
        assertTrue(run.file("out/artifact-size/index.html").contains("lib.jar"),
                "report does not list the file");
    }

    @Test
    @DisplayName("a 2 MB artifact over a 1 MB budget fails the step, and the report is still written")
    void budgetTrips() throws Exception {
        AtomResult run = atom.file("dist/big.jar", "x".repeat(2 * 1024 * 1024))
                .input("{\"pattern\": \"dist/*.jar\", \"maxMb\": \"1\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("over the 1.00 MB budget"),
                "message: " + run.file("output.json"));
        assertTrue(run.exists("out/artifact-size/index.html"), "report must exist on failure");
    }

    @Test
    @DisplayName("a pattern that matches nothing is a readable error, not an empty success")
    void nothingMatched() throws Exception {
        AtomResult run = atom.input("{\"pattern\": \"dist/*.jar\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("nothing matched"), "message not readable");
    }

    @Test
    @DisplayName("**/ also matches zero directories: libs/**/*.jar finds libs/a.jar")
    void doubleStarMatchesZeroDirectories() throws Exception {
        AtomResult run = atom.file("libs/a.jar", "x")
                .input("{\"pattern\": \"libs/**/*.jar\"}")
                .run();

        run.assertOk();
        assertEquals("1", run.field("files"),
                "libs/**/*.jar must find libs/a.jar: " + run.file("output.json"));
        assertFalse(run.field("largest").isEmpty());
    }
}
