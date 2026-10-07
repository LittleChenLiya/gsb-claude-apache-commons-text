/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.text.StringDependencyAnalyzer.Plan;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link StringDependencyAnalyzer}.
 */
class StringDependencyAnalyzerTest {

    private static Plan analyze(final StringSubstitutor substitutor, final String template,
        final Map<String, String> values) {
        return new StringDependencyAnalyzer(substitutor).analyze(template, values);
    }

    private static List<String> edges(final Plan plan) {
        return plan.getEdges().stream().map(StringDependencyAnalyzer.Edge::toString).collect(Collectors.toList());
    }

    @Test
    void testChainResolutionOrderComesDependenciesFirst() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("a", "${b}");
        values.put("b", "${c}");
        values.put("c", "plain");
        final Map<String, String> snapshot = new HashMap<>(values);
        final Plan plan = analyze(new StringSubstitutor(values), "${a}", values);
        // c must come before b, and b before a
        assertEquals(Arrays.asList("c", "b", "a"), plan.getResolutionOrder());
        assertEquals(Arrays.asList("a -> b", "b -> c"), edges(plan));
        assertEquals(new LinkedHashSet<>(Arrays.asList("a", "b", "c")), plan.getVariables());
        assertFalse(plan.hasCycles());
        assertFalse(plan.hasMissingVariables());
        // the read-only map was not modified and nothing was expanded into it
        assertEquals(snapshot, values);
        assertEquals("plain", values.get("c"));
    }

    @Test
    void testCycleBetweenTwoVariablesIsReportedNotThrown() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("a", "${b}");
        values.put("b", "${a}");
        final Plan plan = analyze(new StringSubstitutor(values), "${a}", values);
        assertTrue(plan.hasCycles());
        assertEquals(Collections.singletonList(Arrays.asList("a", "b", "a")), plan.getCycles());
        assertEquals(Arrays.asList("a -> b", "b -> a"), edges(plan));
        assertFalse(plan.hasMissingVariables());
    }

    @Test
    void testCycleInUnreachedDefaultBranchIsIgnored() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("x", "present");
        values.put("y", "${z}");
        values.put("z", "${y}");
        // x is defined, so the ${y} default branch never participates and the y/z cycle is irrelevant
        final StringSubstitutor sub = new StringSubstitutor(values).setEnableSubstitutionInVariables(true);
        final Plan plan = analyze(sub, "${x:-${y}}", values);
        assertFalse(plan.hasCycles());
        assertEquals(Collections.singleton("x"), plan.getVariables());
        assertTrue(plan.getEdges().isEmpty());
        assertFalse(plan.hasMissingVariables());
    }

    @Test
    void testDefaultBranchParticipatesOnlyWhenPrimaryVariableIsMissing() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("y", "${z}");
        values.put("z", "${y}");
        // x is missing, so the default branch participates and the y/z cycle is detected
        final StringSubstitutor sub = new StringSubstitutor(values).setEnableSubstitutionInVariables(true);
        final Plan plan = analyze(sub, "${x:-${y}}", values);
        assertTrue(plan.hasCycles());
        assertEquals(Collections.singletonList(Arrays.asList("y", "z", "y")), plan.getCycles());
        // x has a default branch, so it is not reported missing
        assertFalse(plan.getMissingVariables().contains("x"));
        assertEquals(Arrays.asList("x -> y", "y -> z", "z -> y"), edges(plan));
    }

    @Test
    void testEscapedPrefixIsNotAVariableReference() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("a", "A");
        values.put("b", "B");
        final Plan plan = analyze(new StringSubstitutor(values), "$${a} ${b}", values);
        assertEquals(Collections.singleton("b"), plan.getVariables());
        assertTrue(plan.getEdges().isEmpty());
        assertFalse(plan.hasMissingVariables());
    }

    @Test
    void testMissingVariableIsReported() {
        final Plan plan = analyze(new StringSubstitutor(Collections.emptyMap()), "${missing}",
            Collections.emptyMap());
        assertEquals(Collections.singleton("missing"), plan.getMissingVariables());
        assertTrue(plan.hasMissingVariables());
        assertFalse(plan.hasCycles());
    }

    @Test
    void testMissingVariableWithLiteralDefaultIsNotMissing() {
        final Plan plan = analyze(new StringSubstitutor(Collections.emptyMap()), "${missing:-42}",
            Collections.emptyMap());
        assertFalse(plan.hasMissingVariables());
        assertEquals(Collections.singleton("missing"), plan.getVariables());
    }

    @Test
    void testCustomPrefixSuffixEscapeAndValueDelimiter() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("a", "%(b)");
        values.put("b", "v");
        final StringSubstitutor sub = new StringSubstitutor(values, "%(", ")", '\\', "|");
        final Plan plan = analyze(sub, "%(a) \\%(b)", values);
        // %(b) is escaped, so only the chain a -> b participates
        assertEquals(Arrays.asList("b", "a"), plan.getResolutionOrder());
        assertEquals(Collections.singletonList("a -> b"), edges(plan));
        assertFalse(plan.hasCycles());
        assertFalse(plan.hasMissingVariables());
    }

    @Test
    void testCustomValueDelimiterDefaultBranch() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("y", "why");
        final StringSubstitutor sub = new StringSubstitutor(values, "%(", ")", '\\', "|")
            .setEnableSubstitutionInVariables(true);
        final Plan plan = analyze(sub, "%(x|%(y))", values);
        // x is missing so its default branch participates and pulls in y
        assertEquals(Arrays.asList("y", "x"), plan.getResolutionOrder());
        assertEquals(Collections.singletonList("x -> y"), edges(plan));
        assertFalse(plan.getMissingVariables().contains("x"));
    }

    @Test
    void testDiamondDependencyIsOrderedAndDeduplicated() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("a", "${b} ${c}");
        values.put("b", "${d}");
        values.put("c", "${d}");
        values.put("d", "leaf");
        final Plan plan = analyze(new StringSubstitutor(values), "${a}", values);
        assertEquals(Arrays.asList("d", "b", "c", "a"), plan.getResolutionOrder());
        assertEquals(Arrays.asList("a -> b", "b -> d", "a -> c", "c -> d"), edges(plan));
        assertFalse(plan.hasCycles());
    }

    @Test
    void testNullAndEmptyTemplatesYieldEmptyPlan() {
        final StringDependencyAnalyzer analyzer = new StringDependencyAnalyzer(new StringSubstitutor());
        assertTrue(analyzer.analyze(null, Collections.emptyMap()).getVariables().isEmpty());
        assertTrue(analyzer.analyze("no variables here", Collections.emptyMap()).getVariables().isEmpty());
    }

    @Test
    void testSelfReferenceIsACycle() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("a", "${a}");
        final Plan plan = analyze(new StringSubstitutor(values), "${a}", values);
        assertTrue(plan.hasCycles());
        assertEquals(Collections.singletonList(Arrays.asList("a", "a")), plan.getCycles());
    }

    @Test
    void testTemplateIsNotExpandedByAnalysis() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("a", "${b}");
        values.put("b", "expanded-value");
        final String template = "prefix ${a} suffix";
        final Plan plan = analyze(new StringSubstitutor(values), template, values);
        // the plan talks about variables, never about expanded text
        assertFalse(plan.getVariables().contains("expanded-value"));
        assertEquals("prefix ${a} suffix", template);
        assertEquals("${b}", values.get("a"));
    }
}
