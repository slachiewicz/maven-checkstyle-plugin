/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.checkstyle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.maven.api.Language;
import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Provides;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoExtension;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.api.plugin.testing.stubs.MojoExecutionStub;
import org.apache.maven.api.plugin.testing.stubs.PluginStub;
import org.apache.maven.api.plugin.testing.stubs.ProjectStub;
import org.apache.maven.api.services.ProjectManager;
import org.codehaus.plexus.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs <code>checkstyle:check</code> against the scenarios in <code>src/test/resources/check-scenarios</code>, with
 * the real Checkstyle executor, and asserts on the plugin log and the failure message.
 * <p>
 * The log of the plugin is recorded. The executor logs through SLF4J instead, so what it writes to
 * <code>System.err</code> is captured as well (<code>simplelogger.properties</code> enables its debug level).
 * <p>
 * Some tests change the JVM-wide default locale and standard error stream, so this class must not run its tests in
 * parallel with others; the build runs them sequentially.
 */
@MojoTest
public class CheckstyleViolationCheckScenarioTest {

    @Inject
    private Project project;

    @Inject
    private Session session;

    private final Map<String, List<SourceRoot>> sourceRoots = new HashMap<>();

    private final StringBuilder pluginLog = new StringBuilder();

    /**
     * The scenarios are copied to <code>target/test-classes</code> and write their own <code>target</code> directory,
     * which has to be gone before a scenario runs, as with <code>mvn clean</code> in an integration test.
     */
    @BeforeEach
    public void cleanScenarioBuildDirectory() throws IOException {
        FileUtils.deleteDirectory(new File(MojoExtension.getBasedir(), "target"));
    }

    /**
     * The harness session hands out a mock of the project manager, which the mojo asks for the source and resource
     * roots of the project.
     */
    @BeforeEach
    public void answerSourceRoots() {
        ProjectManager projectManager = session.getService(ProjectManager.class);
        when(projectManager.getEnabledSourceRoots(any(), any(), any()))
                .thenAnswer(invocation ->
                        sourceRoots
                                .getOrDefault(key(invocation.getArgument(1), invocation.getArgument(2)), List.of())
                                .stream());
    }

    private static String key(ProjectScope scope, Language language) {
        return scope + ":" + language;
    }

    private void sourceRoot(ProjectScope scope, String directory) {
        addRoot(scope, Language.JAVA_FAMILY, directory, List.of(), List.of());
    }

    private void resourceRoot(ProjectScope scope, String directory, List<String> includes, List<String> excludes) {
        addRoot(scope, Language.RESOURCES, directory, includes, excludes);
    }

    private void addRoot(
            ProjectScope scope, Language language, String directory, List<String> includes, List<String> excludes) {
        SourceRoot root = mock(SourceRoot.class);
        when(root.directory())
                .thenReturn(
                        Path.of(MojoExtension.getBasedir()).resolve(directory).normalize());
        when(root.includes()).thenReturn(includes);
        when(root.excludes()).thenReturn(excludes);
        sourceRoots
                .computeIfAbsent(key(scope, language), k -> new ArrayList<>())
                .add(root);
    }

    @Provides
    @SuppressWarnings("unused")
    private MojoExecution mojoExecution() {
        PluginStub plugin = new PluginStub();
        plugin.setModel(Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-checkstyle-plugin")
                .build());
        MojoExecutionStub execution = new MojoExecutionStub("default", "check");
        execution.setPlugin(plugin);
        return execution;
    }

    @Provides
    @SuppressWarnings("unused")
    private Log log() {
        return new RecordingLog(pluginLog);
    }

