def draft_result(reference, target='TEST'):
    return {'targetName': target, 'weeks': [
        {'week': n, 'phase': 'INFO' if n <= 4 else 'BASIC_LEARNING' if n <= 8 else 'SMALL_ACTIVITY',
         'estimatedMinutes': 5, 'inputKind': 'FICTIONAL_EXAMPLE', 'inputText': '문구류 정리하기',
         'activity': 'CHOOSE_OPTION', 'options': ['종이 재료로 나누기', '사용 목적별로 나누기'],
         'referenceKeys': [reference]} for n in range(1, 13)]}
