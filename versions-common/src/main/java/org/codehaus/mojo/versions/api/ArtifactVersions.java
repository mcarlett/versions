package org.codehaus.mojo.versions.api;

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

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.Restriction;
import org.apache.maven.artifact.versioning.VersionRange;
import org.codehaus.mojo.versions.ordering.VersionComparator;

import static org.apache.commons.lang3.StringUtils.compare;

/**
 * Holds the results of a search for versions of an artifact.
 *
 * @author Stephen Connolly
 * @since 1.0-alpha-3
 */
public class ArtifactVersions extends AbstractVersionDetails implements Comparable<ArtifactVersions> {
    /**
     * The artifact that who's versions we hold details of.
     *
     * @since 1.0-alpha-3
     */
    private final Artifact artifact;

    /**
     * The available versions.
     *
     * @since 1.0-alpha-3
     */
    private final SortedSet<ArtifactVersion> versions;

    /**
     * The version comparison rule that is used for this artifact.
     *
     * @since 1.0-alpha-3
     */
    private final VersionComparator versionComparator;

    /**
     * Creates a new {@link ArtifactVersions} instance.
     *
     * @param artifact The artifact.
     * @param versions The versions.
     * @param versionComparator The version comparison rule.
     * @since 1.0-alpha-3
     */
    public ArtifactVersions(Artifact artifact, List<ArtifactVersion> versions, VersionComparator versionComparator) {
        this.artifact = artifact;
        this.versionComparator = versionComparator;
        this.versions = new TreeSet<>(versionComparator);
        this.versions.addAll(versions);
        if (artifact.getVersion() != null) {
            setCurrentVersion(artifact.getVersion());
        }
    }

    /**
     * Creates a new {@link ArtifactVersions} instance as shallow copy of the other
     *
     * @param other other object to be linked to
     * @since 2.13.0
     */
    public ArtifactVersions(ArtifactVersions other) {
        artifact = other.artifact;
        versionComparator = other.versionComparator;
        versions = other.versions;
        setCurrentVersion(other.getCurrentVersion());
    }

    @SuppressWarnings("checkstyle:InnerAssignment")
    public int compareTo(ArtifactVersions that) {
        int rv;
        return this == that
                ? 0
                : that == null || getClass() != that.getClass()
                        ? 1
                        : (rv = compare(getGroupId(), that.getGroupId())) != 0
                                ? rv
                                : (rv = compare(getArtifactId(), that.getArtifactId())) != 0
                                        ? rv
                                        : compare(getVersion(), that.getVersion());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ArtifactVersions)) {
            return false;
        }

        ArtifactVersions that = (ArtifactVersions) o;

        return new EqualsBuilder()
                .append(getArtifact(), that.getArtifact())
                .append(getVersions(true), that.getVersions(true))
                .append(getVersionComparator(), that.getVersionComparator())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(getArtifact())
                .append(getVersions(true))
                .append(getVersionComparator())
                .toHashCode();
    }

    /**
     * Checks if the version is in the range (and ensures that the range respects the <code>-!</code> syntax to rule out
     * any qualifiers from range boundaries).
     *
     * @param version the version to check.
     * @param range the range to check.
     * @return <code>true</code> if and only if the version is in the range.
     * @since 1.3
     */
    public static boolean isVersionInRange(ArtifactVersion version, VersionRange range) {
        if (!range.containsVersion(version)) {
            return false;
        }

        for (Restriction r : range.getRestrictions()) {
            if (r.containsVersion(version)) {
                // check for the -! syntax
                if (!r.isLowerBoundInclusive() && r.getLowerBound() != null) {
                    String s = r.getLowerBound().toString();
                    if (s.endsWith("-!") && version.toString().startsWith(s.substring(0, s.length() - 2))) {
                        return false;
                    }
                }
                if (!r.isUpperBoundInclusive() && r.getUpperBound() != null) {
                    String s = r.getUpperBound().toString();
                    if (s.endsWith("-!") && version.toString().startsWith(s.substring(0, s.length() - 2))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Returns the artifact who's version information we are holding.
     *
     * @return the artifact who's version information we are holding.
     * @since 1.0-alpha-3
     */
    public Artifact getArtifact() {
        return artifact;
    }

    /**
     * Returns the groupId of the artifact which versions we are holding.
     *
     * @return the groupId.
     * @since 1.0-alpha-3
     */
    public String getGroupId() {
        return getArtifact().getGroupId();
    }

    /**
     * Returns the artifactId of the artifact which versions we are holding.
     *
     * @return the artifactId.
     * @since 1.0-alpha-3
     */
    public String getArtifactId() {
        return getArtifact().getArtifactId();
    }

    /**
     * Returns the artifactId of the artifact which versions we are holding.
     *
     * @return current version
     * @since 2.13.0
     */
    public String getVersion() {
        return getArtifact().getVersion();
    }

    public ArtifactVersion[] getVersions(boolean includeSnapshots) {
        return includeSnapshots
                ? versions.toArray(new ArtifactVersion[0])
                : versions.stream()
                        .filter(v -> !ArtifactUtils.isSnapshot(v.toString()))
                        .toArray(ArtifactVersion[]::new);
    }

    /**
     * Says whether the versions present in the {@link ArtifactVersions} collection are empty.
     * If {@code includeSnapshots} is {@code true}, snapshots will not counted, which means that
     * the method will only count release versions.
     *
     * @param includeSnapshots {@code includeSnapshots} is {@code true}, snapshots will not counted, which means that
     *      * the method will only count release versions.
     * @return if {@code includeSnapshots} is {@code true}, returns {@code true} if there are no versions.
     * if {@code includeSnapshots} is {@code false}, returns {@code true} if there are no releases.
     */
    public boolean isEmpty(boolean includeSnapshots) {
        return includeSnapshots
                ? versions.isEmpty()
                // the below means: the only versions that could be present in the collection
                // are snapshots
                : versions.stream().map(Object::toString).allMatch(ArtifactUtils::isSnapshot);
    }

    public VersionComparator getVersionComparator() {
        return versionComparator;
    }

    /**
     * {@inheritDoc}
     */
    public String toString() {
        return "ArtifactVersions" + "{artifact=" + artifact + ", versions=" + versions + ", versionComparator="
                + versionComparator + '}';
    }
}
