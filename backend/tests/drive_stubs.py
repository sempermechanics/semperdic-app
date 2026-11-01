"""Drive as account erasure sees it, for route tests that are not about Drive.

Erasure (`services.account._purge_drive`) deletes every folder named after the
account (`drive.find_user_folders`, plus the stored pointer), then probes each
session's stored folder and deletes the ones still there. `erasure_drive`
stands in for those four calls: the session folders sit inside the user
folder, so once any user folder is deleted they probe MISSING; with no user
folder they probe ALIVE and are deleted one by one. `test_drive_http.py` runs
the real calls against a fake Drive.
"""
import threading

from app import drive


class ErasureDrive:
    def __init__(self, monkeypatch, user_folders=()):
        self.user_folders = list(user_folders)
        self.deleted: list[str] = []
        self.probed: list[list[str]] = []
        self._lock = threading.Lock()
        monkeypatch.setattr(drive, "access_token", lambda: "tok")
        monkeypatch.setattr(drive, "delete_file", self.delete)
        monkeypatch.setattr(drive, "find_user_folders", lambda _t, _uid, **_k: list(self.user_folders))
        monkeypatch.setattr(drive, "probe_files", self.probe)

    def delete(self, _token, fid):
        with self._lock:
            self.deleted.append(fid)

    def probe(self, _token, ids, **_kw):
        ids = [i for i in dict.fromkeys(ids) if i]
        self.probed.append(ids)
        gone = drive.MISSING if self.deleted else drive.ALIVE
        return {i: gone for i in ids}


def erasure_drive(monkeypatch, user_folders=()) -> ErasureDrive:
    return ErasureDrive(monkeypatch, user_folders)
