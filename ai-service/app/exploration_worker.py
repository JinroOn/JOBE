"""Explicitly invoked MOCK worker engine; no provider, scheduler, or HTTP execute route.

Production dispatch must first wire fresh Java owner/account/context authorization.
PostgreSQL completion protects leases, cancellation, erasure and current evidence;
it does not prove a still-active account in the other database.
"""
from copy import deepcopy
from sqlalchemy import select,func
from app.exploration_jobs import ExplorationJobStore, ExplorationJob, JobConflict
from app.exploration_contract import ContractViolation
from app.rag.exploration_binding import (
    EvidenceBinding, EvidenceIntegrityError, EvidenceNoLongerAvailable,
    ExplorationEvidenceBinder,
)


def mock_response(request: dict, facts: list[dict]) -> dict:
    """Static labelled content only. No interpolation of untrusted evidence into instructions."""
    keys=[f['referenceKey'] for f in facts]
    result={'summary':'[MOCK] Exploration worker contract verification.',
            'questions':[], 'referenceKeys':keys}
    if request['purpose']=='PLAN':
        result={'targetName':request['input']['targetName'],
                'overview':'[MOCK] Plan worker contract verification.',
                'steps':[{'title':'[MOCK] Review evidence','description':'Synthetic step for contract verification only.',
                          'referenceKeys':keys}]}
    return {**{k:request[k] for k in ('contractVersion','requestId','inputHash','purpose')},
            'status':'SUCCEEDED','changesRecommendation':False,'reasonCode':None,'result':result}


class MockExplorationWorker:
    def __init__(self, session_factory, policy):
        self.sessions,self.policy=session_factory,policy

    def _store(self, session):return ExplorationJobStore(session,ExplorationEvidenceBinder(session,self.policy))

    def run_one(self, owner: str, job: int) -> str:
        # Only an explicit internal call can run this engine. There is no runtime enable switch.
        with self.sessions() as db:
            store=self._store(db)
            claim=store.claim(owner,job)
            if claim['state']=='BLOCKED':db.commit();return 'BLOCKED'
            row=db.get(ExplorationJob,job)
            binding=EvidenceBinding(**deepcopy(row.binding))
            lease=claim['leaseToken']
            db.commit()
        try:
            # Fresh facts immediately before computation, using another short transaction.
            with self.sessions() as db:
                store=self._store(db)
                row=store._locked(owner,job)
                if row.state!='LEASED' or row.lease_token!=lease or row.lease_until<=db.scalar(select(func.statement_timestamp())):raise JobConflict()
                facts=store.binder.validated_model_evidence(binding)
            response=mock_response(binding.request,facts)
            # Never hold the claim transaction during computation. Fence again before commit.
            with self.sessions() as db:
                self._store(db).complete_mock(owner,job,lease,response)
                db.commit()
            return 'MOCK_COMPLETED'
        except (EvidenceIntegrityError,EvidenceNoLongerAvailable,ContractViolation):
            with self.sessions() as db:
                store=self._store(db)
                row=store._locked(owner,job)
                if row.state!='LEASED' or row.lease_token!=lease or row.lease_until<=db.scalar(select(func.statement_timestamp())):raise JobConflict()
                row.state,row.reason='BLOCKED','MOCK_VALIDATION_FAILED'
                row.lease_token=row.lease_until=None
                db.commit()
            return 'BLOCKED'
