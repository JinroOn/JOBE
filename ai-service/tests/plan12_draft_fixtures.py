def draft_result(target='TEST'):
    return {'targetName': target, 'weeks': [
        {'week': n, 'phase': 'INFO' if n <= 4 else 'BASIC_LEARNING' if n <= 8 else 'SMALL_ACTIVITY',
         'estimatedMinutes': 5, 'task': {'inputKind': 'FICTIONAL_EXAMPLE', 'inputText': '문구류 정리하기',
                                         'optionA': '종이 재료로 나누기', 'optionB': '사용 목적별로 나누기'}}
        for n in range(1, 13)]}
