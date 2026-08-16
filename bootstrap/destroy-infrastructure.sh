#!/usr/bin/env bash
# ==============================================================================
# Infrastructure Decommissioning & Cleanup Script
# Repository: github.com/nubenetes/jenkins-argocd-openshift-2026
#
# Hygienically dismantles all deployed OpenShift resources, Helm releases,
# ServiceAccounts, and namespaces created by bootstrap/deploy-infrastructure.sh.
# ==============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $1"; }
log_section() {
    echo -e "\n${CYAN}================================================================================${NC}"
    echo -e "${CYAN} $1${NC}"
    echo -e "${CYAN}================================================================================${NC}"
}

JENKINS_NAMESPACE="jenkins-infra"
ARGOCD_NAMESPACE="openshift-gitops"
FORCE="${1:-}"

if [[ "${FORCE}" != "-f" && "${FORCE}" != "--force" && "${FORCE}" != "-y" ]]; then
    echo -e "${YELLOW}WARNING: This script will completely delete Jenkins, ArgoCD Apps, and Namespaces.${NC}"
    read -p "Are you sure you want to proceed with full decommissioning? (y/N): " -r CONFIRM
    if [[ ! "${CONFIRM}" =~ ^[Yy]$ ]]; then
        echo "Decommissioning aborted by user."
        exit 0
    fi
fi

# ------------------------------------------------------------------------------
# 1. Remove ArgoCD Applications
# ------------------------------------------------------------------------------
log_section "STEP 1: Deleting ArgoCD Applications"
if oc get project "${ARGOCD_NAMESPACE}" &>/dev/null; then
    log_info "Removing ArgoCD root application if present..."
    oc delete application nubenetes-gitops-root -n "${ARGOCD_NAMESPACE}" --ignore-not-found=true --timeout=60s || true
fi

# ------------------------------------------------------------------------------
# 2. Uninstall Jenkins Helm Chart Release
# ------------------------------------------------------------------------------
log_section "STEP 2: Uninstalling Jenkins Helm Chart Release"
if helm status jenkins -n "${JENKINS_NAMESPACE}" &>/dev/null; then
    log_info "Uninstalling Helm release 'jenkins' from namespace '${JENKINS_NAMESPACE}'..."
    helm uninstall jenkins -n "${JENKINS_NAMESPACE}" || true
    log_success "Helm release 'jenkins' uninstalled."
else
    log_info "No active Helm release 'jenkins' found."
fi

# ------------------------------------------------------------------------------
# 3. Clean RBAC Policies & SCC Bindings
# ------------------------------------------------------------------------------
log_section "STEP 3: Revoking RBAC and Security Context Constraints Bindings"
oc adm policy remove-scc-from-user restricted-v2 -z jenkins-controller-sa -n "${JENKINS_NAMESPACE}" 2>/dev/null || true
oc adm policy remove-scc-from-user restricted-v2 -z jenkins-agent-sa -n "${JENKINS_NAMESPACE}" 2>/dev/null || true
oc adm policy remove-role-from-user edit -z jenkins-agent-sa -n "nubenetes-dev" 2>/dev/null || true

# ------------------------------------------------------------------------------
# 4. Delete OpenShift Projects & Namespaces
# ------------------------------------------------------------------------------
log_section "STEP 4: Deleting Namespaces and Associated Persistent Volumes"

delete_project_safe() {
    local ns="$1"
    if oc get project "${ns}" &>/dev/null; then
        log_info "Deleting OpenShift project '${ns}'..."
        oc delete project "${ns}" --ignore-not-found=true --wait=false
    else
        log_info "Project '${ns}' not found. Skipping."
    fi
}

delete_project_safe "nubenetes-dev"
delete_project_safe "nubenetes-staging"
delete_project_safe "nubenetes-production"
delete_project_safe "${JENKINS_NAMESPACE}"

# ------------------------------------------------------------------------------
# 5. Final Confirmation
# ------------------------------------------------------------------------------
log_section "CLEANUP VERIFICATION"
log_success "All OpenShift CI/CD immutable infrastructure components have been scheduled for deletion."
log_info "Run 'oc get projects' to verify background namespace termination."
