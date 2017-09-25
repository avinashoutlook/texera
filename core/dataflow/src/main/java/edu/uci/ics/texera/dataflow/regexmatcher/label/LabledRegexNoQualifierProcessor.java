package edu.uci.ics.texera.dataflow.regexmatcher.label;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.w3c.dom.UserDataHandler;

import edu.uci.ics.texera.api.field.ListField;
import edu.uci.ics.texera.api.span.Span;
import edu.uci.ics.texera.api.tuple.Tuple;
import edu.uci.ics.texera.dataflow.regexmatcher.RegexMatcher;
import edu.uci.ics.texera.dataflow.regexmatcher.RegexPredicate;

/**
 * Helper class for processing labeled regex without any qualifiers.
 * For regex format "this <label1> can cure <label2> better", extract the labels and affixes.
 * affixList {"this "," can cure "," better"}
 * labelList {"label1", "label2"}
 * Sort the affixList in length-decreasing order to select valid tuples with all the affixes inside to short-cut processing time.
 * Generate map of labels and the corresponding spans.
 * For each valid input tuple, match the first label's spans to its prefix and suffix with start/end position information.
 * Filter out the valid spans to continue match with following labels.
 * Early break if no matching in any step.
 *
 * @author Chang Liu
 * @author Zuozhi Wang
 *
 */
public class LabledRegexNoQualifierProcessor {

	public static double totalMatchingTime = 0;
	
	
    private RegexPredicate predicate;
    private RegexMatcher.RegexType regexType;

    private ArrayList<String> labelList = new ArrayList<>();
    private ArrayList<String> affixList = new ArrayList<>();
    
    // to save the regex patterns so we compile only once.
    private Map<String, Pattern> affixRegexPatterns = new HashMap<String, Pattern>();
    // to save the map between label and corresponding list of spans.
    private Map<String, List<Span>> labelValues = new HashMap<String, List<Span>>();
    
    class ScoredString{
    	public String value;
    	public int score;
    	ScoredString(String value_){
    		this.value = value_;
    		this.score = 0;
    	}
    	public String toString(){
    		return value + "(" + score + ")\t";
    	}
    }
    
    private ArrayList<ScoredString> scoredAffixList = new ArrayList<>(); // sort the affixList by length in decreasing order to short-cut the filter tuple operation.
    private int tupleStatsUsed = 0;
    private final static int TOTAL_USED_FOR_STATS = 1000;
    
    public LabledRegexNoQualifierProcessor(RegexPredicate predicate, RegexMatcher.RegexType regexType) {
        this.predicate = predicate;
        this.regexType = regexType;
        // populate labelList and affixList
        preprocessRegex();

    }

    /**
     * For regex format "this <label1> can cure <label2> better", extract the labels and affixes.
     * affixList {"this "," can cure "," better"}
     * labelList {"label1", "label2"}
     * Sort the affixList in length decreasing order to filter tuples.
     */
    private void preprocessRegex() {
        Matcher labelMatcher = Pattern.compile(RegexMatcher.CHECK_REGEX_LABEL).matcher(predicate.getRegex());
        int pre = 0;
        while (labelMatcher.find()) {
            int start = labelMatcher.start();
            int end = labelMatcher.end();

            affixList.add(predicate.getRegex().substring(pre, start));
            labelList.add(predicate.getRegex().substring(
                    labelMatcher.start() + 1, labelMatcher.end() - 1).trim());

            pre = end;
        }
        affixList.add(predicate.getRegex().substring(pre));
        
        scoredAffixList = new ArrayList<>(affixList.stream().map(a -> new ScoredString(a)).collect(Collectors.toList()));
        
        if(regexType == RegexMatcher.RegexType.LABELED_QUALIFIERS_AFFIX){
            for(String affix: affixList){
                affixRegexPatterns.put(affix, this.predicate.isIgnoreCase() ?
                        Pattern.compile(affix, Pattern.CASE_INSENSITIVE)
                        : Pattern.compile(affix) );
            }
        }
    }

    /**
     * Sort the affixList in length decreasing order to filter valid tuples.
     * @param tuple
     * @param attribute
     * @return
     */
    private boolean filterTuple(Tuple tuple, String attribute, int tupleNumber) {
    	if(tupleNumber <= TOTAL_USED_FOR_STATS){
    		boolean isValid = false;
    		for (ScoredString affix : scoredAffixList) {
    			if (! tuple.getField(attribute).getValue().toString().contains(affix.value)) {
    				isValid = false;
    			}else{
    				affix.score ++;
    			}
    		}
    		
    		if(tupleNumber == TOTAL_USED_FOR_STATS){
    			scoredAffixList = new ArrayList<>(scoredAffixList.stream().sorted((a1, a2) -> a2.score - a1.score).collect(Collectors.toList()));
    		}
    		
    		return isValid;
    	}else{
    		for (ScoredString affix : scoredAffixList) {
    			if (! tuple.getField(attribute).getValue().toString().contains(affix.value)) {
    				return false;
    			}
    		}
    		return true;
    	}
    }

