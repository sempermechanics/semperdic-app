"""A small in-memory Firestore double.

Enough of the client surface for the repo package (`app/repo/`) to run in
tests without a live backend: documents, `.set/.update/.get/.delete`, filtered and
one-field ordered queries, `.count()`,
batches, and a pass-through transaction. Install it with `install(monkeypatch)`.

Every method that is a round trip on the real client takes its `retry` and
`timeout` keywords and records them in `CALLS` (TD-197). A call that app code
reached without them also lands in `UNTIMED`, which `conftest` checks after
every test.
"""
import operator
import sys
from datetime import datetime, timedelta, timezone

from google.api_core.exceptions import AlreadyExists, NotFound


class _Sentinel:
    # No __dict__, so that jsonable_encoder rejects a sentinel that leaks into
    # a response the way it rejects the real one (the real client's sentinel
    # is likewise not encodable); with a __dict__ it would encode as {} and a
    # write's return value reaching a response would pass here, 500 in prod.
    __slots__ = ("name",)

    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return f"<{self.name}>"


SERVER_TIMESTAMP = _Sentinel("SERVER_TIMESTAMP")
DELETE_FIELD = _Sentinel("DELETE_FIELD")
# The real client's `Query.ASCENDING` / `Query.DESCENDING` are these strings.
ASCENDING = "ASCENDING"
DESCENDING = "DESCENDING"


class ArrayUnion:
    """Server-side array union: the stored list plus each value not in it."""

    def __init__(self, values):
        self.values = list(values)


class Increment:
    """Server-side atomic increment, as used by bump_session_progress."""

    def __init__(self, value):
        self.value = value


#: (method, timeout, retry, calling module) for every round trip, newest last.
CALLS: list[tuple] = []
#: (method, app module, line) for each round trip that app code reached
#: without a timeout or a retry: a call that skipped the `_base` helpers.
UNTIMED: list[tuple] = []


def _note(method, timeout, retry):
    """Record one round trip.

    The caller is two frames up: the fake's own method, then whoever called
    it. The fake's internal calls (a batch applying its writes, `get_all`
    reading each reference) are not round trips and are skipped. A test that
    wraps a fake method must pass `retry` and `timeout` through.
    """
    frame = sys._getframe(2)
    caller = frame.f_globals.get("__name__", "")
    if caller == __name__:
        return
    CALLS.append((method, timeout, retry, caller))
    if timeout is not None and retry is not None:
        return
    while frame is not None:
        module = frame.f_globals.get("__name__", "")
        if module == __name__:
            return  # inside the fake, through a test's wrapper of a method
        if module.startswith("app."):
            UNTIMED.append((method, module, frame.f_lineno))
            return
        frame = frame.f_back


def transactional(fn):
    """Run the body immediately — no retries, which the tests do not need."""
    return fn


#: The last `SERVER_TIMESTAMP` handed out, for `_server_now`.
_last_stamp: datetime | None = None


def _server_now() -> datetime:
    """What `SERVER_TIMESTAMP` resolves to: the wall clock, but strictly
    later than the previous stamp (readiness TE3-101).

    Real commit times are microsecond and a later commit is later. Windows'
    clock on CPython 3.12 moves in 15.6 ms steps, so two writes in one tick
    used to get the same stamp, and a strict comparison of two stamps
    (`repo/reconcile.py`, a revoke against the holder's next request) read
    the wrong way round depending on the host.
    """
    global _last_stamp
    now = datetime.now(timezone.utc)
    if _last_stamp is not None and now <= _last_stamp:
        now = _last_stamp + timedelta(microseconds=1)
    _last_stamp = now
    return now


def _resolve(data: dict) -> dict:
    out = {}
    for k, v in data.items():
        if v is SERVER_TIMESTAMP:
            out[k] = _server_now()
        else:
            out[k] = v
    return out


class _Snapshot:
    def __init__(self, doc_id, data, reference):
        self.id = doc_id
        self._data = data
        self.reference = reference

    @property
    def exists(self):
        return self._data is not None

    def to_dict(self):
        return dict(self._data) if self._data is not None else None


