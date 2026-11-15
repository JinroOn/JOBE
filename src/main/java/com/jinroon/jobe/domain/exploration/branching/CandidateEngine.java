package com.jinroon.jobe.domain.exploration.branching;

import java.util.*;
import com.jinroon.jobe.domain.exploration.scoring.ScoringCatalog;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.*;

/** Pure candidate selection. Fixed groups are frozen after stage one, never expanded by later scores. */
public final class CandidateEngine {
    public record GroupScore(String code, double score) {}
    public record FixedCandidates(List<GroupScore> rankedGroups, List<String> groupCodes, List<String> majorCodes) {
        public FixedCandidates {
            rankedGroups=List.copyOf(rankedGroups); groupCodes=List.copyOf(groupCodes); majorCodes=List.copyOf(majorCodes);
        }
    }
    public record Display(List<MajorScore> candidates, List<MajorScore> firstScreen) {
        public Display { candidates=List.copyOf(candidates); firstScreen=List.copyOf(firstScreen); }
    }
    private final ScoringCatalog catalog;
    private final double thirdGap, majorGap;
    public CandidateEngine(ScoringCatalog catalog, double thirdGap, double majorGap) {
        this.catalog=Objects.requireNonNull(catalog);
        check(Double.isFinite(thirdGap)&&thirdGap>=0&&Double.isFinite(majorGap)&&majorGap>=0,"Invalid candidate thresholds");
        this.thirdGap=thirdGap; this.majorGap=majorGap;
    }
    public FixedCandidates freeze(List<Response> responses) {
        List<MajorScore> all=catalog.calculator().scoreAndSort(catalog.majors(),responses);
        Map<String,List<MajorScore>> byGroup=new TreeMap<>();
        all.stream().filter(s->s.topic()!=null).forEach(s->byGroup.computeIfAbsent(s.group(),k->new ArrayList<>()).add(s));
        // Group score is over valid topic scores, not final/activity/learning scores.
        List<GroupScore> groups=byGroup.entrySet().stream().map(e->new GroupScore(e.getKey(),e.getValue().stream()
                .sorted(Comparator.comparing(MajorScore::topic).reversed().thenComparing(MajorScore::majorCode))
                .limit(3).mapToDouble(MajorScore::topic).average().orElseThrow()))
                .sorted(Comparator.comparingDouble(GroupScore::score).reversed().thenComparing(GroupScore::code)).toList();
        Set<String> eligible=new HashSet<>();
        all.stream().filter(MajorScore::positiveTopicEvidence).forEach(s->eligible.add(s.group()));
        List<GroupScore> supported=groups.stream().filter(g->eligible.contains(g.code())).toList();
        int count=Math.min(2,supported.size());
        if(supported.size()>2&&supported.get(1).score()-supported.get(2).score()<=thirdGap)count=3;
        List<String> fixed=supported.subList(0,count).stream().map(GroupScore::code).toList();
        List<String> majors=all.stream().filter(s->fixed.contains(s.group())&&s.positiveTopicEvidence())
                .sorted(Comparator.comparing(MajorScore::topic,Comparator.reverseOrder()).thenComparing(MajorScore::majorCode))
                .map(MajorScore::majorCode).toList();
        return new FixedCandidates(groups,fixed,majors);
    }
    public Display rerank(FixedCandidates fixed,List<Response> responses) {
        validate(fixed);
        List<MajorProfile> profiles=catalog.majors().stream().filter(m->fixed.majorCodes().contains(m.majorCode())).toList();
        List<MajorScore> candidates=catalog.calculator().scoreAndSort(profiles,responses).stream()
                .filter(s->s.total()!=null&&s.positiveTopicEvidence()).toList();
        Map<String,List<MajorScore>> byGroup=new TreeMap<>();
        candidates.forEach(s->byGroup.computeIfAbsent(s.group(),k->new ArrayList<>()).add(s));
        List<MajorScore> mandatory=new ArrayList<>(),thirds=new ArrayList<>();
        for(List<MajorScore> scores:byGroup.values()) {
            mandatory.addAll(scores.subList(0,Math.min(2,scores.size())));
            if(scores.size()>2&&scores.get(1).total()-scores.get(2).total()<=majorGap)thirds.add(scores.get(2));
        }
        Comparator<MajorScore> order=Comparator.comparing(MajorScore::total,Comparator.reverseOrder()).thenComparing(MajorScore::majorCode);
        thirds.sort(order);
        mandatory.addAll(thirds.subList(0,Math.min(Math.max(0,7-mandatory.size()),thirds.size())));
        mandatory.sort(order);
        return new Display(candidates,mandatory);
    }
    public void validate(FixedCandidates fixed) {
        check(fixed.groupCodes().size()<=3&&new HashSet<>(fixed.groupCodes()).size()==fixed.groupCodes().size(),"Invalid fixed groups");
        check(new HashSet<>(fixed.majorCodes()).size()==fixed.majorCodes().size(),"Duplicate fixed major");
        Map<String,MajorProfile> known=new HashMap<>();catalog.majors().forEach(m->known.put(m.majorCode(),m));
        for(String code:fixed.majorCodes())check(known.containsKey(code)&&fixed.groupCodes().contains(known.get(code).group()),"Major outside fixed groups");
        Set<String> knownGroups=new HashSet<>();catalog.majors().forEach(m->knownGroups.add(m.group()));
        check(knownGroups.containsAll(fixed.groupCodes()),"Unknown fixed group");
    }
    static void check(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }
}