    /***
     * Consider the affix with qualifiers and without qualifiers separately.
     * @param tuple
     * @return
     */

    public List<Span> computeMatchingResults(Tuple tuple) {
    	long startMatchTime = System.currentTimeMillis();
    	
    	List<Span> results;
        labelValues = fetchLabelSpans(tuple);
        if(this.regexType == RegexMatcher.RegexType.Labeled_WITHOUT_QUALIFIERS){
            results = noQualifierMatchingResults(tuple);
        }else{
            results = affixQualifierMatchingResults(tuple);
        }

        long endMatchTime = System.currentTimeMillis();
        totalMatchingTime += (endMatchTime - startMatchTime) / 1000.0;

        return results;
    }

    /***
     * For affix with qualifiers: treat each affix as a regex.
     * Compile them and get match result as a map of <affix, spanlist>.
     * Match with the label spanlist under same attribute for each input tuple.
     * Early break if no matches.
     * @param tuple
     * @return
     */
    public List<Span> affixQualifierMatchingResults(Tuple tuple) {
    	tupleStatsUsed++;
        List<Span> allAttrsMatchResults = new ArrayList<>();
        for(String attribute: predicate.getAttributeNames()){
            Map<String, List<Span> > affixMap = new HashMap<String, List<Span>>();
            boolean isTupleValid = generateAffixMap(attribute, tuple, tupleStatsUsed, affixMap);
            if(! isTupleValid) {
                continue;
            }
            List<List<Integer>> matchList = new ArrayList<>();
            for(int i = 0; i < labelList.size(); i++){
                String label = labelList.get(i);
                String prefix = affixList.get(i);
                String suffix = affixList.get(i+1);
                List<Span> relevantSpans = labelValues.get(label).stream()
                        .filter(span -> span.getAttributeName().equals(attribute)).collect(Collectors.toList());
                
//                System.out.println(label + "/" + attribute);
//                relevantSpans.stream().forEach(s -> System.out.print("(" + s.getStart() + "," + s.getEnd() + ") "));
//                System.out.println();
                
                if(i ==0){
//                	System.out.println(prefix);
//                	affixMap.get(prefix).stream().forEach(s -> System.out.print("(" + s.getStart() + "," + s.getEnd() + ") "));
//                	System.out.println();
                	
                    List<Span> validSpans = new ArrayList<>();
                    for(Span span: relevantSpans){
                        List<Span> matchPrefix = affixMap.get(prefix).stream()
                                .filter(s -> s.getEnd() == span.getStart()).collect(Collectors.toList());
                        if(! matchPrefix.isEmpty()){
                            matchList.addAll(matchPrefix.stream().
                                    map(span1 -> new ArrayList<Integer>(Arrays.asList(span1.getStart(), span1.getEnd())))
                                    .collect(Collectors.toList()));
                            validSpans.add(span);
                        }

                    }
                    relevantSpans = validSpans;
                }
//            	System.out.println(suffix);
//            	affixMap.get(suffix).stream().forEach(s -> System.out.print("(" + s.getStart() + "," + s.getEnd() + ") "));
//            	System.out.println();
            	
                List<List<Integer>> newMatchList = new ArrayList<>();
                for(List<Integer> previousMatch : matchList){
                    for(Span span: relevantSpans){
                        if(previousMatch.get(1) == span.getStart()){
                            List<Span> matchSuffix = affixMap.get(suffix).stream()
                                    .filter(s -> s.getStart() == span.getEnd()).collect(Collectors.toList());
                            if(! matchSuffix.isEmpty()){
                                matchSuffix.stream().forEach(matchSpan -> newMatchList
                                        .add(new ArrayList<Integer>(Arrays.asList(previousMatch.get(0), matchSpan.getEnd()))));
                            }

                        }
                    }
                }
                matchList = newMatchList;
                if (matchList.isEmpty()) {
                    break;
                }

            }
            String fieldValue = tuple.getField(attribute).getValue().toString();
            matchList.stream().forEach(match -> allAttrsMatchResults.add(
                    new Span(attribute, match.get(0), match.get(1), predicate.getRegex(), fieldValue.substring(match.get(0), match.get(1)))));
        }
        return allAttrsMatchResults;
    }

    /***
     * Filter out valid tuple using affixes in the regex.
     * String Match the entire regex from the first label's spans with its prefix and suffix using span's start and end information.
     * Filter out the valid spans to continue match with following labels.
     * Early break if no matching in any step.
     * @param tuple
     * @return
     */



