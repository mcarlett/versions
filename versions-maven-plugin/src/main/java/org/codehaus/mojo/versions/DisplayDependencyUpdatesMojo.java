package org.codehaus.mojo.versions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.wagon.Wagon;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.Segment;
import org.codehaus.mojo.versions.api.VersionRetrievalException;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
import org.codehaus.mojo.versions.filtering.WildcardMatcher;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.mojo.versions.utils.DependencyComparator;
import org.codehaus.mojo.versions.utils.SegmentUtils;
import org.codehaus.plexus.util.StringUtils;

import static java.util.Collections.emptySet;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.apache.commons.lang3.StringUtils.countMatches;
import static org.codehaus.mojo.versions.api.Segment.MAJOR;
import static org.codehaus.mojo.versions.filtering.DependencyFilter.filterDependencies;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractDependenciesFromDependencyManagement;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractDependenciesFromPlugins;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractPluginDependenciesFromPluginsInPluginManagement;

/**
 * Displays all dependencies that have newer versions available.
 * It will also display dependencies which are used by a plugin or
 * defined in the plugin within a pluginManagement.
 *
 * @author Stephen Connolly
 * @since 1.0-alpha-1
 */
@Mojo(name = "display-dependency-updates", threadSafe = true)
public class DisplayDependencyUpdatesMojo extends AbstractVersionsDisplayMojo {

    // ------------------------------ FIELDS ------------------------------

    /**
     * The width to pad info messages.
     *
     * @since 1.0-alpha-1
     */
    private static final int INFO_PAD_SIZE = 72;

    /**
     * Whether to process the dependencyManagement section of the project.
     *
     * @since 1.2
     */
    @Parameter(property = "processDependencyManagement", defaultValue = "true")
    private boolean processDependencyManagement;

    /**
     * Whether to process the dependencyManagement part transitive or not.
     * In case of <code>&lt;type&gt;pom&lt;/type&gt;</code>and
     * <code>&lt;scope&gt;import&lt;/scope&gt;</code> this means
     * by default to report also the imported dependencies.
     * If processTransitive is set to <code>false</code> the report will only show
     * updates of the imported pom it self.
     *
     * @since 2.11
     */
    @Parameter(property = "processDependencyManagementTransitive", defaultValue = "true")
    private boolean processDependencyManagementTransitive;

    /**
     * Only take these artifacts into consideration.
     * <p>
     * Comma-separated list of extended GAV patterns.
     *
     * <p>
     * Extended GAV: groupId:artifactId:version:type:classifier:scope
     * </p>
     * <p>
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "dependencyManagementIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> dependencyManagementIncludes;

    /**
     * Exclude these artifacts from consideration.
     * <p>
     * Comma-separated list of extended GAV patterns.
     *
     * <p>
     * Extended GAV: groupId:artifactId:version:type:classifier:scope
     * </p>
     * <p>
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,*:*:*:*:*:provided,*:*:*:*:*:system"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "dependencyManagementExcludes")
    private List<String> dependencyManagementExcludes;

    /**
     * Whether to process the dependencies section of the project.
     *
     * @since 1.2
     */
    @Parameter(property = "processDependencies", defaultValue = "true")
    private boolean processDependencies;

    /**
     * Only take these artifacts into consideration.
     * <p>
     * Comma-separated list of extended GAV patterns.
     *
     * <p>
     * Extended GAV: groupId:artifactId:version:type:classifier:scope
     * </p>
     * <p>
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "dependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> dependencyIncludes;

    /**
     * Exclude these artifacts from consideration.
     * <p>
     * Comma-separated list of extended GAV patterns.
     *
     * <p>
     * Extended GAV: groupId:artifactId:version:type:classifier:scope
     * </p>
     * <p>
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,*:*:*:*:*:provided,*:*:*:*:*:system"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "dependencyExcludes")
    private List<String> dependencyExcludes;

    /**
     * Whether to process the dependencies sections of plugins.
     *
     * @since 2.5
     */
    @Parameter(property = "processPluginDependencies", defaultValue = "true")
    private boolean processPluginDependencies;

