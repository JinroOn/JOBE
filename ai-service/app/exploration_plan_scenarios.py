"""Paper-based fictional examples used by exploration plan drafts."""
from dataclasses import dataclass


@dataclass(frozen=True)
class FictionalScenario:
    activity: str
    situation: str
    first: str
    second: str


FICTIONAL_SCENARIOS = (
    FictionalScenario('CHOOSE_OPTION', '문구류를 찾기 쉽게 정리하려고 한다. 어떤 기준이 편할지 생각한다.',
                      '재료별로 나누기', '쓰는 목적별로 나누기'),
    FictionalScenario('CLASSIFY_ITEMS', '도서관 안내에서 책 제목은 제목 칸, 돌려줄 날짜는 반납일 칸에 넣는다.',
                      '숲 속 이야기', '금요일까지'),
    FictionalScenario('OBSERVE_DIFFERENCE', '앱에서 메모를 저장한 뒤 두 화면이 보여 주는 글을 살펴본다.',
                      '아무 표시 없음', '저장 완료'),
    FictionalScenario('ORDER_STEPS', '간식 설문을 만든다. 질문을 먼저 정해야 종이에 질문을 옮길 수 있다.',
                      '종이에 질문 쓰기', '물어볼 질문 고르기'),
    FictionalScenario('SKETCH_LABELS', '간식 설문 화면을 종이에 그린다. 좋아하는 과일을 고르는 버튼 두 개가 필요하다.',
                      '사과', '귤'),
    FictionalScenario('CLASSIFY_ITEMS', '안내 화면의 운영 시간 칸에는 시간, 찾아오는 길 칸에는 입구 위치를 넣는다.',
                      '오전 9시부터', '운동장 옆 입구'),
    FictionalScenario('OBSERVE_DIFFERENCE', '분실물 목록을 연다. 같은 항목을 두 화면에서 확인한다.',
                      '물병: 보관 중', '물병: 주인에게 돌려줌'),
    FictionalScenario('ORDER_STEPS', '도서 검색 화면을 시험한다. 책 이름을 적은 다음 검색을 눌러야 한다.',
                      '검색 누르기', '책 이름 적기'),
    FictionalScenario('SKETCH_LABELS', '분실물 안내 화면에서 물건을 올리거나 목록을 볼 수 있도록 버튼 두 개를 그린다.',
                      '물건 등록하기', '보관 목록 보기'),
    FictionalScenario('CLASSIFY_ITEMS', '준비물 목록에 있는 물건은 준비물 칸, 가져오는 때는 날짜 칸에 넣는다.',
                      '화요일까지', '종이와 펜'),
    FictionalScenario('OBSERVE_DIFFERENCE', '도서 검색을 하기 전과 한 뒤에 나타나는 글을 비교한다.',
                      '책 이름을 입력하세요', '검색 결과 2권'),
    FictionalScenario('ORDER_STEPS', '방문 안내를 종이에 만든다. 시간을 확인한 뒤 안내문에 적는다.',
                      '안내문에 시간 쓰기', '열리는 시간 확인하기'),
    FictionalScenario('SKETCH_LABELS', '학교 행사 안내 화면에 시간과 장소를 각각 표시할 칸을 그린다.',
                      '오후 2시', '도서관 앞'),
    FictionalScenario('CHOOSE_OPTION', '물병이 있는지 찾으려 한다. 기억나는 정보는 물건 이름뿐이다.',
                      '물건 이름으로 찾기', '발견한 날짜로 찾기'),
)
