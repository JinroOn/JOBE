package com.jinroon.jobe.domain.result.dto.response;

import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record RankedMajorScore(ResultMajorScore score, Integer displayRank, int tieCount) {
    // Use the persisted ranking criteria before major ID breaks ties.
    private record RankingKey(Boolean failed, Float finalScore, Float competencyScore) {
        static RankingKey from(ResultMajorScore score) {
            return new RankingKey(score.getFailed(), score.getFinalScore(), score.getCompetencyScore());
        }
    }

    private record RankGroup(int firstRank, int count) {
        RankGroup include(int rank) {
            return new RankGroup(Math.min(firstRank, rank), count + 1);
        }
    }

    public static List<RankedMajorScore> from(List<ResultMajorScore> scores) {
        Map<RankingKey, RankGroup> groups = new HashMap<>();
        for (ResultMajorScore score : scores) {
            if (hasRankingData(score)) {
                var key = RankingKey.from(score);
                groups.compute(key, (ignored, group) -> group == null
                        ? new RankGroup(score.getRank(), 1)
                        : group.include(score.getRank()));
            }
        }
        return scores.stream().map(score -> {
            RankGroup group = hasRankingData(score) ? groups.get(RankingKey.from(score)) : null;
            return group == null
                    ? new RankedMajorScore(score, score.getRank(), 1)
                    : new RankedMajorScore(score, group.firstRank(), group.count());
        }).toList();
    }

    public static int firstPlaceCount(List<ResultMajorScore> scores) {
        return from(scores).stream()
                .filter(ranked -> Integer.valueOf(1).equals(ranked.displayRank()))
                .findFirst()
                .map(RankedMajorScore::tieCount)
                .orElse(0);
    }

    private static boolean hasRankingData(ResultMajorScore score) {
        return score.getRank() != null && score.getFailed() != null
                && score.getFinalScore() != null && score.getCompetencyScore() != null;
    }
}