    /**
     * Whether to process the dependencies sections of plugins which are defined in pluginManagement.
     *
     * @since 2.5
     */
    @Parameter(property = "processPluginDependenciesInPluginManagement", defaultValue = "true")
    private boolean processPluginDependenciesInPluginManagement;

    /**
     * Whether to allow the major version number to be changed.
     *
     * <p><b>Note: {@code false} also implies {@linkplain #allowAnyUpdates}
     * to be {@code false}</b></p>
     *
     * @since 2.5
     */
    @Parameter(property = "allowMajorUpdates", defaultValue = "true")
    private boolean allowMajorUpdates = true;

    /**
     * <p>Whether to allow the minor version number to be changed.</p>
     *
     * <p><b>Note: {@code false} also implies {@linkplain #allowAnyUpdates}
     * and {@linkplain #allowMajorUpdates} to be {@code false}</b></p>
     *
     * @since 2.5
     */
    @Parameter(property = "allowMinorUpdates", defaultValue = "true")
    private boolean allowMinorUpdates = true;

    /**
     * <p>Whether to allow the incremental version number to be changed.</p>
     *
     * <p><b>Note: {@code false} also implies {@linkplain #allowAnyUpdates},
     * {@linkplain #allowMajorUpdates}, and {@linkplain #allowMinorUpdates}
     * to be {@code false}</b></p>
     *
     * @since 2.5
     */
    @Parameter(property = "allowIncrementalUpdates", defaultValue = "true")
    private boolean allowIncrementalUpdates = true;

    /**
     * Whether to allow any version change to be allowed. This keeps
     * compatibility with previous versions of the plugin.
     * If you set this to false you can control changes in version
     * number by {@link #allowMajorUpdates}, {@link #allowMinorUpdates} or
     * {@link #allowIncrementalUpdates}.
     *
     * @since 2.5
     * @deprecated This will be removed with version 3.0.0
     */
    @Deprecated
    @Parameter(property = "allowAnyUpdates", defaultValue = "true")
    private boolean allowAnyUpdates = true;

