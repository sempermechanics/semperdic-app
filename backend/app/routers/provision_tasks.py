from fastapi import APIRouter, Depends

from .. import repo, tasks
from ..models import LicenseFanoutTask, ProvisionTask
from ..services import recycle_bin
from ..services import testaccounts as test_accounts
from ..session_provision import provision_session

router = APIRouter()


@router.post("/v1/tasks/provision-session")
def provision_session_task(body: ProvisionTask, caller=Depends(tasks.tasks_caller)):
    """Cloud Tasks callback: open the Drive upload targets for one session.

    Authenticated by the OIDC token Cloud Tasks attaches (see tasks.tasks_caller)
    — not a user route. Cloud Tasks retries on a non-2xx, and provision_session
    is idempotent, so a retry resumes rather than duplicating work.
    """
    result = provision_session(body.sessionId)
    # Cloud Tasks only reads the status code; keep upload URLs out of the reply.
    return {k: v for k, v in result.items() if k != "uploads"}


@router.post(tasks.LICENSE_FANOUT_PATH)
def license_fanout_task(body: LicenseFanoutTask, caller=Depends(tasks.tasks_caller)):
    """Cloud Tasks callback: finish a licence-wide fan-out that the revoke or
    edit which recorded it did not (ADR-026). Nothing pending, the usual case,
    and a licence that is gone both answer 200 with nothing finished, so the
    queue does not retry them; a failed fan-out raises, and is retried."""
    return {"licenseId": body.licenseId, "finished": repo.finish_license_fanout(body.licenseId)}


@router.post(tasks.TEST_ACCOUNT_EXPIRY_PATH)
def expire_test_accounts_task(caller=Depends(tasks.tasks_caller)):
    """Cloud Scheduler, daily: end every test account past its `expiresAt`
    (ADR-027), each audited `test_account_expired`. OIDC as the invoker, like
    the Cloud Tasks callbacks, and off the gateway. Answers
    `{expiredTestAccounts, ended, failed}`: how many were due, and the uids
    ended and not; a failed one is due again tomorrow."""
    return test_accounts.expire_due()


@router.post(tasks.RECYCLE_BIN_PURGE_PATH)
def purge_recycle_bin_task(caller=Depends(tasks.tasks_caller)):
    """Cloud Scheduler, daily: purge every recycle-bin entry past its
    `purgeAt` (ADR-035), up to `RECYCLE_BIN_PURGE_BATCH`, each audited
    `SESSION_PURGE` with reason `expired`. OIDC as the invoker, like the
    test-account expiry job, and off the gateway. Answers
    `{due, purged, failed}`: how many were due, and the session ids purged
    and not; a failed one is back in the bin and due again tomorrow."""
    return recycle_bin.purge_due()
