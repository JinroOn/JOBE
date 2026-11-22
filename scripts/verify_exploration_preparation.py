"""Read-only checks for the development input bundle; never loads a DB or approves content."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

PROJECT=Path(__file__).resolve().parents[1]
DATA=PROJECT/'datasets/exploration'
MAPPING_VERSION='MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE'
RULESET_VERSION='RULESET_V1_5_IMPLEMENTATION_CANDIDATE'
CURRENT_RULESET_VERSION='RULESET_V1_6_IMPLEMENTATION_CANDIDATE'

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def verify(workspace: Path | None = None):
    checks=[]
    def check(name, passed):
        checks.append({'check':name,'pass':bool(passed)})
    manifest=read(DATA/'manifest.json')
    expected={'questions.csv','options.csv','option-variants.csv','comparison-set-codes.csv',
              'comparison-skip-pairs.csv','major-mapping.json','caution-tags.csv','ruleset.json','ruleset-v1-6.json','semantic-dictionary.json'}
    check('exactly_ten_inputs',len(manifest['files'])==10 and {r['file'] for r in manifest['files']}==expected)
    for row in manifest['files']:
        path=DATA/row['file']
        check('manifest_hash_and_size:'+row['file'],path.is_file() and sha(path)==row['sha256'] and path.stat().st_size==row['bytes'])
        if workspace and 'source' in row:
            source=workspace/row['source']
            check('canonical_copy:'+row['file'],source.is_file() and source.read_bytes()==path.read_bytes())
    mapping=read(DATA/'major-mapping.json')
    rules=read(DATA/'ruleset.json')
    current=read(DATA/'ruleset-v1-6.json')
    expected_current={**rules, 'ruleset_version':CURRENT_RULESET_VERSION,
                      'thresholds':{**rules['thresholds'], 'affirmative_interest_response_gte':75}}
    check('current_ruleset_affirmative_interest_policy',current==expected_current)
    majors={m['major_code']:m for m in mapping['majors']}
    check('mapping_schema_and_version',mapping['schema_version']=='1.2' and mapping['major_mapping_version']==MAPPING_VERSION)
    check('ruleset_mapping_reference',rules['ruleset_version']==RULESET_VERSION and rules['catalog_versions']['major_mapping']==MAPPING_VERSION)
    check('ninety_four_unique_major_codes',len(mapping['majors'])==len(majors)==94)
    for row,old,new in [(6,'문예창작과','문예창작학과'),(93,'유아교육과','유아교육학과')]:
        major=majors[f'MAJOR_ROW_{row}']
        service=read(PROJECT/f'datasets/majors/major-row-{row}-{old}.service.json')
        check(f'name_alias_and_service:{row}',major['major_name']==new and old in major['major_name_aliases']
              and service['majorName']==service['standardMajorName']==new and old in service['majorNameAliases'])
    check('no_mapping_domain_approval',mapping['domain_review_status']=='PENDING' and all(m['domain_review_status']=='PENDING' for m in majors.values()))
    for filename,count in [('questions.csv',187),('options.csv',1360),('option-variants.csv',32),('caution-tags.csv',846)]:
        with (DATA/filename).open(encoding='utf-8-sig',newline='') as stream:
            rows=list(csv.DictReader(stream))
        check('row_count:'+filename,len(rows)==count)
    fixture_manifest=read(PROJECT/'datasets/exploration-validation/manifest.json')
    for row in fixture_manifest['files']:
        path=PROJECT/'datasets/exploration-validation'/row['file']
        check('reference_fixture_hash:'+row['file'],sha(path)==row['sha256'])
    reach=read(PROJECT/'datasets/exploration-validation/reachability-profiles.json')
    check('376_reference_profiles_not_runtime_results',len(reach['profiles'])==376 and reach['runtime_status']=='NOT_EXECUTED'
          and all(p['ruleset_version']==RULESET_VERSION and p['catalog_versions']['major_mapping']==MAPPING_VERSION for p in reach['profiles']))
    if workspace:
        activities=read(workspace/'탐색활동_콘텐츠_초안/전체활동_카탈로그.json')
        check('282_activity_drafts_on_current_mapping',len(activities['activities'])==282 and activities['major_mapping_version']==MAPPING_VERSION
              and all(a['major_name']==majors[a['major_code']]['major_name'] and a['content_status']=='DRAFT'
                      and a['domain_review_status']=='PENDING' and not a['public_use_enabled'] for a in activities['activities']))
    return {'checked_at':datetime.now(timezone.utc).isoformat(),'scope':'Read-only input/reference-fixture checks; no runtime engine or release approval',
            'all_pass':all(c['pass'] for c in checks),'checks':checks,'versions':{'major_mapping':MAPPING_VERSION,'ruleset':RULESET_VERSION},
            'input_sha256':{r['file']:sha(DATA/r['file']) for r in manifest['files']}}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace-root',type=Path,help='Optional workspace containing canonical 통합과정 and activity drafts')
    parser.add_argument('--report',type=Path,help='Optional JSON report path; no report is written by default')
    args=parser.parse_args()
    result=verify(args.workspace_root.resolve() if args.workspace_root else None)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for c in result['checks']:
        if not c['pass']:
            print('FAIL:',c['check'])
    print(('PASS' if result['all_pass'] else 'FAIL')+f": {len(result['checks'])} preparation checks")
    raise SystemExit(0 if result['all_pass'] else 1)
