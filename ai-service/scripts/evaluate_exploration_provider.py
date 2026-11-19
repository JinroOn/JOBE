"""Explicit operator evaluation. No routes, approvals, jobs, or user-result writes.

Default preflight is offline. Protocol probes use synthetic constants only; they
cannot approve content or enable the application. Approved evaluation requires a
fixed version and revalidated DB evidence before and after the provider call.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import math
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.exploration_contract import ConsultationResult, Plan12Result
from app.exploration_plan_draft import Plan12Draft, render_plan_draft
from app.exploration_model import ModelFault

VERSION = 'EXPLORATION_PROVIDER_EVALUATION_V1_0'


def configuration():
    provider = os.getenv('LLM_PROVIDER', 'openai').lower()
    model = os.getenv('EXPLORATION_LLM_MODEL') or os.getenv('LLM_MODEL', '')
    key = os.getenv('LLM_API_KEY') or os.getenv('OPENAI_API_KEY') or os.getenv('FACTCHAT_API_KEY') or ''
    try:
        timeout = float(os.getenv('EXPLORATION_LLM_TIMEOUT_SECONDS', '5'))
        plan_timeout = float(os.getenv('EXPLORATION_PLAN_LLM_TIMEOUT_SECONDS', '30'))
    except ValueError:
        raise ModelFault('MODEL_CONFIGURATION_INVALID') from None
    if (provider not in {'openai', 'openai_compatible', 'factchat'} or not model.strip()
            or len(model) > 80 or not key.strip() or not math.isfinite(timeout) or not 0 < timeout <= 5
            or not math.isfinite(plan_timeout) or not 0 < plan_timeout <= 30):
        raise ModelFault('MODEL_CONFIGURATION_INVALID')
    return dict(provider=provider, model=model, key=key, timeout=timeout, plan_timeout=plan_timeout,
                base=os.getenv('LLM_BASE_URL') or os.getenv('OPENAI_BASE_URL') or None)


def preflight():
    required = ('EXPLORATION_EVIDENCE_ENABLED', 'EXPLORATION_MODEL_EXECUTION_ENABLED')
    missing = [name for name in required if os.getenv(name, 'false').lower() != 'true']
    if not os.getenv('EXPLORATION_EVIDENCE_DATASET_VERSION', '').strip():
        missing.append('EXPLORATION_EVIDENCE_DATASET_VERSION')
    if not os.getenv('INTERNAL_SERVICE_TOKEN', '').strip():
        missing.append('INTERNAL_SERVICE_TOKEN')
    if os.getenv('MOCK_MODE', '').lower() == 'true':
        missing.append('MOCK_MODE=false')
    try:
        config = configuration()
        provider = {'configured': True, 'provider': config['provider'], 'model': config['model'],
                    'timeoutSeconds': config['timeout'], 'planTimeoutSeconds': config['plan_timeout']}
    except ModelFault:
        provider = {'configured': False, 'reasonCode': 'MODEL_CONFIGURATION_INVALID'}
    return dict(reportVersion=VERSION, mode='preflight', liveRequests=0, provider=provider,
                serviceConfigurationReady=provider['configured'] and not missing,
                missingServiceSettings=missing, approvalVerified=False,
                contentQuality='NOT_EVALUATED', releaseReady=False)


def failure_code(error):
    # Never stringify provider/DB exceptions: they can contain credentials or input.
    if isinstance(error, (asyncio.TimeoutError, TimeoutError)):
        return 'MODEL_TIMEOUT'
    if isinstance(error, ModelFault):
        return error.code
    status = getattr(error, 'status_code', None)
    if status in {401, 403}:
        return 'MODEL_AUTH_FAILED'
    if status in {400, 404, 422}:
        return 'MODEL_CONFIGURATION_INVALID'
    if status == 429:
        return 'MODEL_RATE_LIMITED'
    return 'MODEL_UNAVAILABLE'


def usage(raw):
    value = getattr(raw, 'usage_metadata', None)
    if isinstance(value, dict):
        names = {'inputTokens': 'input_tokens', 'outputTokens': 'output_tokens', 'totalTokens': 'total_tokens'}
    else:
        value = getattr(raw, 'response_metadata', {}).get('token_usage', {})
        names = {'inputTokens': 'prompt_tokens', 'outputTokens': 'completion_tokens', 'totalTokens': 'total_tokens'}
    return {out: value[key] for out, key in names.items() if type(value.get(key)) is int and value[key] >= 0}


async def close_client(model):
    try:
        client = getattr(model, 'root_async_client', None)
        if client is not None:
            await asyncio.wait_for(client.close(), timeout=1)
    except Exception:
        pass
    try:
        client = getattr(model, 'root_client', None)
        if client is not None:
            client.close()
    except Exception:
        pass


async def protocol_probe(config, factory=None):
    """At most two SDK requests, no retries; no user input or claimed approval."""
    from langchain_core.messages import HumanMessage, SystemMessage
    if factory is None:
        from langchain_openai import ChatOpenAI
        factory = ChatOpenAI
    rows = []
    for name, schema, budget in (('CONSULTATION_SCHEMA', ConsultationResult, 1600), ('PLAN12_SCHEMA', Plan12Draft, 6000)):
        deadline = config['plan_timeout'] if name == 'PLAN12_SCHEMA' else config['timeout']
        model = None
        row = {'probe': name, 'status': 'FAILED', 'requestAttempted': False,
               'contentQuality': 'NOT_EVALUATED', 'estimatedCost': None, 'timeoutSeconds': deadline}
        started = time.monotonic()
        try:
            model = factory(model=config['model'], api_key=config['key'], base_url=config['base'],
                            timeout=deadline, max_retries=0, max_tokens=budget)
            prompt = ('Synthetic SDK schema test only; no student, diagnosis, approved source or service result. '
                      'Use exactly the supplied literal referenceKey protocol_ref. Keep every text short. '
                      'For consultation: summary=프로토콜 시험, questions=[], referenceKeys=[protocol_ref]. '
                      'For plan: targetName=PROTOCOL_TEST, exactly weeks 1 through 12; '
                      'phase INFO for 1-4, BASIC_LEARNING for 5-8, SMALL_ACTIVITY for 9-12. '
                      'For every week title=시험, materials=종이, estimatedMinutes=5, inputKind=FICTIONAL_EXAMPLE, '
                      'inputText=[A,B], action=두 칸에 나누기, expectedOutput=표, easierAction=A만 적기, '
                      'easierOutput=단어, referenceKeys=[protocol_ref]. No public tasks field.')
            row['requestAttempted'] = True
            envelope = await asyncio.wait_for(model.with_structured_output(schema, include_raw=True).ainvoke(
                [SystemMessage(content=prompt), HumanMessage(content=name)]), timeout=deadline)
            row['usage'] = usage(envelope.get('raw'))
            if envelope.get('parsing_error') is not None or envelope.get('parsed') is None:
                row['reasonCode'] = 'MODEL_OUTPUT_INVALID'
            else:
                try:
                    parsed = envelope['parsed']
                    parsed = schema.model_validate(parsed.model_dump() if hasattr(parsed, 'model_dump') else parsed)
                    refs = [parsed.referenceKeys] if name == 'CONSULTATION_SCHEMA' else [w.referenceKeys for w in parsed.weeks]
                    if any(r != ['protocol_ref'] for r in refs) or (name == 'PLAN12_SCHEMA' and parsed.targetName != 'PROTOCOL_TEST'):
                        raise ValueError()
                    if name == 'PLAN12_SCHEMA':
                        render_plan_draft(parsed.model_dump(), [{'referenceKey':'protocol_ref','text':'SYNTHETIC PROTOCOL'}])
                    row.update(status='PASS', schemaValid=True)
                except (ValueError, TypeError):
                    row['reasonCode'] = 'MODEL_OUTPUT_INVALID'
        except Exception as error:
            row['reasonCode'] = failure_code(error)
        finally:
            row['latencyMs'] = round((time.monotonic() - started) * 1000)
            await close_client(model)
        rows.append(row)
        if row.get('reasonCode') == 'MODEL_AUTH_FAILED':
            break
    return dict(reportVersion=VERSION, mode='protocol', provider=config['provider'], model=config['model'],
                timeoutSeconds=config['timeout'], planTimeoutSeconds=config['plan_timeout'], liveRequests=sum(r['requestAttempted'] for r in rows), probes=rows,
                status='PASS' if len(rows) == 2 and all(r['status'] == 'PASS' for r in rows) else 'FAILED',
                contentQuality='NOT_EVALUATED', approvalVerified=False, serviceGenerationTested=False,
                costStatus='TOKEN_USAGE_ONLY_PRICE_AND_BILLING_REVIEW_REQUIRED', releaseReady=False)


def approved_probe(request_path, dataset_version, review_output=None):
    """Read-only RAG evaluation, no job/result writes and no synthetic approvals."""
    from contextlib import contextmanager
    from sqlalchemy import create_engine, text
    from sqlalchemy.orm import Session
    from app.db import get_ai_database_settings
    from app.exploration_contract import read_request
    from app.exploration_model import ExplorationModel
    from app.rag.exploration_binding import (EvidencePolicy, ExplorationEvidenceBinder,
        EvidenceIntegrityError, EvidenceNoLongerAvailable, EvidencePolicyError)
    report = dict(reportVersion=VERSION, mode='approved', liveRequests=0, status='BLOCKED',
                  contentQuality='NOT_EVALUATED', savesUserResult=False, releaseReady=False)
    if os.getenv('EXPLORATION_MODEL_EXECUTION_ENABLED', 'false').lower() != 'true' or os.getenv('EXPLORATION_EVIDENCE_ENABLED', 'false').lower() != 'true':
        return dict(report, reasonCode='EVALUATION_SERVICE_DISABLED')
    if not dataset_version:
        return dict(report, reasonCode='FIXED_DATASET_VERSION_REQUIRED')
    if request_path is None:
        return dict(report, reasonCode='OWNED_REQUEST_EXPORT_REQUIRED')
    engine = None
    consumer = None
    try:
        policy = EvidencePolicy(dataset_version)
        configured = os.getenv('EXPLORATION_EVIDENCE_DATASET_VERSION', '')
        if configured and configured != dataset_version:
            raise EvidencePolicyError()
        if request_path.stat().st_size > 65536:
            return dict(report, reasonCode='EVALUATION_REQUEST_INVALID')
        request = read_request(json.loads(request_path.read_text(encoding='utf-8'))).model_dump()
        consumer = ExplorationModel(evaluation_diagnostics=True)
        engine = create_engine(get_ai_database_settings().sqlalchemy_url,
                               connect_args={'connect_timeout': 3, 'options': '-c statement_timeout=5000'})

        @contextmanager
        def readonly():
            with Session(engine) as db:
                db.execute(text('SET TRANSACTION READ ONLY'))
                yield db

        with readonly() as db:
            binder = ExplorationEvidenceBinder(db, policy)
            prepared = binder.prepare(request)
            if prepared.binding is None:
                return dict(report, reasonCode='APPROVED_EVIDENCE_UNAVAILABLE')
            facts = binder.validated_model_evidence(prepared.binding)
        # No DB transaction is held while the external provider executes.
        report['liveRequests'] = 1
        started = time.monotonic()
        response = consumer.generate(prepared.request, facts)
        report['latencyMs'] = round((time.monotonic() - started) * 1000)
        report['timingsMs'] = dict(consumer.timings)
        report['promptVersion'] = consumer.metadata['promptVersion']
        with readonly() as db:
            ExplorationEvidenceBinder(db, policy).revalidate(prepared.binding)
        if review_output is not None:
            if review_output.name.startswith('.env') or review_output.resolve() == request_path.resolve():
                return dict(report, reasonCode='EVALUATION_REVIEW_PATH_INVALID')
            review_output.parent.mkdir(parents=True, exist_ok=True)
            review_output.write_text(json.dumps({'evaluationOnly': True, 'requiresHumanReview': True,
                'purpose': response['purpose'], 'contractVersion': response['contractVersion'],
                'result': response['result']}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        return dict(report, status='PASS', approvalVerified=True, schemaValid=True,
                    purpose=response['purpose'], contractVersion=response['contractVersion'],
                    contentQuality='HUMAN_REVIEW_REQUIRED', reasonCode=None)
    except (EvidenceIntegrityError, EvidenceNoLongerAvailable, EvidencePolicyError):
        return dict(report, reasonCode='EVIDENCE_VALIDATION_FAILED')
    except ModelFault as error:
        if consumer is not None:
            report['timingsMs'] = dict(consumer.timings)
            report['promptVersion'] = consumer.metadata['promptVersion']
            if consumer.output_diagnostic is not None:
                report['outputDiagnostic'] = consumer.output_diagnostic
        return dict(report, reasonCode=error.code)
    except (OSError, ValueError):
        return dict(report, reasonCode='EVALUATION_REQUEST_INVALID')
    except Exception:
        return dict(report, reasonCode='EVIDENCE_STORE_UNAVAILABLE')
    finally:
        if engine is not None:
            engine.dispose()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--mode', choices=('preflight', 'protocol', 'approved'), default='preflight')
    parser.add_argument('--live', action='store_true', help='Explicitly allow paid provider requests (max 2 protocol / 1 approved).')
    parser.add_argument('--request-file', type=Path)
    parser.add_argument('--dataset-version')
    parser.add_argument('--review-output', type=Path, help='Optional private human-review artifact for approved mode only; no input, identities or credentials.')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if args.output.name.startswith('.env') or (args.env_file and args.output.resolve() == args.env_file.resolve()):
        parser.error('Report output must not overwrite environment configuration')
    if args.review_output is not None:
        if args.mode != 'approved' or args.review_output.name.startswith('.env') or any(
                path and args.review_output.resolve() == path.resolve() for path in (args.env_file, args.output, args.request_file)):
            parser.error('Human-review output must be a separate private file')
    if args.env_file:
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=False)
    # Report only fixed codes/allowlisted counters; SDK/HTTP logs must not emit payloads.
    for logger in ('httpx', 'httpcore', 'openai', 'langchain'):
        logging.getLogger(logger).setLevel(logging.CRITICAL)
    if args.mode != 'preflight' and not args.live:
        report = dict(reportVersion=VERSION, mode=args.mode, status='BLOCKED', liveRequests=0, reasonCode='LIVE_OPT_IN_REQUIRED')
    elif args.mode == 'protocol':
        try:
            report = asyncio.run(protocol_probe(configuration()))
        except ModelFault as error:
            report = dict(reportVersion=VERSION, mode=args.mode, status='BLOCKED', liveRequests=0, reasonCode=error.code)
    elif args.mode == 'approved':
        report = approved_probe(args.request_file, args.dataset_version or os.getenv('EXPLORATION_EVIDENCE_DATASET_VERSION'), args.review_output)
    else:
        report = preflight()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'mode': args.mode, 'status': report.get('status', 'CHECKED'), 'liveRequests': report['liveRequests']}))
    return 0 if report.get('status') in (None, 'PASS') else 2


if __name__ == '__main__':
    raise SystemExit(main())
