package org.jclaude.runtime.policy;

/** Why a lane was reconciled without further action. */
public enum ReconcileReason {
    /** Branch already merged into main — no PR needed. */
    ALREADY_MERGED,
    /** Work superseded by another lane or direct commit. */
    SUPERSEDED,
    /** PR would be empty — all changes already landed. */
    EMPTY_DIFF,
    /** Lane manually closed by operator. */
    MANUAL_CLOSE
}
