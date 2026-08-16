// ==============================================================================
// Jenkins Job DSL - Idempotent Enterprise Pipeline Provisioning
// Repository: github.com/nubenetes/jenkins-argocd-openshift-2026
// OpenShift 4.20+ Multi-Cluster Hub-Spoke GitOps Architecture
// ==============================================================================

// ------------------------------------------------------------------------------
// 1. Folders Definition (Idempotent creation)
// ------------------------------------------------------------------------------
def environments = [
    [name: 'PROYECTO-DEV', description: 'Development Environment - Continuous Integration & Automatic Dev Rollout'],
    [name: 'PROYECTO-STAGING', description: 'Staging Environment - Immutable Binary Promotion & Pre-Production Testing'],
    [name: 'PROYECTO-PRODUCTION', description: 'Production Environment - Controlled Immutable Binary Promotion & GitOps Rollout']
]

environments.each { envItem ->
    folder(envItem.name) {
        description(envItem.description)
        displayName(envItem.name)
    }
}

// ------------------------------------------------------------------------------
// Global Constants
// ------------------------------------------------------------------------------
def gitRepositoryUrl = 'https://github.com/nubenetes/jenkins-argocd-openshift-2026.git'
def gitCredentialsId = 'github-scm-token'
def jenkinsfilePath  = 'pipelines/Jenkinsfile'

// ------------------------------------------------------------------------------
// 2. PROYECTO-DEV Pipeline: 01-build-and-deploy-dev
// Triggered automatically on SCM Webhook / Git Push
// ------------------------------------------------------------------------------
pipelineJob('PROYECTO-DEV/01-build-and-deploy-dev') {
    displayName('01 - Build & Deploy to DEV')
    description('''
        <b>Development Pipeline:</b><br/>
        - Automatically triggered on code push.<br/>
        - Builds container image using Buildah in rootless non-privileged container.<br/>
        - Pushes to DEV Quay Registry with Git Short SHA.<br/>
        - Updates GitOps configuration repo to trigger ArgoCD synchronization in DEV cluster.
    '''.stripIndent())

    parameters {
        stringParam('TARGET_ENV', 'dev', 'Target deployment environment (fixed to dev)')
        booleanParam('RUN_SECURITY_SCAN', true, 'Enable container vulnerability scanning')
    }

    properties {
        githubProjectProperty {
            projectUrlString(gitRepositoryUrl)
            displayName('Nubenetes Application CI/CD')
        }
        buildDiscarder {
            strategy {
                logRotator {
                    daysToKeepStr('14')
                    numToKeepStr('30')
                    artifactDaysToKeepStr('7')
                    artifactNumToKeepStr('10')
                }
            }
        }
        disableConcurrentBuilds()
    }

    triggers {
        githubPush()
        scm('H/5 * * * *')
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(gitRepositoryUrl)
                        credentials(gitCredentialsId)
                    }
                    branch('*/main')
                }
            }
            scriptPath(jenkinsfilePath)
            lightweight(true)
        }
    }
}

// ------------------------------------------------------------------------------
// 3. PROYECTO-STAGING Pipeline: 02-promote-to-staging
// Parameterized Promotion using Immutable Git Release Tags
// ------------------------------------------------------------------------------
pipelineJob('PROYECTO-STAGING/02-promote-to-staging') {
    displayName('02 - Promote to STAGING')
    description('''
        <b>Staging Promotion Pipeline:</b><br/>
        - Parameterized promotion enforcing immutable release tags (e.g. v1.2.3).<br/>
        - Promotes container image directly from Quay DEV to Quay STG via Skopeo API copy.<br/>
        - Eliminates rebuilds ("Build Once, Promote Anywhere").<br/>
        - Updates GitOps staging manifests to trigger declarative ArgoCD rollout on STAGING spoke cluster.
    '''.stripIndent())

    parameters {
        stringParam('TARGET_ENV', 'staging', 'Target deployment environment (fixed to staging)')
        
        // Git Parameter Plugin configuration for immutable tags
        gitParameter {
            name('RELEASE_TAG')
            type('PT_TAG')
            defaultValue('')
            description('Select immutable Semantic Version release tag to promote to STAGING')
            branch('')
            branchFilter('')
            tagFilter('^v[0-9]+\\.[0-9]+\\.[0-9]+$')
            sortMode('DESCENDING_SMART')
            selectedValue('TOP')
            useRepository(gitRepositoryUrl)
            quickFilterEnabled(true)
            listSize('10')
        }
    }

    properties {
        buildDiscarder {
            strategy {
                logRotator {
                    daysToKeepStr('30')
                    numToKeepStr('50')
                    artifactDaysToKeepStr('14')
                    artifactNumToKeepStr('20')
                }
            }
        }
        disableConcurrentBuilds()
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(gitRepositoryUrl)
                        credentials(gitCredentialsId)
                    }
                    branch('*/main')
                }
            }
            scriptPath(jenkinsfilePath)
            lightweight(true)
        }
    }
}

// ------------------------------------------------------------------------------
// 4. PROYECTO-PRODUCTION Pipeline: 03-promote-to-production
// Parameterized Production Promotion with Strict Governance
// ------------------------------------------------------------------------------
pipelineJob('PROYECTO-PRODUCTION/03-promote-to-production') {
    displayName('03 - Promote to PRODUCTION')
    description('''
        <b>Production Promotion Pipeline:</b><br/>
        - Parameterized production promotion enforcing audited immutable release tags.<br/>
        - Promotes container image directly from Quay STG to Quay PROD via Skopeo API copy.<br/>
        - Guarantees 100% byte-for-byte binary equivalence with staging verified artifact.<br/>
        - Updates GitOps production manifests to trigger declarative ArgoCD rollout on PROD spoke cluster.
    '''.stripIndent())

    parameters {
        stringParam('TARGET_ENV', 'production', 'Target deployment environment (fixed to production)')
        
        // Git Parameter Plugin configuration for immutable tags
        gitParameter {
            name('RELEASE_TAG')
            type('PT_TAG')
            defaultValue('')
            description('Select verified immutable Semantic Version release tag to promote to PRODUCTION')
            branch('')
            branchFilter('')
            tagFilter('^v[0-9]+\\.[0-9]+\\.[0-9]+$')
            sortMode('DESCENDING_SMART')
            selectedValue('TOP')
            useRepository(gitRepositoryUrl)
            quickFilterEnabled(true)
            listSize('10')
        }
    }

    properties {
        buildDiscarder {
            strategy {
                logRotator {
                    daysToKeepStr('90')
                    numToKeepStr('100')
                    artifactDaysToKeepStr('30')
                    artifactNumToKeepStr('50')
                }
            }
        }
        disableConcurrentBuilds()
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(gitRepositoryUrl)
                        credentials(gitCredentialsId)
                    }
                    branch('*/main')
                }
            }
            scriptPath(jenkinsfilePath)
            lightweight(true)
        }
    }
}