def _project(data, field_paths):
    """The fields of [data] a projection keeps, dotted paths included, as
    Firestore's `select()` / `get_all(field_paths=...)` answer: a selected
    field the document lacks is simply absent."""
    if data is None or field_paths is None:
        return data
    out: dict = {}
    for path in field_paths:
        value, present = _Query._field_value(data, path)
        if not present:
            continue
        *parents, leaf = path.split(".")
        node = out
        for part in parents:
            node = node.setdefault(part, {})
        node[leaf] = value
    return out


class _DocRef:
    def __init__(self, store, collection, doc_id):
        self._store = store
        self._collection = collection
        self.id = doc_id

    def _bucket(self):
        return self._store._data.setdefault(self._collection, {})

    def get(self, transaction=None, *, retry=None, timeout=None):
        _note("get", timeout, retry)
        data = self._bucket().get(self.id)
        return _Snapshot(self.id, data, self)

    def set(self, data, *, retry=None, timeout=None):
        _note("set", timeout, retry)
        self._bucket()[self.id] = _resolve(data)

    def create(self, data, *, retry=None, timeout=None):
        _note("create", timeout, retry)
        # Real Firestore raises AlreadyExists; get_or_create_user catches it to
        # settle a first-sign-in race, so the double has to raise it too.
        if self.id in self._bucket():
            raise AlreadyExists(f"document {self._collection}/{self.id} exists")
        self._bucket()[self.id] = _resolve(data)

    def update(self, patch, *, retry=None, timeout=None):
        _note("update", timeout, retry)
        cur = self._bucket().get(self.id)
        if cur is None:
            # The real client raises NotFound, and callers catch that — a KeyError
            # here would let a wrong except clause pass in tests and fail in prod.
            raise NotFound(f"update on missing doc {self._collection}/{self.id}")
        for k, v in patch.items():
            if v is DELETE_FIELD:
                cur.pop(k, None)
            elif v is SERVER_TIMESTAMP:
                cur[k] = _server_now()
            elif isinstance(v, Increment):
                cur[k] = int(cur.get(k, 0)) + v.value
            elif isinstance(v, ArrayUnion):
                stored = list(cur.get(k) or [])
                cur[k] = stored + [x for x in v.values if x not in stored]
            else:
                cur[k] = v

    def delete(self, *, retry=None, timeout=None):
        _note("delete", timeout, retry)
        self._bucket().pop(self.id, None)

    @property
    def path(self):
        return f"{self._collection}/{self.id}"

    @property
    def parent(self):
        """The collection holding this document, as the real client has it."""
        return _Collection(self._store, self._collection)

    def collection(self, name):
        """Subcollection under this document (e.g. licenses/{id}/seats/{uid}),
        stored as its own flat bucket keyed by the joined path — mirrors how
        the real client addresses subcollections without needing a nested
        document tree in this double."""
        return _Collection(self._store, f"{self._collection}/{self.id}/{name}")


class _AggResult:
    def __init__(self, value):
        self.value = value


