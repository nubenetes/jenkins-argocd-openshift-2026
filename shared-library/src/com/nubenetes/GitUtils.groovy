package com.nubenetes

import java.io.Serializable

/**
 * GitUtils - Enterprise Git and Semantic Versioning Helper Class
 * Provides utility methods for tag validation, SHA manipulation, and GitOps commit formatting.
 */
class GitUtils implements Serializable {

    private static final long serialVersionUID = 1L

    /**
     * Validates whether a release tag strictly adheres to Semantic Versioning (e.g., v1.2.3, v2.0.0-rc1)
     * @param tag The tag string to validate
     * @return boolean true if valid, false otherwise
     */
    static boolean isValidReleaseTag(String tag) {
        if (!tag || tag.trim().isEmpty()) {
            return false
        }
        // Strict SemVer pattern prefixed with 'v'
        def semverPattern = ~/^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$/
        return tag.trim() ==~ semverPattern
    }

    /**
     * Extracts short commit hash (first 7 characters)
     * @param fullCommit 40-character Git SHA
     * @return 7-character truncated SHA
     */
    static String getShortCommit(String fullCommit) {
        if (!fullCommit) {
            return "unknown"
        }
        String cleanCommit = fullCommit.trim()
        return cleanCommit.length() >= 7 ? cleanCommit.substring(0, 7) : cleanCommit
    }

    /**
     * Generates standard GitOps audit commit message
     * @param targetEnv Target OpenShift environment (dev, staging, production)
     * @param imageTag New immutable image tag or SHA
     * @param pipelineName Originating Jenkins job name
     * @param buildNumber Jenkins build execution number
     * @return Formatted Git commit message
     */
    static String formatGitOpsCommitMessage(String targetEnv, String imageTag, String pipelineName, String buildNumber) {
        return """[GitOps Automated Promotion] [${targetEnv.toUpperCase()}] Update image tag to ${imageTag}

Triggered by Jenkins Pipeline: ${pipelineName} #${buildNumber}
Target Environment: ${targetEnv}
Immutable Artifact Tag: ${imageTag}
Compliance: Automated ALM Platform Standard (OpenShift 4.20+)
[skip ci]""".stripIndent()
    }
}
