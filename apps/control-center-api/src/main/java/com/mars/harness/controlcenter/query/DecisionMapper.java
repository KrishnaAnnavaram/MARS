package com.mars.harness.controlcenter.query;

import com.mars.harness.controlcenter.api.dto.DecisionDtos;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.decision.DecisionActor;

import java.util.List;

/** Decisions as the API shows them, with integrity and authentication stated separately and honestly. */
public final class DecisionMapper {

    private DecisionMapper() {
    }

    public static DecisionDtos.DecisionView view(Decision d, List<String> tampered, List<Decision> all) {
        // the approval index is in recording order; a later decision of the same kind supersedes this one
        int position = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).decisionId().equals(d.decisionId())) {
                position = i;
            }
        }
        boolean superseded = false;
        for (int i = position + 1; position >= 0 && i < all.size(); i++) {
            Decision later = all.get(i);
            if (later.type() == d.type() && (d.proposalId() == null || d.proposalId().equals(later.proposalId()))) {
                superseded = true;
            }
        }
        return new DecisionDtos.DecisionView(d.decisionId(), d.type().name(), d.selected(), d.recommendation(),
                d.proposalId(), d.proposalHash(), d.planId(), d.planHash(), d.assessmentHash(), d.baselineSeal(),
                d.ledgerHead(), d.findingIds(), d.actor(), d.role(), d.actorAuthentication(),
                authenticationNote(d.actorAuthentication()), d.rationale(), d.timestamp(), d.policyVersion(),
                d.integrityHash(), tampered.contains(d.decisionId()) ? "TAMPERED" : "VERIFIED", superseded);
    }

    public static String authenticationNote(String authentication) {
        if (authentication == null || DecisionActor.LOCALLY_ASSERTED.equals(authentication)) {
            return "Locally asserted: the actor typed their own name at the CLI. Nothing verified who they are.";
        }
        if (DecisionActor.DEVELOPMENT_ASSERTED.equals(authentication)) {
            return "Development identity: a Control Center dev user, not authenticated by an identity provider.";
        }
        if (authentication.startsWith(DecisionActor.OIDC_PREFIX)) {
            return "Authenticated by the Control Center with a token from "
                    + authentication.substring(DecisionActor.OIDC_PREFIX.length()) + ".";
        }
        return "Unrecognised authentication statement.";
    }
}