    public List<Span> noQualifierMatchingResults(Tuple tuple){
        List<Span> allAttrsMatchSpans = new ArrayList<>();
        for (String attribute : predicate.getAttributeNames()) {
            boolean isValidTuple = filterTuple(tuple, attribute, tupleStatsUsed++);

            if (! isValidTuple) {
                continue;
            }

            String fieldValue = tuple.getField(attribute).getValue().toString();

            List<List<Integer>> matchList = new ArrayList<>();

            for (int i = 0; i < labelList.size(); i++) {
                String label = labelList.get(i);
                String prefix = affixList.get(i);
                String suffix = affixList.get(i+1);
                List<Span> relevantSpans = labelValues.get(label).stream()
                        .filter(span -> span.getAttributeName().equals(attribute)).collect(Collectors.toList());
                if (i == 0) {
                    List<Span> validSpans = relevantSpans.stream()
                            .filter(span -> span.getStart() >= prefix.length())
                            .filter(span -> fieldValue.substring(span.getStart() - prefix.length(), span.getStart()).equals(prefix))
                            .collect(Collectors.toList());
                    matchList = validSpans.stream()
                            .map(span -> new ArrayList<Integer>(Arrays.asList(span.getStart() - prefix.length(), span.getStart())))
                            .collect(Collectors.toList());
                    relevantSpans = validSpans;
                }

                List<List<Integer>> newMatchList = new ArrayList<>();

                for (List<Integer> previousMatch : matchList) {
                    for (Span span : relevantSpans) {
                        if (previousMatch.get(1) == span.getStart()
                                && span.getEnd() + suffix.length() <= fieldValue.length()
                                && fieldValue.substring(span.getEnd(), span.getEnd() + suffix.length()).equals(suffix)) {
                            newMatchList.add(Arrays.asList(previousMatch.get(0), span.getEnd() + suffix.length()));
                        }
                    }
                }

                matchList = newMatchList;
                if (matchList.isEmpty()) {
                    break;
                }
            }

            // assert that for every match:
            //  start >= 0, and end >= 0, and start <= end
            assert(matchList.stream()
                    .filter(match -> match.get(0) >= 0)
                    .filter(match -> match.get(1) >= 0)
                    .filter(match -> match.get(0) <= match.get(1))
                    .count() == (long) matchList.size());

            matchList.stream().forEach(match -> allAttrsMatchSpans.add(
                    new Span(attribute, match.get(0), match.get(1), predicate.getRegex(), fieldValue.substring(match.get(0), match.get(1)))));
        }

        return allAttrsMatchSpans;
    }


    /**
     * Creates Map of label and corresponding spans
     * @param inputTuple
     * @return
     */
    private Map<String, List<Span>> fetchLabelSpans(Tuple inputTuple) {
        Map<String, List<Span>> labelSpanMap = new HashMap<>();
        for (String label : this.labelList) {
            ListField<Span> spanListField = inputTuple.getField(label);
            labelSpanMap.put(label, spanListField.getValue());
        }
        return labelSpanMap;
    }

    /***
     * For each input tuple and attribute, compile all the affix regex and get the matching result as a map of <affix, spanlist>.
     * Early break if any affix matching fails.
     * @param attribute
     * @param tuple
     * @return
     */
    private boolean generateAffixMap(String attribute, Tuple tuple, int tupleNumber, Map<String, List<Span> > affixSpans){

    	if(tupleNumber <= TOTAL_USED_FOR_STATS){
    		boolean isValid = true;
    		for (ScoredString affix : scoredAffixList) {
    			List<Span> matchingResults = generateAffixSpanList(tuple.getField(attribute).getValue().toString(), 
                		attribute, affix.value);
                if(matchingResults.isEmpty()){
                    isValid = false;
                }else{
    				affix.score ++;                	
    				affixSpans.put(affix.value, matchingResults);
                }
    		}
    		
    		if(tupleNumber == TOTAL_USED_FOR_STATS){
    			scoredAffixList = new ArrayList<>(scoredAffixList.stream().sorted((a1, a2) -> a1.score - a2.score).collect(Collectors.toList()));
    			scoredAffixList.stream().forEach(a -> System.out.println(a));
    		}
    		
    		return isValid;
    	}else{
    		for (ScoredString affix : scoredAffixList) {
    			List<Span> matchingResults = generateAffixSpanList(tuple.getField(attribute).getValue().toString(), 
                		attribute, affix.value);
                if(matchingResults.isEmpty()){
                    return false;
                }
                affixSpans.put(affix.value, matchingResults);
    		}
    		return true;
    	}
    }
    
    private List<Span> generateAffixSpanList(String fieldValue, String attributeName, String affixValue){
    	List<Span> matchingResults = new ArrayList<>();
    	if(regexType == RegexMatcher.RegexType.Labeled_WITHOUT_QUALIFIERS){
    		//TODO
    	}else if(regexType == RegexMatcher.RegexType.LABELED_QUALIFIERS_AFFIX){
    		Pattern affixPattern = affixRegexPatterns.get(affixValue);
            Matcher affixMatcher =affixPattern.matcher(fieldValue.toLowerCase());
            while (affixMatcher.find()) {
                int start = affixMatcher.start();
                int end = affixMatcher.end();
                matchingResults.add(
                        new Span(attributeName, start, end, affixValue, fieldValue.substring(start, end)));
            }
    	}
    	return matchingResults;
    }
    

}