class _Query:
    def __init__(self, store, collection, filters=None, limit=None, order_by=None, start_after=None,
                 group=False, projection=None):
        self._store = store
        self._collection = collection
        self._filters = filters or []
        self._limit = limit
        self._order_by = order_by
        self._start_after = start_after
        #: A collection-group query (`client.collection_group(name)`): every
        #: collection named `name`, top-level or under any document.
        self._group = group
        #: `select(field_paths)`: the fields each result keeps. Filters and
        #: the order still read the whole stored document.
        self._projection = projection

    def _with(self, **changes):
        state = dict(filters=self._filters, limit=self._limit, order_by=self._order_by,
                     start_after=self._start_after, group=self._group,
                     projection=self._projection)
        state.update(changes)
        return _Query(self._store, self._collection, **state)

    #: Positional `.where(field, op, value)` only — the production code uses
    #: that legacy signature rather than FieldFilter precisely so this double
    #: can implement it.
    _OPS = {
        "==": operator.eq,
        "!=": operator.ne,
        "<": operator.lt,
        "<=": operator.le,
        ">": operator.gt,
        ">=": operator.ge,
        # Membership, not comparison: the left side is the stored list.
        "array_contains": lambda stored, wanted: (
            isinstance(stored, (list, tuple)) and wanted in stored
        ),
        # The stored value is one of the wanted list.
        "in": lambda stored, wanted: stored in wanted,
    }

    def where(self, field, op, value):
        assert op in self._OPS, f"fake store does not support {op!r}"
        return self._with(filters=self._filters + [(field, op, value)])

    def limit(self, n):
        return self._with(limit=n)

    def order_by(self, field, direction=None):
        """One sort field, as the repo uses. Ties break on the document id in
        the same direction, which is what Firestore's implicit `__name__`
        ordering does."""
        return self._with(order_by=(field, direction == DESCENDING))

    def start_after(self, snapshot_or_doc):
        return self._with(start_after=snapshot_or_doc)

    def select(self, field_paths):
        return self._with(projection=list(field_paths))

    @classmethod
    def _field_value(cls, data: dict, field: str):
        """Resolve `a.b.c` the way Firestore document fields do for queries."""
        cur = data
        for part in field.split("."):
            if not isinstance(cur, dict) or part not in cur:
                return None, False
            cur = cur[part]
        return cur, True

    @classmethod
    def _passes(cls, data, field, op, value) -> bool:
        """One filter clause.

        A document missing the field never matches an inequality, matching
        Firestore: a field that is absent is not indexed, so such documents
        are simply not in the result set. Getting this wrong would make an
        expired-lease sweep also pick up seats that hold no lease at all.
        """
        stored, present = cls._field_value(data, field)
        if not present:
            return op == "!=" if "!" in op else False
        if op in ("array_contains", "in"):
            return cls._OPS[op](stored, value)
        try:
            return cls._OPS[op](stored, value)
        except TypeError:
            return False  # mismatched types are never comparable in Firestore

    def _sort_key(self, doc_id, data):
        field, _desc = self._order_by or ("__name__", False)
        if field == "__name__":
            return (False, "", doc_id)
        value, present = self._field_value(data or {}, field)
        return (not present or value is None, value if present else None, doc_id)

    def _buckets(self):
        if not self._group:
            return [(self._collection, self._store._data.get(self._collection, {}))]
        return [(path, bucket) for path, bucket in self._store._data.items()
                if path.rsplit("/", 1)[-1] == self._collection]

    def _matching(self):
        rows = []
        for path, bucket in self._buckets():
            for doc_id, data in bucket.items():
                if all(self._passes(data, f, op, v) for f, op, v in self._filters):
                    rows.append(_Snapshot(doc_id, data, _DocRef(self._store, path, doc_id)))
        field, desc = self._order_by or ("__name__", False)
        if field != "__name__":
            # Firestore leaves out documents that lack the order field.
            rows = [snap for snap in rows if self._field_value(snap.to_dict() or {}, field)[1]]
        rows.sort(key=lambda snap: self._sort_key(snap.id, snap.to_dict()), reverse=desc)
        if self._start_after is not None:
            after_id = getattr(self._start_after, "id", None)
            after_data = None
            if isinstance(self._start_after, dict):
                # `start_after({"__name__": ref})`: a cursor by document id,
                # which needs no document behind it. Only the id order
                # takes one (`repo._base._after_token`).
                assert field == "__name__", "an id cursor on another order"
                named = self._start_after["__name__"]
                after_id = getattr(named, "id", named)
            elif after_id is not None and hasattr(self._start_after, "to_dict"):
                after_data = self._start_after.to_dict()
            if after_id is not None:
                after = self._sort_key(after_id, after_data)
                rows = [snap for snap in rows
                        if (self._sort_key(snap.id, snap.to_dict()) < after if desc
                            else self._sort_key(snap.id, snap.to_dict()) > after)]
        if self._limit is not None:
            rows = rows[: self._limit]
        return rows

    def stream(self, *, retry=None, timeout=None):
        _note("stream", timeout, retry)
        rows = self._matching()
        if self._projection is not None:
            rows = [_Snapshot(snap.id, _project(snap._data, self._projection), snap.reference)
                    for snap in rows]
        return iter(rows)

    def count(self):
        rows = self._matching()

        class _Countable:
            def get(_self, *, retry=None, timeout=None):
                _note("count", timeout, retry)
                return [[_AggResult(len(rows))]]

        return _Countable()


