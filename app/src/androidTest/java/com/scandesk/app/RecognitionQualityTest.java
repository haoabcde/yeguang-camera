package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public final class RecognitionQualityTest {
    private static final float TOLERANCE = .0001f;

    @Test public void acceptsMixedTextAndRepeatedCharactersWithAnIsolatedEmptyBox() {
        RecognitionQuality.Result result = RecognitionQuality.printed(Arrays.asList(
                sample("甲组ABC 123.50", .96f),
                sample("哈哈哈哈 0000", .95f),
                sample("　", Float.NaN)));

        assertTrue(result.reliable);
        assertEquals(3, result.lines);
        assertEquals(1f / 3, result.emptyFraction, TOLERANCE);
        assertEquals(0f, result.missingConfidenceFraction, TOLERANCE);
    }

    @Test public void rejectsEmptyPagesAndPagesWithTooManyEmptyBoxes() {
        assertEquals("no_text", RecognitionQuality.printed(Collections.emptyList()).reason);
        assertEquals("no_text", RecognitionQuality.handwritten(Arrays.asList(
                sample(null, Float.NaN), sample(" \t\u00a0", Float.NaN))).reason);

        RecognitionQuality.Result result = RecognitionQuality.printed(Arrays.asList(
                sample("ABCD", .97f), sample("", Float.NaN), sample("", Float.NaN)));

        assertFalse(result.reliable);
        assertEquals("too_many_empty_lines", result.reason);
    }

    @Test public void weighsMissingConfidenceByRecognizedCharacters() {
        RecognitionQuality.Result result = RecognitionQuality.printed(Arrays.asList(
                sample("甲", .95f), sample("乙丙丁", Float.NaN)));

        assertFalse(result.reliable);
        assertEquals("missing_confidence", result.reason);
        assertEquals(.75f, result.missingConfidenceFraction, TOLERANCE);
        assertEquals(.95f, result.meanConfidence, TOLERANCE);
    }

    @Test public void rejectsAgreementBetweenTwoLowConfidenceModels() {
        RecognitionQuality.Result result = RecognitionQuality.handwritten(Collections.singletonList(
                new RecognitionQuality.Sample("示例文本", .48f, "示例文本", .52f)));

        assertFalse(result.reliable);
        assertEquals("low_confidence", result.reason);
        assertEquals(1f, result.lowConfidenceFraction, TOLERANCE);
        assertEquals(0f, result.disagreementFraction, TOLERANCE);
    }

    @Test public void weighsLowConfidenceByRecognizedCharacters() {
        RecognitionQuality.Result result = RecognitionQuality.printed(Arrays.asList(
                sample("甲", .96f), sample("乙", .96f), sample("丙丁戊己庚辛壬癸", .55f)));

        assertFalse(result.reliable);
        assertEquals("low_confidence", result.reason);
        assertEquals(.8f, result.lowConfidenceFraction, TOLERANCE);
        assertEquals(.632f, result.meanConfidence, TOLERANCE);
    }

    @Test public void rejectsBroadDisagreementBetweenHighConfidenceModels() {
        RecognitionQuality.Result result = RecognitionQuality.handwritten(Collections.singletonList(
                new RecognitionQuality.Sample("A甲123", .97f, "B乙456", .96f)));

        assertFalse(result.reliable);
        assertEquals("model_disagreement", result.reason);
        assertEquals(1f, result.disagreementFraction, TOLERANCE);
    }

    @Test public void acceptsMinorDisagreementAndIgnoresAnAbsentAlternative() {
        RecognitionQuality.Result result = RecognitionQuality.handwritten(Arrays.asList(
                new RecognitionQuality.Sample("中ABC 123", .97f, "中ABC 124", .96f),
                new RecognitionQuality.Sample("示例", .92f, "", Float.NaN)));

        assertTrue(result.reliable);
        assertEquals(1f / 9, result.disagreementFraction, TOLERANCE);
    }

    private static RecognitionQuality.Sample sample(String text, float confidence) {
        return new RecognitionQuality.Sample(text, confidence, null, Float.NaN);
    }

    @Test public void qualityMetadataSurvivesRelayoutAndCannotUpgradeLegacyOrSkippedPages() throws Exception {
        OfficeTable table = new OfficeTable("表", 1, 1, 1, true,
                Collections.singletonList(new OfficeTable.Cell(0, 0, 1, 1, "00107")));
        ScanRecognition.TableDraft current = new ScanRecognition.TableDraft(Collections.singletonList(table),
                Collections.emptyList()).checked(ScanRecognition.HAND_TABLE);
        ScanRecognition.TableDraft restored = ScanRecognition.TableDraft.from(current.withTables(current.tables).json());
        assertTrue(restored.checkedFor(ScanRecognition.HAND_TABLE));
        assertFalse(restored.checkedFor(ScanRecognition.EXCEL));
        ScanRecognition.TableDraft legacy = new ScanRecognition.TableDraft(current.tables, Collections.emptyList());
        assertFalse(ScanRecognition.TableDraft.from(legacy.withTables(legacy.tables).json())
                .checkedFor(ScanRecognition.HAND_TABLE));
        assertFalse(new ScanRecognition.TableDraft(current.tables, Collections.singletonList(2))
                .checked(ScanRecognition.HAND_TABLE).checkedFor(ScanRecognition.HAND_TABLE));
    }
}
