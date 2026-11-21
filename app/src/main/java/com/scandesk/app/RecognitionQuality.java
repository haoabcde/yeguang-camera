package com.scandesk.app;

import java.util.List;

/** Page-level evidence that the OCR output is too uncertain to use directly. */
final class RecognitionQuality {
    static final int VERSION = 1;

    private static final float MAX_EMPTY_FRACTION = .50f;
    private static final float MAX_MISSING_CONFIDENCE_FRACTION = .35f;
    private static final float MAX_LOW_CONFIDENCE_FRACTION = .45f;
    private static final float MIN_COMPARABLE_CONFIDENCE = .80f;
    private static final float MAX_DISAGREEMENT_FRACTION = .35f;

    private RecognitionQuality() {}

    static final class Sample {
        final String text;
        final float confidence;
        final String alternative;
        final float alternativeConfidence;

        Sample(String text, float confidence, String alternative, float alternativeConfidence) {
            this.text = text;
            this.confidence = confidence;
            this.alternative = alternative;
            this.alternativeConfidence = alternativeConfidence;
        }
    }

    static final class Result {
        final boolean reliable;
        final String reason;
        final int lines;
        final float meanConfidence;
        final float lowConfidenceFraction;
        final float disagreementFraction;
        final float missingConfidenceFraction;
        final float emptyFraction;

        private Result(String reason, int lines, float meanConfidence, float lowConfidenceFraction,
                       float disagreementFraction, float missingConfidenceFraction, float emptyFraction) {
            this.reliable = "ok".equals(reason);
            this.reason = reason;
            this.lines = lines;
            this.meanConfidence = meanConfidence;
            this.lowConfidenceFraction = lowConfidenceFraction;
            this.disagreementFraction = disagreementFraction;
            this.missingConfidenceFraction = missingConfidenceFraction;
            this.emptyFraction = emptyFraction;
        }
    }

    static Result printed(List<Sample> samples) {
        return assess(samples, .80f, .82f);
    }

    static Result handwritten(List<Sample> samples) {
        return assess(samples, .72f, .75f);
    }

    /** Agreement is supporting evidence, not a probability or a font classifier. */
    static float agreement(String first, String second) {
        int[] a = nonWhitespaceCharacters(first), b = nonWhitespaceCharacters(second);
        int length = Math.max(a.length, b.length);
        return length == 0 ? 0 : 1f - (float) editDistance(a, b) / length;
    }

    private static Result assess(List<Sample> samples, float lowLineConfidence, float minMeanConfidence) {
        int emptyLines = 0;
        long characters = 0;
        long scoredCharacters = 0;
        long lowCharacters = 0;
        long disagreementWeight = 0;
        long differingCharacters = 0;
        double confidenceSum = 0;
        for (Sample sample : samples) {
            int[] primary = nonWhitespaceCharacters(sample.text);
            int length = primary.length;
            if (length == 0) {
                emptyLines++;
                continue;
            }
            characters += length;
            disagreementWeight += length;
            if (hasConfidence(sample.confidence)) {
                scoredCharacters += length;
                confidenceSum += (double) sample.confidence * length;
                if (sample.confidence < lowLineConfidence) lowCharacters += length;
            }
            if (sample.alternative != null && hasConfidence(sample.confidence)
                    && hasConfidence(sample.alternativeConfidence)
                    && sample.confidence >= MIN_COMPARABLE_CONFIDENCE
                    && sample.alternativeConfidence >= MIN_COMPARABLE_CONFIDENCE) {
                int[] alternative = nonWhitespaceCharacters(sample.alternative);
                if (alternative.length > 0) {
                    disagreementWeight += Math.max(0, alternative.length - length);
                    differingCharacters += editDistance(primary, alternative);
                }
            }
        }
        float mean = scoredCharacters == 0 ? Float.NaN : (float) (confidenceSum / scoredCharacters);
        float low = fraction(lowCharacters, scoredCharacters);
        float missing = fraction(characters - scoredCharacters, characters);
        float disagreement = fraction(differingCharacters, disagreementWeight);
        float empty = fraction(emptyLines, samples.size());
        String reason = "ok";
        if (characters == 0) reason = "no_text";
        else if (empty > MAX_EMPTY_FRACTION) reason = "too_many_empty_lines";
        else if (missing > MAX_MISSING_CONFIDENCE_FRACTION) reason = "missing_confidence";
        else if (mean < minMeanConfidence || low > MAX_LOW_CONFIDENCE_FRACTION) reason = "low_confidence";
        else if (disagreement > MAX_DISAGREEMENT_FRACTION) reason = "model_disagreement";

        return new Result(reason, samples.size(), mean, low, disagreement, missing, empty);
    }

    private static boolean hasConfidence(float confidence) {
        return confidence >= 0 && confidence <= 1;
    }

    private static float fraction(long count, long total) {
        return total == 0 ? 0 : (float) ((double) count / total);
    }

    private static int[] nonWhitespaceCharacters(String text) {
        if (text == null) return new int[0];
        return text.codePoints()
                .filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
                .toArray();
    }

    private static int editDistance(int[] first, int[] second) {
        int[] distances = new int[second.length + 1];
        for (int column = 0; column <= second.length; column++) distances[column] = column;
        for (int row = 1; row <= first.length; row++) {
            int previousDiagonal = distances[0];
            distances[0] = row;
            for (int column = 1; column <= second.length; column++) {
                int previousRow = distances[column];
                int substitution = previousDiagonal + (first[row - 1] == second[column - 1] ? 0 : 1);
                distances[column] = Math.min(substitution, 1 + Math.min(previousRow, distances[column - 1]));
                previousDiagonal = previousRow;
            }
        }
        return distances[second.length];
    }
}
