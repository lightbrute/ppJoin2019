package ppJoin;

import ppJoin.Services.InitializationService;
import ppJoin.interfaces.TextualJoinExecutor;
import ppJoin.pojos.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

class PPjoinExecutor implements TextualJoinExecutor {

    private InitializationService initializationService = new InitializationService();

    public static List<RecordPair> recordPairs = new ArrayList<>();

    private long maxExecutionTimeOnThread;

    PPjoinExecutor() {}

    public void execute(List<Record> recordList, Double similarityThreshold) {
        recordList = initializationService.initializeForPPjoin(recordList);

//        System.out.println("--- SORTED RECORD LIST: ---");
//        for (Record record : recordList)
//            System.out.println(record.toString());
//        System.out.println("----------------------------------------------------------------------------");

//        System.out.println("--- FREQUENCY INDEX: ---");
//        System.out.println(FrequencyIndex.getInstance().getIndex());
//        System.out.println("----------------------------------------------------------------------------");

        final Map<Integer, Record> recordMap = initializationService.listOfRecordsToIdMap(recordList);
        findPairs(recordMap, similarityThreshold);
    }

    private void findPairs(final Map<Integer, Record> recordMap, final Double similarityThreshold) {
        LocalDateTime startTime = LocalDateTime.now();

        for (Map.Entry<Integer, Record> entryRM : recordMap.entrySet()) {
            int recordIdX = entryRM.getKey();
            Record recordX = entryRM.getValue();

            int recordSizeX = recordX.getNGramsList().size();

            //This is A from "Efficient Similarity Joins for Near Duplicate Detection" paper
            HashMap<Integer, Integer> overlapMap = new HashMap<>();

            // Get prefix length
            int prefixLength = PPjoinUtils.calculate_prefixLength(recordX, similarityThreshold);

            int positionI = 0;
            for (String nGram : recordX.getNGramsList().subList(0, prefixLength)) {
                positionI++;
                if (InvertedIndex.getInstance().getIndex().get(nGram) != null) {
                    for (Occurrence occurrence : InvertedIndex.getInstance().getIndex().get(nGram)) {
                        int recordIdY = occurrence.getRecordId();
                        //if (recordIdX.equals(recordIdY)) break;
                        if (recordIdX == recordIdY) break;

                        int positionJ = occurrence.getPosition();
                        int recordSizeY = recordMap.get(recordIdY).getNGramsList().size();

                        if ((recordSizeY >= (similarityThreshold * recordSizeX))) {
                            int a = PPjoinUtils.calculate_a(similarityThreshold, recordSizeX, recordSizeY);
                            int ubound = PPjoinUtils.calculate_ubound(recordSizeX, recordSizeY,
                                    positionI, positionJ);
                            if ((overlapMap.getOrDefault(recordIdY, 0) + ubound >= a))
                                overlapMap.put(recordIdY, (overlapMap.getOrDefault(recordIdY, 0) + 1));
                            else
                                overlapMap.put(recordIdY, 0);
                        }
                    }
                }

                InvertedIndex.getInstance().addToInvertedIndex(nGram, recordIdX, positionI);
            }

            final Map<Record, Integer> overlapMapVerify = new HashMap<>();

            overlapMap.forEach((recordId, overlap) -> overlapMapVerify.put(recordMap.get(recordId), overlap));

            ppJoinVerify(recordX, overlapMapVerify, similarityThreshold);
        }

        LocalDateTime stopTime = LocalDateTime.now();
        long executionTime = ChronoUnit.SECONDS.between(startTime, stopTime);
        System.out.println("\n" + "Partition Execution Time :: "
                + String.format("%02d:%02d:%02d",(executionTime/3600), ((executionTime % 3600)/60), (executionTime % 60))
                + ". (" + executionTime + " seconds)");

        if (executionTime > maxExecutionTimeOnThread)
            maxExecutionTimeOnThread = executionTime;
    }


    private void ppJoinVerify (Record record, Map<Record,Integer> overlapMap, double simThreshold) {

        int prefixLengthX = PPjoinUtils.calculate_prefixLength(record, simThreshold);

        overlapMap.forEach((recordFromOverlapMap, overlap) -> {
            int intersection;
            int ubound;
            List<String> tokenSet1 =  new ArrayList<>(), tokenSet2 =  new ArrayList<>();

            int a = PPjoinUtils.calculate_a(simThreshold,
                    record.getNGramsList().size(), recordFromOverlapMap.getNGramsList().size());

            if (overlap > 0) {
                int prefixLengthY = PPjoinUtils.calculate_prefixLength(recordFromOverlapMap, simThreshold);
                String wx = record.getNGramsList().get(prefixLengthX - 1);
                int wxFreq = FrequencyIndex.getInstance().getIndex().get(wx);
                String wy = recordFromOverlapMap.getNGramsList().get(prefixLengthY - 1);
                int wyFreq = FrequencyIndex.getInstance().getIndex().get(wy);
                // valueO is O as named in Verify
                int valueO = overlap;

                if (wxFreq <= wyFreq) {  //  Global Frequency Ordering (Odf) favors rare tokens :: it means "is Wx rarest OR as rare as Wy?"
                    ubound = valueO + record.getNGramsList().size() - prefixLengthX;
                    if (ubound >= a) {
                        for (int i = prefixLengthX; i < record.getNGramsList().size(); i++) {
                            tokenSet1.add(record.getNGramsList().get(i));
                        }
                        for (int i = overlap; i < recordFromOverlapMap.getNGramsList().size(); i++) {
                            tokenSet2.add(recordFromOverlapMap.getNGramsList().get(i));
                        }
                    }
                } else {
                    ubound = valueO + recordFromOverlapMap.getNGramsList().size() - prefixLengthY;
                    if (ubound >= a) {
                        for (int i = overlap; i < record.getNGramsList().size(); i++) {
                            tokenSet1.add(record.getNGramsList().get(i));
                        }
                        for (int i = prefixLengthY; i < recordFromOverlapMap.getNGramsList().size(); i++) {
                            tokenSet2.add(recordFromOverlapMap.getNGramsList().get(i));
                        }
                    }
                }
                tokenSet1.retainAll(tokenSet2);
                intersection = tokenSet1.size();
                valueO = valueO + intersection;

                if (valueO >= a) {
                    recordPairs.add(new RecordPair(record, recordFromOverlapMap));
                }
            }

        });
    }

    public HashSet<RecordPair> getRecordPairsToSet() {
        return new HashSet<>(recordPairs);
    }

    public long getMaxExecutionTimeOnThread() {
        return maxExecutionTimeOnThread;
    }

}
