def draft_result(target='TEST'):
    default = ('CHOOSE_OPTION', '문구류 정리하기', '종이 재료로 나누기', '사용 목적별로 나누기')
    varied = {
        5: ('CLASSIFY_ITEMS', '도서 안내를 날짜 칸과 책 제목 칸으로 나눈다', '금요일', '숲 속 이야기'),
        6: ('OBSERVE_DIFFERENCE', '앱에서 저장을 누른 뒤 두 화면을 본다', '표시 없음', '저장 완료'),
        8: ('ORDER_STEPS', '설문의 질문을 정한 뒤에 종이에 옮긴다', '종이에 쓰기', '질문 정하기'),
        10: ('SKETCH_LABELS', '간식 설문 화면에 버튼 두 개를 넣는다', '사과', '귤'),
    }
    weeks = []
    for number in range(1, 13):
        activity, text, first, second = varied.get(number, default)
        phase = 'INFO' if number <= 4 else 'BASIC_LEARNING' if number <= 8 else 'SMALL_ACTIVITY'
        weeks.append({'week': number, 'phase': phase, 'estimatedMinutes': 5,
                      'task': {'inputKind': 'FICTIONAL_EXAMPLE', 'activity': activity,
                               'inputText': text, 'optionA': first, 'optionB': second}})
    return {'targetName': target, 'weeks': weeks}
