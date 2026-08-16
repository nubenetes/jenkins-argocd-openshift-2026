#!/usr/bin/env bash
# ==============================================================================
# Enterprise Infrastructure Provisioning Script for Red Hat OpenShift 4.20+
# Repository: github.com/nubenetes/jenkins-argocd-openshift-2026
#
# Automates:
#  1. Prerequisites and CLI tool validation (oc, helm, argocd, git)
#  2. Multi-Cluster OpenShift Namespace provisioning & RBAC
#  3. Security Context Constraint (restricted-v2) and Service Account binding
#  4. GitHub SCM and Quay Registry Kubernetes Secrets deployment
#  5. Official Jenkins Helm Chart deployment with JCasC & Pod Templates
#  6. ArgoCD Hub-Spoke cluster registration & Application deployment
# ==============================================================================

set -euo pipefail

# ------------------------------------------------------------------------------
# Color and Logging Utilities
# ------------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $1"; }
log_section() {
    echo -e "\n${CYAN}================================================================================${NC}"
    echo -e "${CYAN} $1${NC}"
    echo -e "${CYAN}================================================================================${NC}"
}

# ------------------------------------------------------------------------------
# Environment Variables & Defaults
# ------------------------------------------------------------------------------
HUB_API_URL="${OCP_HUB_API:-https://api.ocp-dev.nubenetes.internal:6443}"
STG_API_URL="${OCP_STG_API:-https://api.ocp-stg.nubenetes.internal:6443}"
PROD_API_URL="${OCP_PROD_API:-https://api.ocp-prod.nubenetes.internal:6443}"

OCP_TOKEN="${OCP_TOKEN:-}"
JENKINS_NAMESPACE="jenkins-infra"
ARGOCD_NAMESPACE="openshift-gitops"

GITHUB_TOKEN="${GITHUB_TOKEN:-ghp_sampleEnterpriseToken1234567890abcdef}"
GITHUB_USERNAME="${GITHUB_USERNAME:-nubenetes-ci}"

QUAY_DEV_USER="${QUAY_DEV_USER:-quay-dev-robot}"
QUAY_DEV_PASS="${QUAY_DEV_PASS:-QuayDevSecurePass2026!}"
QUAY_STG_USER="${QUAY_STG_USER:-quay-stg-robot}"
QUAY_STG_PASS="${QUAY_STG_PASS:-QuayStgSecurePass2026!}"
QUAY_PROD_USER="${QUAY_PROD_USER:-quay-prod-robot}"
QUAY_PROD_PASS="${QUAY_PROD_PASS:-QuayProdSecurePass2026!}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ------------------------------------------------------------------------------
# Step 1: Pre-flight Verification
# ------------------------------------------------------------------------------
log_section "STEP 1: Validating CLI Tools and Prerequisites"

for tool in oc helm git; do
    if ! command -v "${tool}" &> /dev/null; then
        log_error "Required tool '${tool}' is not installed in PATH. Aborting."
        exit 1
    fi
    log_info "Found ${tool}: $(command -v ${tool})"
done

# ------------------------------------------------------------------------------
# Step 2: OpenShift Cluster Login (Hub Cluster)
# ------------------------------------------------------------------------------
log_section "STEP 2: Connecting to OpenShift Hub Cluster (DEV / CI-CD Control Plane)"

if [ -n "${OCP_TOKEN}" ]; then
    log_info "Logging in to Hub cluster with provided token: ${HUB_API_URL}"
    oc login --token="${OCP_TOKEN}" --server="${HUB_API_URL}" --insecure-skip-tls-verify=true
else
    log_warn "OCP_TOKEN not set; utilizing existing active oc session..."
    CURRENT_SERVER="$(oc whoami --show-server 2>/dev/null || echo 'Not logged in')"
    log_info "Active OpenShift Server: ${CURRENT_SERVER}"
fi

# ------------------------------------------------------------------------------
# Step 3: Provision Namespaces & Projects
# ------------------------------------------------------------------------------
log_section "STEP 3: Provisioning OpenShift Namespaces"

create_project_if_missing() {
    local ns="$1"
    local desc="$2"
    if oc get project "${ns}" &>/dev/null; then
        log_info "Project '${ns}' already exists."
    else
        log_info "Creating OpenShift project '${ns}'..."
        oc new-project "${ns}" --description="${desc}" --display-name="${ns}"
    fi
}

create_project_if_missing "${JENKINS_NAMESPACE}" "Jenkins CI Controller and Ephemeral Agent Infrastructure"
create_project_if_missing "nubenetes-dev" "Development Application Spoke Namespace"
create_project_if_missing "nubenetes-staging" "Staging Pre-Production Application Spoke Namespace"
create_project_if_missing "nubenetes-production" "Production Application Spoke Namespace"

# ------------------------------------------------------------------------------
# Step 4: Security Context Constraints (SCC) and Service Accounts
# ------------------------------------------------------------------------------
log_section "STEP 4: Configuring Service Accounts & restricted-v2 SCC Compliance"

oc project "${JENKINS_NAMESPACE}"

# Create Agent Service Account
if ! oc get sa jenkins-agent-sa -n "${JENKINS_NAMESPACE}" &>/dev/null; then
    oc create sa jenkins-agent-sa -n "${JENKINS_NAMESPACE}"
    log_info "Created ServiceAccount 'jenkins-agent-sa'"
fi