    /**
     * Whether to show additional information such as dependencies that do not need updating. Defaults to false.
     *
     * @since 2.1
     */
    @Parameter(property = "verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * <p>Only take these artifacts into consideration:<br/>
     * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns</p>
     *
     * <p>
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "pluginDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> pluginDependencyIncludes;

    /**
     * <p>Exclude these artifacts into consideration:<br/>
     * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns</p>
     *
     * <p>
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "pluginDependencyExcludes")
    private List<String> pluginDependencyExcludes;

    /**
     * <p>Only take these artifacts into consideration:<br/>
     * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns</p>
     *
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "pluginManagementDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> pluginManagementDependencyIncludes;

    /**
     * <p>Exclude these artifacts into consideration:<br/>
     * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns</p>
     *
     * <p>
     * The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     * </p>
     *
     * <p>
     * Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
     * </p>
     *
     * @since 2.12.0
     */
    @Parameter(property = "pluginManagementDependencyExcludes")
    private List<String> pluginManagementDependencyExcludes;

    // --------------------- GETTER / SETTER METHODS ---------------------

    @Inject
    public DisplayDependencyUpdatesMojo(
            RepositorySystem repositorySystem,
            org.eclipse.aether.RepositorySystem aetherRepositorySystem,
            Map<String, Wagon> wagonMap,
            Map<String, ChangeRecorder> changeRecorders) {
        super(repositorySystem, aetherRepositorySystem, wagonMap, changeRecorders);
    }

    // open for tests
    protected static boolean dependenciesMatch(Dependency dependency, Dependency managedDependency) {
        if (!managedDependency.getGroupId().equals(dependency.getGroupId())) {
            return false;
        }

        if (!managedDependency.getArtifactId().equals(dependency.getArtifactId())) {
            return false;
        }

        if (managedDependency.getScope() == null
                || Objects.equals(managedDependency.getScope(), dependency.getScope())) {
            return false;
        }

        if (managedDependency.getClassifier() == null
                || Objects.equals(managedDependency.getClassifier(), dependency.getClassifier())) {
            return false;
        }

        return dependency.getVersion() == null
                || managedDependency.getVersion() == null
                || Objects.equals(managedDependency.getVersion(), dependency.getVersion());
    }

    public boolean isProcessingDependencyManagement() {
        return processDependencyManagement;
    }

    public boolean isProcessingDependencies() {
        return processDependencies;
    }

    public boolean isProcessingPluginDependencies() {
        return processPluginDependencies;
    }

    public boolean isProcessPluginDependenciesInDependencyManagement() {
        return processPluginDependenciesInPluginManagement;
    }

    public boolean isVerbose() {
        return verbose;
    }

    // ------------------------ INTERFACE METHODS ------------------------

    // --------------------- Interface Mojo ---------------------

    /**
     * @throws org.apache.maven.plugin.MojoExecutionException when things go wrong
     * @throws org.apache.maven.plugin.MojoFailureException   when things go wrong in a very bad way
     * @see org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo#execute()
     * @since 1.0-alpha-1
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        logInit();
        validateInput();

        Set<Dependency> dependencyManagement = emptySet();

        try {
            if (isProcessingDependencyManagement()) {
                dependencyManagement = filterDependencies(
                        extractDependenciesFromDependencyManagement(
                                getProject(), processDependencyManagementTransitive, getLog()),
                        dependencyManagementIncludes,
                        dependencyManagementExcludes,
                        "Dependecy Management",
                        getLog());

                logUpdates(
                        getHelper().lookupDependenciesUpdates(dependencyManagement, false, allowSnapshots),
                        "Dependency Management");
            }
            if (isProcessingDependencies()) {
                Set<Dependency> finalDependencyManagement = dependencyManagement;
                logUpdates(
                        getHelper()
                                .lookupDependenciesUpdates(
                                        filterDependencies(
                                                getProject().getDependencies().stream()
                                                        .filter(dep -> finalDependencyManagement.stream()
                                                                .noneMatch(depMan -> dependenciesMatch(dep, depMan)))
                                                        .collect(
                                                                () -> new TreeSet<>(DependencyComparator.INSTANCE),
                                                                Set::add,
                                                                Set::addAll),
                                                dependencyIncludes,
                                                dependencyExcludes,
                                                "Dependencies",
                                                getLog()),
                                        false,
                                        allowSnapshots),
                        "Dependencies");
            }
            if (isProcessPluginDependenciesInDependencyManagement()) {
                logUpdates(
                        getHelper()
                                .lookupDependenciesUpdates(
                                        filterDependencies(
                                                extractPluginDependenciesFromPluginsInPluginManagement(getProject()),
                                                pluginManagementDependencyIncludes,
                                                pluginManagementDependencyExcludes,
                                                "Plugin Management Dependencies",
                                                getLog()),
                                        false,
                                        allowSnapshots),
                        "pluginManagement of plugins");
            }
            if (isProcessingPluginDependencies()) {
                logUpdates(
                        getHelper()
                                .lookupDependenciesUpdates(
                                        filterDependencies(
                                                extractDependenciesFromPlugins(getProject()),
                                                pluginDependencyIncludes,
                                                pluginDependencyExcludes,
                                                "Plugin Dependencies",
                                                getLog()),
                                        false,
                                        allowSnapshots),
                        "Plugin Dependencies");
            }
        } catch (VersionRetrievalException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    @Override
    protected void validateInput() throws MojoExecutionException {
        validateGAVList(dependencyIncludes, 6, "dependencyIncludes");
        validateGAVList(dependencyExcludes, 6, "dependencyExcludes");
        validateGAVList(dependencyManagementIncludes, 6, "dependencyManagementIncludes");
        validateGAVList(dependencyManagementExcludes, 6, "dependencyManagementExcludes");
        validateGAVList(pluginDependencyIncludes, 3, "pluginDependencyIncludes");
        validateGAVList(pluginDependencyExcludes, 3, "pluginDependencyExcludes");
        validateGAVList(pluginManagementDependencyIncludes, 3, "pluginManagementDependencyIncludes");
        validateGAVList(pluginManagementDependencyExcludes, 3, "pluginManagementDependencyExcludes");
    }

    /**
     * Validates a list of GAV strings
     * @param gavList list of the input GAV strings
     * @param numSections number of sections in the GAV to verify against
     * @param argumentName argument name to indicate in the exception
     * @throws MojoExecutionException if the argument is invalid
     */
    static void validateGAVList(List<String> gavList, int numSections, String argumentName)
            throws MojoExecutionException {
        if (gavList != null && gavList.stream().anyMatch(gav -> countMatches(gav, ":") >= numSections)) {
            throw new MojoExecutionException(argumentName + " should not contain more than 6 segments");
        }
    }

    private Optional<Segment> calculateUpdateScope() {
        return allowAnyUpdates
                ? empty()
                : of(SegmentUtils.determineUnchangedSegment(
                                allowMajorUpdates, allowMinorUpdates, allowIncrementalUpdates, getLog())
                        .map(s -> Segment.of(s.value() + 1))
                        .orElse(MAJOR));
    }

    private void logUpdates(Map<Dependency, ArtifactVersions> updates, String section) {
        List<String> withUpdates = new ArrayList<>();
        List<String> usingCurrent = new ArrayList<>();
        for (ArtifactVersions versions : updates.values()) {
            String left = "  " + ArtifactUtils.versionlessKey(versions.getArtifact()) + " ";
            final String current;
            ArtifactVersion latest;
            if (versions.isCurrentVersionDefined()) {
                current = versions.getCurrentVersion().toString();
                latest = versions.getNewestUpdate(calculateUpdateScope(), allowSnapshots);
            } else {
                ArtifactVersion newestVersion =
                        versions.getNewestVersion(versions.getArtifact().getVersionRange(), allowSnapshots);
                current = versions.getArtifact().getVersionRange().toString();
                latest = newestVersion == null
                        ? null
                        : versions.getNewestUpdate(newestVersion, calculateUpdateScope(), allowSnapshots);
                if (latest != null
                        && ArtifactVersions.isVersionInRange(
                                latest, versions.getArtifact().getVersionRange())) {
                    latest = null;
                }
            }
            String right = " " + (latest == null ? current : current + " -> " + latest);
            List<String> t = latest == null ? usingCurrent : withUpdates;
            if (right.length() + left.length() + 3 > INFO_PAD_SIZE + getOutputLineWidthOffset()) {
                t.add(left + "...");
                t.add(StringUtils.leftPad(right, INFO_PAD_SIZE + getOutputLineWidthOffset()));

            } else {
                t.add(StringUtils.rightPad(left, INFO_PAD_SIZE + getOutputLineWidthOffset() - right.length(), ".")
                        + right);
            }
        }

        if (isVerbose()) {
            if (usingCurrent.isEmpty()) {
                if (!withUpdates.isEmpty()) {
                    logLine(false, "No dependencies in " + section + " are using the newest version.");
                    logLine(false, "");
                }
            } else {
                logLine(false, "The following dependencies in " + section + " are using the newest version:");
                for (String s : usingCurrent) {
                    logLine(false, s);
                }
                logLine(false, "");
            }
        }

        if (withUpdates.isEmpty()) {
            if (!usingCurrent.isEmpty()) {
                logLine(false, "No dependencies in " + section + " have newer versions.");
                logLine(false, "");
            }
        } else {
            logLine(false, "The following dependencies in " + section + " have newer versions:");
            for (String withUpdate : withUpdates) {
                logLine(false, withUpdate);
            }
            logLine(false, "");
        }
    }

    /**
     * @param pom the pom to update.
     * @see org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo#update(org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader)
     * @since 1.0-alpha-1
     */
    @Override
    protected void update(ModifiedPomXMLEventReader pom) {
        // do nothing
    }
}
