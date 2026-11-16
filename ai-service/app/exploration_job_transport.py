"""Trusted Java-only commands. All DB changes commit before acknowledgement."""
import json
import os
import re
from sqlalchemy import select
from app.errors import AppError
from app.exploration_contract import read_request, ContractViolation
from app.exploration_jobs import ExplorationJob, ExplorationJobStore, JobConflict
from app.exploration_transport import evidence_session
from app.rag.exploration_binding import (
    EvidencePolicy, EvidencePolicyError, EvidenceIntegrityError, EvidenceNoLongerAvailable,
    EvidenceStoreUnavailable, ExplorationEvidenceBinder,
)

VERSION='EXPLORATION_JOB_TRANSPORT_V1_0'


def command(operation: str, payload: dict):
    try:
        if operation not in {'enqueue','read','cancel','purge'}: raise JobConflict()
        expected={'ownerKey'} if operation=='purge' else {'ownerKey','mutationKey','request'}
        if set(payload)!=expected: raise ContractViolation('Invalid exploration job command')
        owner=payload['ownerKey']
        ExplorationJobStore._owner(owner)
        # Erasure remains available even when generation preparation is disabled/misconfigured.
        if operation!='purge' and os.getenv('EXPLORATION_EVIDENCE_ENABLED','false').lower()!='true':
            raise AppError(503,'EVIDENCE_BINDING_DISABLED','Evidence preparation is disabled')
        with evidence_session() as db:
            policy=EvidencePolicy('ERASURE_ONLY' if operation=='purge' else os.getenv('EXPLORATION_EVIDENCE_DATASET_VERSION',''))
            binder=ExplorationEvidenceBinder(db,policy)
            jobs=ExplorationJobStore(db,binder)
            if operation=='purge':
                jobs.purge_owner(owner);db.commit()
                return {'transportVersion':VERSION,'status':'ERASED','generationAuthorized':False}
            original=read_request(payload['request']).model_dump()
            mutation=payload['mutationKey']
            if original['evidence']['bindingStatus']!='NOT_BOUND' or not isinstance(mutation,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',mutation):
                raise ContractViolation('Invalid exploration job command')
            jobs.guard_owner(owner)
            row=db.scalar(select(ExplorationJob).where(ExplorationJob.owner_key==owner,ExplorationJob.mutation_key==mutation).with_for_update())
            if row is not None and json.loads(row.binding['original_request_json'])!=original: raise JobConflict()
            if row is None:
                if operation!='enqueue': raise JobConflict()
                prepared=binder.prepare(original)
                if prepared.binding is None:
                    return envelope(original,mutation,'INSUFFICIENT_EVIDENCE',None)
                job=jobs.enqueue(owner,mutation,prepared.binding)
                row=db.get(ExplorationJob,job)
            if operation=='cancel':jobs.cancel(owner,row.id)
            result=envelope(original,mutation,row.state,row.id)
            db.commit()
            return result
    except ContractViolation:
        raise AppError(422,'EVIDENCE_CONTRACT_INVALID','Invalid exploration job command') from None
    except JobConflict:
        raise AppError(409,'EXPLORATION_JOB_CONFLICT','Exploration job is unavailable or conflicts') from None
    except EvidencePolicyError:
        raise AppError(503,'EVIDENCE_POLICY_INVALID','Evidence policy is unavailable') from None
    except EvidenceIntegrityError:
        raise AppError(503,'EVIDENCE_INTEGRITY_ERROR','Evidence integrity check failed') from None
    except EvidenceNoLongerAvailable:
        raise AppError(409,'EVIDENCE_NO_LONGER_AVAILABLE','Evidence approval has changed') from None
    except EvidenceStoreUnavailable:
        raise AppError(503,'EVIDENCE_STORE_UNAVAILABLE','Evidence store is unavailable',retryable=True) from None


def envelope(original,mutation,state,job):
    return {'transportVersion':VERSION,'status':state,'mutationKey':mutation,
            'sourceContextHash':original['sourceContextHash'],'inputHash':original['inputHash'],
            'jobId':job,'generationAuthorized':False}
