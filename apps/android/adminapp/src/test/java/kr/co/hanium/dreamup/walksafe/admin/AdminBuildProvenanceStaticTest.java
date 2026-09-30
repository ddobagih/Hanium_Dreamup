package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * S21-24: the admin APK records the same source commit, build kind and app role as the user APK,
 * and a release build fails without a 40-hex commit. Build-script guard only; the produced APK and
 * its independent hash still come from the release evidence procedure.
 */
public final class AdminBuildProvenanceStaticTest {
    private final String script = read("build.gradle.kts");

    @Test
    public void releaseRequiresFortyHexSourceCommitBeforeBuilding() {
        assertTrue(script.contains("Regex(\"^[0-9a-fA-F]{40}$\")"));
        assertTrue(script.contains("providers.gradleProperty(\"WALKSAFE_SOURCE_COMMIT\")"));
        assertTrue(script.contains(".orElse(providers.environmentVariable(\"WALKSAFE_SOURCE_COMMIT\"))"));
        String validation = section("val validateWalkSafeAdminSourceCommit by tasks.registering {", "tasks.configureEach {");
        assertTrue(validation.contains("check(verifiedSourceCommit != null)"));
        String wiring = section("tasks.configureEach {", "android {");
        assertTrue(wiring.contains("if (name == \"preReleaseBuild\")"));
        assertTrue(wiring.contains("dependsOn(validateWalkSafeAdminSourceCommit)"));
        assertTrue(wiring.contains("dependsOn(validateWalkSafeAdminReleaseOrigin)"));
    }

    @Test
    public void debugAndReleaseBothEmbedTheCommitAndABuildKindMarker() {
        String debug = section("        debug {", "        release {");
        String release = section("        release {", "isMinifyEnabled");
        String commitField = "buildConfigField(\"String\", \"WALKSAFE_SOURCE_COMMIT\", \"\\\"${verifiedSourceCommit ?: \"unverified\"}\\\"\")";
        assertTrue(debug.contains(commitField));
        assertTrue(release.contains(commitField));
        assertTrue(debug.contains("\"WALKSAFE_BUILD_MARKER\", \"\\\"walksafe-admin-debug-v1\\\"\""));
        assertTrue(release.contains("\"WALKSAFE_BUILD_MARKER\", \"\\\"walksafe-admin-release-v1\\\"\""));
        assertFalse(release.contains("walksafe-release-v1\\\""));
    }

    @Test
    public void appRoleAndIdentifierStaySeparateFromTheUserApp() {
        assertTrue(script.contains("buildConfigField(\"String\", \"WALKSAFE_PRODUCT_ROLE\", \"\\\"ADMIN\\\"\")"));
        assertTrue(script.contains("applicationId = \"kr.co.hanium.dreamup.walksafe.admin\""));
        assertEquals(1, count(script, "applicationId = "));
    }

    private String section(String start, String end) {
        int from = script.indexOf(start);
        assertTrue("missing " + start, from >= 0);
        int to = script.indexOf(end, from + start.length());
        assertTrue("missing " + end, to > from);
        return script.substring(from, to);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError(path, error);
        }
    }
}
