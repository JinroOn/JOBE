def draft_result(target='TEST'):
    weeks = []
    for number, scenario in enumerate((1, 6, 7, 9, 2, 3, 10, 4, 14, 5, 11, 8), 1):
        phase = 'INFO' if number <= 4 else 'BASIC_LEARNING' if number <= 8 else 'SMALL_ACTIVITY'
        weeks.append({'week': number, 'phase': phase, 'estimatedMinutes': 5,
                      'task': {'inputKind': 'FICTIONAL_EXAMPLE', 'scenarioIndex': scenario}})
    return {'targetName': target, 'weeks': weeks}
