#!/usr/bin/env groovy

/**
 * promoteImageSkopeo.groovy
 * Executes direct server-to-server container image promotion via Skopeo API copy.
 * Enforces "Build Once, Promote Anywhere" with zero local disk pull on OpenShift 4.20+
 *
 * @param config Map of configuration parameters:
 *   - srcRegistry: (String) Source Quay registry URL (e.g. 'quay-dev.cluster.local')
 *   - destRegistry: (String) Destination Quay registry URL (e.g. 'quay-stg.cluster.local' or 'quay-prod.cluster.local')
 *   - imageName: (String) Image repository path (e.g. 'nubenetes/demo-app')
 *   - tag: (String) Immutable release tag (e.g. 'v1.2.3' or short SHA)
 *   - srcCredentialsId: (String) Jenkins credential ID for source registry (default: 'quay-dev-creds')
 *   - destCredentialsId: (String) Jenkins credential ID for destination registry (e.g. 'quay-stg-creds' or 'quay-prod-creds')
 *   - multiArch: (boolean) Copy all architecture variants (default: true)
 *   - tlsVerify: (boolean) Enable TLS verification (default: false for internal cluster certs)
 */
def call(Map config = [:]) {
    def srcRegistry       = config.get('srcRegistry', 'quay-dev.cluster.local')
    def destRegistry      = config.get('destRegistry', 'quay-stg.cluster.local')
    def imageName         = config.get('imageName', 'nubenetes/demo-app')
    def tag               = config.get('tag', '')
    def srcCredentialsId  = config.get('srcCredentialsId', 'quay-dev-creds')
    def destCredentialsId = config.get('destCredentialsId', 'quay-stg-creds')
    def multiArch         = config.get('multiArch', true)
    def tlsVerify         = config.get('tlsVerify', false)

    if (!tag || tag.trim().isEmpty()) {
        error("[ERROR] [promoteImageSkopeo] 'tag' parameter is mandatory for immutable promotion.")
    }

    container('skopeo') {
        stage("Promote Image (${srcRegistry} -> ${destRegistry})") {
            def srcImageRef  = "docker://${srcRegistry}/${imageName}:${tag}"
            def destImageRef = "docker://${destRegistry}/${imageName}:${tag}"

            echo "========================================================"
            echo " [PROMOTION] Skopeo Direct Registry-to-Registry Copy"
            echo " Source Image:      ${srcImageRef}"
            echo " Destination Image: ${destImageRef}"
            echo " Multi-Arch Copy:   ${multiArch}"
            echo " Zero Disk Pull:    Enforced (Pure API Stream)"
            echo "========================================================"

            withCredentials([
                usernamePassword(
                    credentialsId: srcCredentialsId,
                    usernameVariable: 'SRC_USER',
                    passwordVariable: 'SRC_PASS'
                ),
                usernamePassword(
                    credentialsId: destCredentialsId,
                    usernameVariable: 'DEST_USER',
                    passwordVariable: 'DEST_PASS'
                )
            ]) {
                def tlsFlag = tlsVerify ? "--src-tls-verify=true --dest-tls-verify=true" : "--src-tls-verify=false --dest-tls-verify=false"
                def archFlag = multiArch ? "--all" : ""

                sh """
                    set -euo pipefail

                    echo "[INFO] Inspecting source image manifest prior to transfer..."
                    skopeo inspect ${tlsFlag} \
                        --creds "\${SRC_USER}:\${SRC_PASS}" \
                        "${srcImageRef}" > /tmp/src_manifest.json

                    echo "[INFO] Source Image Digest: \$(grep -i 'Digest' /tmp/src_manifest.json || true)"

                    echo "[INFO] Executing fast API-driven Skopeo promotion..."
                    skopeo copy ${tlsFlag} ${archFlag} \
                        --src-creds "\${SRC_USER}:\${SRC_PASS}" \
                        --dest-creds "\${DEST_USER}:\${DEST_PASS}" \
                        "${srcImageRef}" \
                        "${destImageRef}"

                    echo "[INFO] Verifying destination image availability..."
                    skopeo inspect ${tlsFlag} \
                        --creds "\${DEST_USER}:\${DEST_PASS}" \
                        "${destImageRef}" > /tmp/dest_manifest.json

                    echo "[SUCCESS] Image successfully promoted: ${destImageRef}"
                """
            }

            env.PROMOTED_IMAGE_REF = destImageRef
            return destImageRef
        }
    }
}
