"""Internal durable preparation queue. No worker, provider, HTTP API or generation permission.

The authenticated Java consumer supplies an opaque owner key and must recheck the
account/context before each operation. The caller owns commit/rollback. No external
call may occur while a transaction/row lock is held.
"""
from dataclasses import asdict
from datetime import timedelta
import re
import uuid

from sqlalchemy import BigInteger, DateTime, Integer, String, Text, func, select, text
from sqlalchemy.dialects.postgresql import JSONB, insert
from sqlalchemy.orm import Mapped, mapped_column, Session

from app.rag.models import Base
from app.rag.exploration_binding import (
    EvidenceBinding, EvidenceIntegrityError, EvidenceNoLongerAvailable,
    ExplorationEvidenceBinder,
)


class JobConflict(ValueError):
    def __init__(self): super().__init__('Exploration job conflict')


class ExplorationJob(Base):
    __tablename__ = 'ai_exploration_jobs'
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    owner_key: Mapped[str] = mapped_column(String(36), nullable=False)
    mutation_key: Mapped[str] = mapped_column(String(80), nullable=False)
    binding_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    binding: Mapped[dict] = mapped_column(JSONB, nullable=False)
    state: Mapped[str] = mapped_column(String(20), nullable=False)
    attempt: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    lease_token: Mapped[str | None] = mapped_column(String(36))
    lease_until: Mapped[object | None] = mapped_column(DateTime(timezone=True))
    reason: Mapped[str | None] = mapped_column(Text)


class ExplorationJobStore:
    def __init__(self, session: Session, binder: ExplorationEvidenceBinder):
        if binder.session is not session: raise JobConflict()
        self.db, self.binder = session, binder

    @staticmethod
    def _owner(value):
        try:
            if str(uuid.UUID(value)) != value: raise JobConflict()
        except (ValueError, TypeError, AttributeError): raise JobConflict() from None

    def enqueue(self, owner: str, mutation: str, binding: EvidenceBinding) -> int:
        self.guard_owner(owner)
        if not isinstance(mutation, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', mutation): raise JobConflict()
        # Replays are also subject to fresh evidence approval, never a permission cache.
        self.binder.revalidate(binding)
        values = dict(owner_key=owner, mutation_key=mutation, binding_hash=binding.binding_hash,
                      binding=asdict(binding), state='QUEUED', attempt=0)
        self.db.execute(insert(ExplorationJob).values(**values).on_conflict_do_nothing(
            index_elements=['owner_key', 'mutation_key']))
        row = self.db.scalar(select(ExplorationJob).where(ExplorationJob.owner_key==owner,
            ExplorationJob.mutation_key==mutation).with_for_update().execution_options(populate_existing=True))
        if row.binding_hash != binding.binding_hash or row.binding != asdict(binding): raise JobConflict()
        return row.id

    def _locked(self, owner, job):
        self.guard_owner(owner)
        row = self.db.scalar(select(ExplorationJob).where(ExplorationJob.id==job,
            ExplorationJob.owner_key==owner).with_for_update().execution_options(populate_existing=True))
        if row is None: raise JobConflict()
        return row

    def claim(self, owner: str, job: int) -> dict:
        row = self._locked(owner, job)
        now = self.db.scalar(select(func.statement_timestamp()))
        if row.state not in {'QUEUED','LEASED'}: raise JobConflict()
        if row.state=='LEASED' and row.lease_until > now: raise JobConflict()
        if row.attempt >= 3:
            row.state, row.reason = 'BLOCKED', 'ATTEMPTS_EXHAUSTED'
            row.lease_token = row.lease_until = None
            self.db.flush()
            return {'state':'BLOCKED', 'generationAuthorized':False}
        try:
            binding = EvidenceBinding(**row.binding)
            if binding.binding_hash != row.binding_hash: raise EvidenceIntegrityError()
            self.binder.revalidate(binding)
        except (EvidenceIntegrityError, EvidenceNoLongerAvailable, TypeError):
            row.state, row.reason = 'BLOCKED', 'EVIDENCE_NO_LONGER_VALID'
            row.lease_token = row.lease_until = None
            self.db.flush()
            return {'state':'BLOCKED', 'generationAuthorized':False}
        row.state, row.attempt = 'LEASED', row.attempt+1
        row.lease_token, row.lease_until = str(uuid.uuid4()), now+timedelta(seconds=60)
        self.db.flush()
        return {'state':'LEASED', 'leaseToken':row.lease_token, 'attempt':row.attempt,
                'generationAuthorized':False}

    def retry(self, owner: str, job: int, lease: str):
        row = self._locked(owner, job)
        now = self.db.scalar(select(func.statement_timestamp()))
        if row.state!='LEASED' or row.lease_token!=lease or row.lease_until<=now: raise JobConflict()
        row.state = 'QUEUED' if row.attempt<3 else 'BLOCKED'
        row.reason = 'RETRY_REQUESTED' if row.attempt<3 else 'ATTEMPTS_EXHAUSTED'
        row.lease_token = row.lease_until = None
        self.db.flush()

    def cancel(self, owner: str, job: int):
        row = self._locked(owner, job)
        row.state, row.reason = 'CANCELLED', 'CANCELLED'
        row.lease_token = row.lease_until = None
        self.db.flush()

    def purge_owner(self, owner: str):
        """Trusted account-erasure workflow only; includes saved context/evidence text."""
        self._owner(owner)
        self.db.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:owner,0))'),{'owner':owner})
        self.db.execute(text('INSERT INTO ai_exploration_erased_owners(owner_key) VALUES (:owner) ON CONFLICT DO NOTHING'),{'owner':owner})
        from sqlalchemy import delete
        self.db.execute(delete(ExplorationJob).where(ExplorationJob.owner_key==owner))

    def guard_owner(self, owner: str):
        self._owner(owner)
        self.db.execute(text('SELECT pg_advisory_xact_lock(hashtextextended(:owner,0))'),{'owner':owner})
        if self.db.scalar(text('SELECT EXISTS(SELECT 1 FROM ai_exploration_erased_owners WHERE owner_key=:owner)'),{'owner':owner}):
            raise JobConflict()