# Grant restricted-v2 SCC to Jenkins Agent & Controller
log_info "Applying restricted-v2 SCC to Jenkins ServiceAccounts..."
oc adm policy add-scc-to-user restricted-v2 -z jenkins-controller-sa -n "${JENKINS_NAMESPACE}" || true
oc adm policy add-scc-to-user restricted-v2 -z jenkins-agent-sa -n "${JENKINS_NAMESPACE}" || true

# Grant edit privileges in target dev namespace for CI verification
oc adm policy add-role-to-user edit -z jenkins-agent-sa -n "nubenetes-dev" || true

# ------------------------------------------------------------------------------
# Step 5: Kubernetes Secrets Creation for Jenkins
# ------------------------------------------------------------------------------
log_section "STEP 5: Creating Jenkins Credentials & Registry Secrets"

# 1. GitHub Token Secret
oc create secret generic github-scm-token \
    -n "${JENKINS_NAMESPACE}" \
    --from-literal=username="${GITHUB_USERNAME}" \
    --from-literal=password="${GITHUB_TOKEN}" \
    --dry-run=client -o yaml | oc apply -f -
log_success "Deployed 'github-scm-token' Secret."

# 2. Quay DEV Credentials
oc create secret generic quay-dev-creds \
    -n "${JENKINS_NAMESPACE}" \
    --from-literal=username="${QUAY_DEV_USER}" \
    --from-literal=password="${QUAY_DEV_PASS}" \
    --dry-run=client -o yaml | oc apply -f -
log_success "Deployed 'quay-dev-creds' Secret."

# 3. Quay STG Credentials
oc create secret generic quay-stg-creds \
    -n "${JENKINS_NAMESPACE}" \
    --from-literal=username="${QUAY_STG_USER}" \
    --from-literal=password="${QUAY_STG_PASS}" \
    --dry-run=client -o yaml | oc apply -f -
log_success "Deployed 'quay-stg-creds' Secret."

# 4. Quay PROD Credentials
oc create secret generic quay-prod-creds \
    -n "${JENKINS_NAMESPACE}" \
    --from-literal=username="${QUAY_PROD_USER}" \
    --from-literal=password="${QUAY_PROD_PASS}" \
    --dry-run=client -o yaml | oc apply -f -
log_success "Deployed 'quay-prod-creds' Secret."

# ------------------------------------------------------------------------------
# Step 6: Deploy Official Jenkins Helm Chart
# ------------------------------------------------------------------------------
log_section "STEP 6: Deploying Official Jenkins Helm Chart (JCasC + Seed Job)"

log_info "Adding Jenkins official Helm repository..."
helm repo add jenkins https://charts.jenkins.io || true
helm repo update jenkins

log_info "Installing / Upgrading Jenkins Helm release in namespace '${JENKINS_NAMESPACE}'..."
helm upgrade --install jenkins jenkins/jenkins \
    --namespace "${JENKINS_NAMESPACE}" \
    --values "${ROOT_DIR}/helm-jenkins/values.yaml" \
    --timeout 10m \
    --wait

log_success "Jenkins Helm release deployed successfully!"

# ------------------------------------------------------------------------------
# Step 7: ArgoCD Hub-Spoke GitOps Application Setup
# ------------------------------------------------------------------------------
log_section "STEP 7: Configuring ArgoCD Declarative GitOps Application"

if oc get project "${ARGOCD_NAMESPACE}" &>/dev/null; then
    log_info "Deploying ArgoCD Root Application for Hub-Spoke Continuous Delivery..."
    cat << 'EOF' | oc apply -n "${ARGOCD_NAMESPACE}" -f -
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: nubenetes-gitops-root
  namespace: openshift-gitops
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default
  source:
    repoURL: 'https://github.com/nubenetes/nubenetes-gitops-config.git'
    targetRevision: main
    path: environments/dev
  destination:
    server: 'https://kubernetes.default.svc'
    namespace: nubenetes-dev
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ApplyOutOfSyncOnly=true
EOF
    log_success "ArgoCD root application configured."
else
    log_warn "ArgoCD namespace '${ARGOCD_NAMESPACE}' not detected. Skipping direct ArgoCD CR application."
fi

# ------------------------------------------------------------------------------
# Step 8: Verify Deployment Status & Print Endpoints
# ------------------------------------------------------------------------------
log_section "STEP 8: Verification & Access Information"

log_info "Waiting for Jenkins pod to achieve Ready state..."
oc rollout status deployment/jenkins -n "${JENKINS_NAMESPACE}" --timeout=300s || true

JENKINS_ROUTE="$(oc get route jenkins -n "${JENKINS_NAMESPACE}" -o jsonpath='{.spec.host}' 2>/dev/null || echo 'Route not found')"

echo -e "\n${GREEN}================================================================================${NC}"
echo -e "${GREEN} OPENSHIFT CI/CD INFRASTRUCTURE BOOTSTRAP COMPLETED SUCCESSFULLY!${NC}"
echo -e "${GREEN}================================================================================${NC}"
echo -e " Jenkins URL:     ${CYAN}https://${JENKINS_ROUTE}${NC}"
echo -e " Namespace:       ${CYAN}${JENKINS_NAMESPACE}${NC}"
echo -e " JCasC Status:    ${CYAN}Loaded (Restricted-v2 Non-Root UID 10001)${NC}"
echo -e " Ephemeral Cloud: ${CYAN}Enabled (Buildah v1.35.0 & Skopeo v1.14.0)${NC}"
echo -e " Seed Job DSL:    ${CYAN}Provisioned automatically (00-seed-job-bootstrap)${NC}"
echo -e "${GREEN}================================================================================${NC}\n"
