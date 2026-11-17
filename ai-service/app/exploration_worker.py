"""Shared leased worker engine with separate MOCK and opt-in model consumers.

Java owns fresh owner/account/context authorization and final user persistence.
PostgreSQL completion protects leases, cancellation, erasure and current evidence;
it does not prove a still-active account in the other database.
"""
from copy import deepcopy
from sqlalchemy import select,func
from app.exploration_jobs import ExplorationJobStore, ExplorationJob, JobConflict
from app.exploration_contract import ContractViolation
from app.exploration_model import ModelFault
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
    mode='MOCK'
    def claimed(self,row):pass
    def compute(self,request,facts):return mock_response(request,facts)
    def complete(self,store,owner,job,lease,response):store.complete_mock(owner,job,lease,response)
    def __init__(self, session_factory, policy):
        self.sessions,self.policy=session_factory,policy

    def _store(self, session):return ExplorationJobStore(session,ExplorationEvidenceBinder(session,self.policy))

    def run_one(self, owner: str, job: int) -> str:
        # Only an explicit internal call can run this engine. There is no runtime enable switch.
        with self.sessions() as db:
            store=self._store(db)
            claim=store.claim(owner,job,self.mode)
            if claim['state']=='BLOCKED':db.commit();return 'BLOCKED'
            row=db.get(ExplorationJob,job)
            self.claimed(row)
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
            response=self.compute(binding.request,facts)
            # Never hold the claim transaction during computation. Fence again before commit.
            with self.sessions() as db:
                self.complete(self._store(db),owner,job,lease,response)
                db.commit()
            return 'MOCK_COMPLETED' if self.mode=='MOCK' else 'MODEL_COMPLETED'
        except (EvidenceIntegrityError,EvidenceNoLongerAvailable,ContractViolation):
            with self.sessions() as db:
                store=self._store(db)
                row=store._locked(owner,job)
                if row.state!='LEASED' or row.lease_token!=lease or row.lease_until<=db.scalar(select(func.statement_timestamp())):raise JobConflict()
                row.state,row.reason='BLOCKED','MOCK_VALIDATION_FAILED' if self.mode=='MOCK' else 'MODEL_VALIDATION_FAILED'
                row.lease_token=row.lease_until=None
                db.commit()
            return 'BLOCKED'
        except ModelFault as failure:
            with self.sessions() as db:
                store=self._store(db);row=store._locked(owner,job)
                if row.state!='LEASED' or row.lease_token!=lease:raise JobConflict()
                if failure.retryable:store.retry(owner,job,lease)
                else:row.state,row.reason='BLOCKED',failure.code;row.lease_token=row.lease_until=None
                db.commit()
            raise


class ModelExplorationWorker(MockExplorationWorker):
    mode='MODEL'
    def __init__(self,session_factory,policy,consumer):
        super().__init__(session_factory,policy);self.consumer=consumer
    def compute(self,request,facts):return self.consumer.generate(request,facts)
    def complete(self,store,owner,job,lease,response):store.complete_model(owner,job,lease,response,self.consumer.metadata)
    def claimed(self,row):
        if hasattr(self.consumer,'configure_request'):
            self.consumer.configure_request(EvidenceBinding(**row.binding).request)
        if row.model_policy is None:row.model_policy=dict(self.consumer.metadata)
        elif row.model_policy!=self.consumer.metadata:raise ModelFault('MODEL_CONFIGURATION_INVALID')