class _Collection(_Query):
    def document(self, doc_id):
        return _DocRef(self._store, self._collection, doc_id)

    @property
    def id(self):
        return self._collection.rsplit("/", 1)[-1]

    @property
    def parent(self):
        """The document a subcollection sits under; None for a top-level one."""
        if "/" not in self._collection:
            return None
        doc_path, _name = self._collection.rsplit("/", 1)
        coll, doc_id = doc_path.rsplit("/", 1)
        return _DocRef(self._store, coll, doc_id)


class _Transaction:
    """Pass-through transaction: applies writes immediately (no isolation),
    which is all the single-doc bump logic needs to be exercised."""

    def update(self, ref, patch):
        ref.update(patch)

    def set(self, ref, data):
        ref.set(data)

    def delete(self, ref):
        ref.delete()


class _Batch:
    """Buffers writes until commit(), like a real WriteBatch.

    Ordering is preserved across mixed op types — a real batch applies writes in
    the order they were added, and callers rely on that (e.g. superseding the
    previous device before setting the new one).
    """

    def __init__(self, store):
        self._ops = []

    def delete(self, ref):
        self._ops.append(("delete", ref, None))

    def set(self, ref, data, merge=False):
        self._ops.append(("set", ref, (data, merge)))

    def update(self, ref, data):
        self._ops.append(("update", ref, data))

    def commit(self, *, retry=None, timeout=None):
        _note("commit", timeout, retry)
        ops, self._ops = self._ops, []
        for kind, ref, payload in ops:
            if kind == "delete":
                ref.delete()
            elif kind == "set":
                data, merge = payload
                ref.set(data, merge=merge) if merge else ref.set(data)
            else:
                ref.update(payload)


class FakeClient:
    def __init__(self):
        self._data = {}

    def collection(self, name):
        return _Collection(self, name)

    def collection_group(self, name):
        return _Query(self, name, group=True)

    def get_all(self, references, transaction=None, *, field_paths=None, retry=None, timeout=None):
        _note("get_all", timeout, retry)
        # The real client yields in arbitrary order; reversing keeps callers
        # honest about keying results by id rather than by position.
        out = []
        for ref in reversed(list(references)):
            snap = ref.get()
            out.append(_Snapshot(snap.id, _project(snap._data, field_paths), ref))
        return out

    def batch(self):
        return _Batch(self)

    def transaction(self, **kwargs):
        # Real Client.transaction takes max_attempts; accept and ignore it so a
        # caller tuning retries does not blow up only in tests.
        return _Transaction()


def install(monkeypatch):
    """Point the repo at a fresh FakeClient and fake firestore sentinels.

    Only `app.repo._base` holds the client and binds the `firestore` module;
    every other repo module reads both from there at call time.

    Returns the FakeClient so a test can seed/inspect `client._data`.
    """
    from app.repo import _base

    # Each store starts its own clock, so a test that froze or moved time
    # cannot hold the next test's stamps at its own.
    monkeypatch.setattr(sys.modules[__name__], "_last_stamp", None)
    client = FakeClient()
    monkeypatch.setattr(_base, "_DB", client)

    class _FakeFirestore:
        SERVER_TIMESTAMP = SERVER_TIMESTAMP
        DELETE_FIELD = DELETE_FIELD
        Increment = Increment
        ArrayUnion = ArrayUnion
        transactional = staticmethod(transactional)

        class Query:
            ASCENDING = ASCENDING
            DESCENDING = DESCENDING

    monkeypatch.setattr(_base, "firestore", _FakeFirestore)
    return client
