#!/usr/bin/env groovy

/**
 * buildAndPushDev.groovy
 * Executes daemonless, rootless container image build using Buildah in OpenShift 4.20+
 * Publishes the built artifact to the DEV Quay Registry tagged with Git Short SHA.
 *
 * @param config Map of configuration parameters:
 *   - imageName: (String) Container repository name (e.g. 'nubenetes/demo-app')
 *   - registryUrl: (String) DEV Registry host (default: 'quay-dev.cluster.local')
 *   - credentialsId: (String) Jenkins credential ID for DEV registry (default: 'quay-dev-creds')
 *   - dockerfilePath: (String) Path to Dockerfile (default: './Dockerfile')
 *   - buildContext: (String) Build context directory (default: '.')
 *   - extraBuildArgs: (String) Additional buildah bud flags (optional)
 * @return String Truncated Git commit SHA used as image tag
 */
def call(Map config = [:]) {
    def imageName      = config.get('imageName', 'nubenetes/demo-app')
    def registryUrl    = config.get('registryUrl', 'quay-dev.cluster.local')
    def credentialsId  = config.get('credentialsId', 'quay-dev-creds')
    def dockerfilePath = config.get('dockerfilePath', './Dockerfile')
    def buildContext   = config.get('buildContext', '.')
    def extraBuildArgs = config.get('extraBuildArgs', '')

    container('buildah') {
        stage('Build & Push Container (Buildah DEV)') {
            echo "========================================================"
            echo " [CI/CD] Starting Buildah Non-Root Container Build"
            echo " Image Name: ${registryUrl}/${imageName}"
            echo " Dockerfile: ${dockerfilePath}"
            echo " Context:    ${buildContext}"
            echo "========================================================"

            // 1. Calculate Git Short Commit SHA
            def shortSha = sh(
                script: 'git rev-parse --short=7 HEAD',
                returnStdout: true
            ).trim()

            def fullImageTag  = "${registryUrl}/${imageName}:${shortSha}"
            def latestImageTag = "${registryUrl}/${imageName}:dev-latest"

            echo "Target Image Tag: ${fullImageTag}"

            // 2. Perform Buildah Authentication and Build
            withCredentials([usernamePassword(
                credentialsId: credentialsId,
                usernameVariable: 'REGISTRY_USER',
                passwordVariable: 'REGISTRY_PASSWORD'
            )]) {
                sh """
                    set -euo pipefail
                    
                    # Ensure isolated containers storage directory exists
                    export STORAGE_DRIVER=vfs
                    export BUILDAH_ISOLATION=chroot
                    export HOME=/home/jenkins
                    
                    echo "[INFO] Logging into DEV Quay Registry (${registryUrl})..."
                    buildah login --tls-verify=false \
                        -u "\${REGISTRY_USER}" \
                        -p "\${REGISTRY_PASSWORD}" \
                        "${registryUrl}"

                    echo "[INFO] Building container image with Buildah..."
                    buildah bud \
                        --tls-verify=false \
                        --format docker \
                        --storage-driver=vfs \
                        ${extraBuildArgs} \
                        -f "${dockerfilePath}" \
                        -t "${fullImageTag}" \
                        -t "${latestImageTag}" \
                        "${buildContext}"

                    echo "[INFO] Pushing immutable artifact ${fullImageTag}..."
                    buildah push --tls-verify=false --storage-driver=vfs "${fullImageTag}"

                    echo "[INFO] Pushing development tag ${latestImageTag}..."
                    buildah push --tls-verify=false --storage-driver=vfs "${latestImageTag}"

                    echo "[INFO] Buildah images cleanup..."
                    buildah rmi --storage-driver=vfs --all || true

                    echo "[SUCCESS] Image successfully built and published to DEV Quay Registry."
                """
            }

            // Expose the short SHA to the pipeline execution environment
            env.BUILT_IMAGE_TAG = shortSha
            env.BUILT_IMAGE_FULL_REF = fullImageTag
            return shortSha
        }
    }
}
