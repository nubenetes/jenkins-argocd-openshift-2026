#!/usr/bin/env groovy

import com.nubenetes.GitUtils

/**
 * updateGitOpsManifest.groovy
 * Updates deployment Helm Chart image tags in the declarative GitOps repository.
 * Triggers ArgoCD automatic synchronization across target OpenShift cluster.
 *
 * @param config Map of configuration parameters:
 *   - gitopsRepo: (String) GitOps configuration repo URL (default: 'https://github.com/nubenetes/nubenetes-gitops-config.git')
 *   - gitCredentialsId: (String) Jenkins credential ID with write access (default: 'github-scm-token')
 *   - targetEnv: (String) Target environment ('dev', 'staging', 'production')
 *   - imageTag: (String) New immutable tag or Git SHA
 *   - appName: (String) Application folder name (default: 'nubenetes-app')
 *   - branch: (String) GitOps repo branch (default: 'main')
 */
def call(Map config = [:]) {
    def gitopsRepo       = config.get('gitopsRepo', 'https://github.com/nubenetes/nubenetes-gitops-config.git')
    def gitCredentialsId = config.get('gitCredentialsId', 'github-scm-token')
    def targetEnv        = config.get('targetEnv', 'dev').toLowerCase()
    def imageTag         = config.get('imageTag', '')
    def appName          = config.get('appName', 'nubenetes-app')
    def branch           = config.get('branch', 'main')

    if (!imageTag || imageTag.trim().isEmpty()) {
        error("[ERROR] [updateGitOpsManifest] 'imageTag' is required to update GitOps manifests.")
    }

    stage("Update GitOps Manifests [${targetEnv.toUpperCase()}]") {
        echo "========================================================"
        echo " [GITOPS] Updating Declarative Manifest for ${targetEnv}"
        echo " Repo:        ${gitopsRepo}"
        echo " Target Env:  ${targetEnv}"
        echo " App:         ${appName}"
        echo " New Tag:     ${imageTag}"
        echo "========================================================"

        def workspaceDir = "gitops-workspace-${targetEnv}-${BUILD_NUMBER}"
        def commitMsg = GitUtils.formatGitOpsCommitMessage(targetEnv, imageTag, env.JOB_NAME, env.BUILD_NUMBER)

        withCredentials([usernamePassword(
            credentialsId: gitCredentialsId,
            usernameVariable: 'GIT_USER',
            passwordVariable: 'GIT_TOKEN'
        )]) {
            // Encode token for URL injection if HTTPS
            def authRepoUrl = gitopsRepo.replace("https://", "https://${GIT_USER}:${GIT_TOKEN}@")

            sh """
                set -euo pipefail
                rm -rf "${workspaceDir}"
                
                echo "[INFO] Cloning GitOps repository..."
                git clone --depth 1 --branch "${branch}" "${authRepoUrl}" "${workspaceDir}"
                cd "${workspaceDir}"

                # Configure standard CI git author identity
                git config user.name "Nubenetes CI Automation"
                git config user.email "platform-ci@nubenetes.com"

                # Define target environment values.yaml path
                VALUES_FILE="environments/${targetEnv}/values.yaml"
                if [ ! -f "\${VALUES_FILE}" ]; then
                    VALUES_FILE="apps/${appName}/environments/${targetEnv}/values.yaml"
                fi

                if [ ! -f "\${VALUES_FILE}" ]; then
                    # Fallback standard structure: values-${targetEnv}.yaml
                    VALUES_FILE="helm/${appName}/values-${targetEnv}.yaml"
                fi

                if [ ! -f "\${VALUES_FILE}" ]; then
                    echo "[WARN] Target values file not found in subdirectories. Checking root environments/${targetEnv}.yaml"
                    mkdir -p "environments/${targetEnv}"
                    cat << 'EOF' > "environments/${targetEnv}/values.yaml"
global:
  environment: "${targetEnv}"
image:
  repository: "quay-${targetEnv}.cluster.local/${appName}"
  tag: "${imageTag}"
  pullPolicy: "IfNotPresent"
replicaCount: 2
EOF
                    VALUES_FILE="environments/${targetEnv}/values.yaml"
                fi

                echo "[INFO] Modifying image tag in \${VALUES_FILE}..."
                # Replace tag in values.yaml cleanly (supports tag: "..." or tag: ...)
                sed -i -E "s/(tag:[[:space:]]*['\\"]?)([^'\\"[:space:]]+)(['\\"]?)/\\1${imageTag}\\3/g" "\${VALUES_FILE}"

                echo "[INFO] Diff of changes:"
                git diff "\${VALUES_FILE}" || true

                # Check if there are any actual changes
                if git diff --quiet "\${VALUES_FILE}"; then
                    echo "[INFO] No changes detected in \${VALUES_FILE}. Manifest is already up-to-date."
                else
                    git add "\${VALUES_FILE}"
                    git commit -m "${commitMsg}"
                    
                    echo "[INFO] Pushing updated manifest to \${branch} branch..."
                    git push origin "${branch}"
                    echo "[SUCCESS] GitOps repository updated. ArgoCD will reconcile the ${targetEnv} spoke cluster."
                fi
                
                # Cleanup local workspace
                cd ..
                rm -rf "${workspaceDir}"
            """
        }
    }
}
