def draft_result(reference, target='TEST'):
    return {'targetName': target, 'weeks': [
        {'week': n, 'phase': 'INFO' if n <= 4 else 'BASIC_LEARNING' if n <= 8 else 'SMALL_ACTIVITY',
         'title': '작은 탐색', 'materials': '종이와 펜', 'estimatedMinutes': 5,
         'inputKind': 'FICTIONAL_EXAMPLE', 'inputText': '[책, 공책, 펜]',
         'action': '종이 물건/그 외 두 칸으로 나누기', 'expectedOutput': '물건 세 개를 넣은 표',
         'easierAction': '책 하나만 해당 칸에 적기', 'easierOutput': '단어 한 개',
         'referenceKeys': [reference]} for n in range(1, 13)]}