    /** MCHECKSTYLE-268: a violation severity of warning counts the warnings of google_checks.xml. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-268")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Test
    public void testViolationSeverityWarning(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.MAIN, "src/main/java");

        Execution execution = execute(mojo);

        assertEquals(
                "You have 3 Checkstyle violations.",
                execution.failure(MojoException.class).getMessage());
    }

    /** MCHECKSTYLE-295: resources matching resourceIncludes are checked, other rules than the default apply. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-295")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Disabled(
            "MojoExtension 4.0.0-beta-4 NPEs in WrapEvaluator.stripTokens on the null-valued nodes of the checkstyleRules XmlNode parameter, so the mojo cannot be created")
    @Test
    public void testTestResourcesWithInlineRules(CheckstyleViolationCheckMojo mojo) throws Exception {
        resourceRoot(ProjectScope.TEST, "src/test/resources", List.of(), List.of());

        Execution execution = execute(mojo);

        assertTrue(
                execution.output.contains("There are 2 errors reported by Checkstyle"),
                () -> "Missing the error count in: " + execution.output);
        assertEquals(
                "You have 2 Checkstyle violations.",
                execution.failure(MojoException.class).getMessage());
    }

    /** MCHECKSTYLE-347: a blank violationIgnore ignores nothing. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-347")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @MojoParameter(name = "violationIgnore", value = "")
    @MojoParameter(name = "configLocation", value = "check-scenarios/MCHECKSTYLE-347/checkstyle.xml")
    @Test
    public void testBlankViolationIgnore(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.MAIN, "src/main/java");

        Execution execution = execute(mojo);

        assertEquals(
                "You have 1 Checkstyle violation.",
                execution.failure(MojoException.class).getMessage());
    }

    /** check-fail: the violations of the default configuration are counted. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/check-fail")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Test
    public void testDefaultConfigurationViolation(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.MAIN, "src/main/java");

        Execution execution = execute(mojo);

        assertEquals(
                "You have 1 Checkstyle violation.",
                execution.failure(MojoException.class).getMessage());
    }

    /** MCHECKSTYLE-371: violations within maxAllowedViolations pass and the log states the maximum. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-371")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Disabled(
            "MojoExtension 4.0.0-beta-4 NPEs in WrapEvaluator.stripTokens on the null-valued nodes of the checkstyleRules XmlNode parameter, so the mojo cannot be created")
    @Test
    public void testMaxAllowedViolations(CheckstyleViolationCheckMojo mojo) throws Exception {
        Execution execution = execute(mojo);

        assertNull(execution.failure, () -> "Unexpected failure: " + execution.failure);
        assertTrue(
                execution.output.contains(
                        "[INFO] You have 1 Checkstyle violation. The maximum number of allowed violations is 10."),
                () -> "Missing the violation count in: " + execution.output);
    }

    /** MCHECKSTYLE-412: with excludeGeneratedSources, source roots below the build directory are not checked. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-412")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Test
    public void testExcludeGeneratedSources(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.MAIN, "src/main/java");
        sourceRoot(ProjectScope.MAIN, "build/generated-sources/antlr4");
        ProjectStub stub = (ProjectStub) project;
        stub.setModel(Model.newBuilder(stub.getModel())
                .build(Build.newBuilder()
                        .directory(Path.of(MojoExtension.getBasedir(), "build").toString())
                        .build())
                .build());
        Execution execution = execute(mojo);

        assertNull(execution.failure, () -> "Unexpected failure: " + execution.failure);
        assertTrue(
                execution.output.contains("[INFO] You have 0 Checkstyle violations."),
                () -> "Missing the violation count in: " + execution.output);
    }

    /** MCHECKSTYLE-214: a resource that is excluded from the jar, but matches resourceIncludes, is still checked. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-214-basedir-resource")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @MojoParameter(name = "configLocation", value = "check-scenarios/MCHECKSTYLE-214-basedir-resource/checkstyle.xml")
    @Test
    public void testResourceInBasedir(CheckstyleViolationCheckMojo mojo) throws Exception {
        resourceRoot(ProjectScope.MAIN, "src/main/resources", List.of(), List.of("*"));
        resourceRoot(ProjectScope.MAIN, ".", List.of("LICENSE.txt"), List.of());

        Execution execution = execute(mojo);

        // LICENSE.txt has 2 errors, src/main/resources/README.txt has 1 error
        assertTrue(
                execution.output.contains("There are 3 errors reported by Checkstyle"),
                () -> "Missing the error count in: " + execution.output);
        assertEquals(
                "You have 3 Checkstyle violations.",
                execution.failure(MojoException.class).getMessage());
        String result = readResult(mojo);
        assertEquals(2, count(result, "<file "), () -> "Files in the result: " + result);
    }

    /** MCHECKSTYLE-219: a missing header file is only fatal when the configuration uses it. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-219-no-license_failure")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @MojoParameter(name = "configLocation", value = "check-scenarios/MCHECKSTYLE-219-no-license_failure/checkstyle.xml")
    @Test
    public void testNoLicenseFailure(CheckstyleViolationCheckMojo mojo) throws Exception {
        Execution execution = execute(mojo);

        assertFalse(execution.output.contains("The resource 'LICENSE.txt' was found"));
        assertTrue(
                execution.output.contains("Unable to process header location: LICENSE.txt"),
                () -> "Missing the header warning in: " + execution.output);
        assertTrue(
                execution.output.contains("Checkstyle will throw exception if ${checkstyle.header.file} is used"),
                () -> "Missing the header warning in: " + execution.output);
        // Maven reports the messages of the whole cause chain
        String messages = causeMessages(execution.failure(MojoException.class));
        assertTrue(
                messages.contains("Failed during checkstyle configuration"), () -> "Unexpected failure: " + messages);
        assertTrue(
                messages.contains("Property ${checkstyle.header.file} has not been set"),
                () -> "Unexpected failure: " + messages);
    }

    /** MCHECKSTYLE-219: the default configuration does not use the header file, so a missing one is not an error. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-219-no-license_success")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Test
    public void testNoLicenseSuccess(CheckstyleViolationCheckMojo mojo) throws Exception {
        Execution execution = execute(mojo);

        assertNull(execution.failure, () -> "Unexpected failure: " + execution.failure);
        assertFalse(execution.output.contains("The resource 'LICENSE.txt' was found"));
        assertTrue(
                execution.output.contains("Unable to process header location: LICENSE.txt"),
                () -> "Missing the header warning in: " + execution.output);
        assertTrue(
                execution.output.contains("Checkstyle will throw exception if ${checkstyle.header.file} is used"),
                () -> "Missing the header warning in: " + execution.output);
    }

    /** MCHECKSTYLE-220: the debug output has no MalformedURLException. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-220")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Test
    public void testNoMalformedUrlException(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.MAIN, "src/main/java");

        Execution execution = execute(mojo);

        assertNull(execution.failure, () -> "Unexpected failure: " + execution.failure);
        assertTrue(
                execution.output.contains("headerLocation"),
                () -> "The debug output of the executor is not captured: " + execution.output);
        assertFalse(execution.output.contains("java.net.MalformedURLException"));
    }

    /** MCHECKSTYLE-70: several source roots, main and test, are all checked. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-70-multi-source-directory")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Test
    public void testMultipleSourceDirectories(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.MAIN, "src/additional/java");
        sourceRoot(ProjectScope.TEST, "src/test-additional/java");

        Execution execution = execute(mojo);

        // 2 errors in src/additional/java and 1 error in src/test-additional/java
        assertTrue(
                execution.output.contains("There are 3 errors reported by Checkstyle"),
                () -> "Missing the error count in: " + execution.output);
        assertEquals(
                "You have 3 Checkstyle violations.",
                execution.failure(MojoException.class).getMessage());
    }

    /** check-tests-only: a project with test sources only is checked, and failsOnError reports the error count. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/check-tests-only")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @Test
    public void testOnlyTestSources(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.TEST, "src/test/java");

        Execution execution = execute(mojo);

        MojoException failure = execution.failure(MojoException.class);
        assertEquals("Failed during checkstyle execution", failure.getMessage());
        assertTrue(
                Pattern.compile("There are \\d+ errors reported by Checkstyle")
                        .matcher(failure.getCause().getMessage())
                        .find(),
                () -> "Unexpected cause: " + failure.getCause().getMessage());
    }

    /** MCHECKSTYLE-337: the plain output file lists the violations. */
    @Basedir("${basedir}/target/test-classes/check-scenarios/MCHECKSTYLE-337")
    @InjectMojo(goal = "check", pom = "pom.xml")
    @MojoParameter(name = "outputFile", value = "target/checkstyle-result.txt")
    @Test
    public void testPlainOutputFile(CheckstyleViolationCheckMojo mojo) throws Exception {
        sourceRoot(ProjectScope.MAIN, "src/main/java");

        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            Execution execution = execute(mojo);

            assertEquals(
                    "You have 1 Checkstyle violation.",
                    execution.failure(MojoException.class).getMessage());
        } finally {
            Locale.setDefault(defaultLocale);
        }
        File plainOutput = new File(MojoExtension.getBasedir(), "target/checkstyle-result.txt");
        assertTrue(plainOutput.exists(), "Plain output file exists");
        String text = new String(Files.readAllBytes(plainOutput.toPath()), StandardCharsets.UTF_8);
        assertTrue(
                text.contains("'{' at column 1 should be on the previous line. [LeftCurly]"),
                () -> "Unexpected plain output: " + text);
    }

    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private String readResult(CheckstyleViolationCheckMojo mojo) throws Exception {
        File outputFile = (File) MojoExtension.getVariableValueFromObject(mojo, "outputFile");
        assertNotNull(outputFile);
        return new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }

    /**
     * Runs the mojo, recording what it logs and what the executor writes to the standard error stream.
     */
    private Execution execute(CheckstyleViolationCheckMojo mojo) throws Exception {
        Exception failure = null;
        try (StandardErrorCapture err = new StandardErrorCapture()) {
            try {
                mojo.execute();
            } catch (MojoException e) {
                failure = e;
            }
            return new Execution(pluginLog + err.text(), failure);
        }
    }

    private static final class Execution {
        private final String output;

        private final Exception failure;

        private Execution(String output, Exception failure) {
            this.output = output;
            this.failure = failure;
        }

        private <T extends Exception> T failure(Class<T> type) {
            assertNotNull(failure, "The build must fail");
            return assertThrows(type, () -> {
                throw failure;
            });
        }
    }

    private static final class RecordingLog implements Log {
        private final StringBuilder lines;

        private RecordingLog(StringBuilder lines) {
            this.lines = lines;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(CharSequence content) {}

        @Override
        public void debug(CharSequence content, Throwable error) {}

        @Override
        public void debug(Throwable error) {}

        @Override
        public void debug(Supplier<String> content) {}

        @Override
        public void debug(Supplier<String> content, Throwable error) {}

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public void info(CharSequence content, Throwable error) {
            info(content);
        }

        @Override
        public void info(Throwable error) {}

        @Override
        public void info(Supplier<String> content) {
            info(content.get());
        }

        @Override
        public void info(Supplier<String> content, Throwable error) {
            info(content.get());
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public void warn(CharSequence content, Throwable error) {
            warn(content);
        }

        @Override
        public void warn(Throwable error) {}

        @Override
        public void warn(Supplier<String> content) {
            warn(content.get());
        }

        @Override
        public void warn(Supplier<String> content, Throwable error) {
            warn(content.get());
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public void error(CharSequence content, Throwable error) {
            error(content);
        }

        @Override
        public void error(Throwable error) {}

        @Override
        public void error(Supplier<String> content) {
            error(content.get());
        }

        @Override
        public void error(Supplier<String> content, Throwable error) {
            error(content.get());
        }

        @Override
        public void info(CharSequence content) {
            lines.append("[INFO] ").append(content).append('\n');
        }

        @Override
        public void warn(CharSequence content) {
            lines.append("[WARNING] ").append(content).append('\n');
        }

        @Override
        public void error(CharSequence content) {
            lines.append("[ERROR] ").append(content).append('\n');
        }
    }
}
