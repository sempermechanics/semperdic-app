from fastapi import APIRouter, Depends, Header, HTTPException

from .. import apps, audit, errors, repo, statuses
from .. import rate_limit
from ..deps import current_user, request_app
from ..models import DeviceReg
from ..validation import require_header_identifier

router = APIRouter()


@router.post("/v1/devices/register", status_code=201)
def register_device(body: DeviceReg, user=Depends(current_user), app=Depends(request_app),
                    x_app_id: str = Header(default="")):
    # A retired app id (`apps.RETIRED`) reports the device id of the app that
    # succeeded it on the same phone, so registering would replace that app's
    # key and fail every call it signs (`bad_signature`). Nothing is written.
    if apps.is_retired(x_app_id):
        audit.record(user["uid"], body.deviceId, action="DEVICE_REGISTER", outcome="DENIED",
                     detail={"reason": "app_retired", "app": app})
        raise HTTPException(410, errors.APP_RETIRED)
    rate_limit.enforce(rate_limit.device_register_bucket, user["uid"])
    active = user.get(apps.field("activeDeviceId", app))
    # This ACCOUNT is already bound to a different device for this app → real
    # device switch, needs a reset/rebind. (Same device id re-registering after
    # a reinstall is fine — it just heals the stored public key.) Each app has
    # its own binding (ADR-010): Material Testing on the phone Semper is
    # registered on reports its own device id, and is not a conflict.
    if active and active != body.deviceId:
        raise HTTPException(409, errors.DEVICE_CONFLICT)
    # A device-lock clear just released THIS phone for a new one. Its upload
    # worker re-registers on `device_not_active`; letting it would hand the
    # account back and refuse the new phone. Installed builds read any 409 here
    # as "bound to a different device", which is what the old phone now is.
    if not active and repo.released_device_held(user, body.deviceId, app):
        audit.record(user["uid"], body.deviceId, action="DEVICE_REGISTER", outcome="DENIED",
                     detail={"reason": "released", "app": app})
        raise HTTPException(409, errors.DEVICE_CONFLICT)
    # This DEVICE is already bound to a different account. Enforces one-account-
    # per-device: a second person can't sign in on someone else's phone.
    existing = repo.get_device(body.deviceId)
    if (existing and existing.get("status") == statuses.DEVICE_ACTIVE
            and existing.get("uid") != user["uid"]):
        audit.record(user["uid"], body.deviceId, action="DEVICE_IN_USE", outcome="DENIED",
                     detail={"owner": existing.get("uid")})
        raise HTTPException(409, errors.DEVICE_IN_USE)
    healed = active == body.deviceId
    repo.register_device(user["uid"], body, app)  # upsert: refreshes the public key
    audit.record(user["uid"], body.deviceId,
                 action="DEVICE_REBIND" if healed else "DEVICE_REGISTER",
                 detail={"app": app})
    return {"deviceId": body.deviceId, "healed": healed}


@router.post("/v1/challenge")
def challenge(user=Depends(current_user), x_device_id: str = Header(default="")):
    x_device_id = require_header_identifier(
        x_device_id, name="device_id", maximum=128
    )
    rate_limit.enforce(rate_limit.challenge_bucket, user["uid"])
    return {"nonce": repo.issue_nonce(user["uid"], x_device_id)}
