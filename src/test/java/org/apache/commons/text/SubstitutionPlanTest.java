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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link SubstitutionPlan}.
 */
class SubstitutionPlanTest {

    private static List<String> edges(final SubstitutionPlan plan) {
        return plan.getEdges().stream().map(SubstitutionPlan.Edge::toString).collect(Collectors.toList());
    }

    private static Map<String, String> map(final String... keyValues) {
        final Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void testChainIsOrderedDependenciesFirst() {
        final Map<String, String> values = map("a", "${b}", "b", "${c}", "c", "plain");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
        assertEquals(Arrays.asList("c", "b", "a"), plan.getResolutionOrder());
        assertEquals(Arrays.asList("a -> b", "b -> c"), edges(plan));
        assertEquals(3, plan.getReferencedVariables().size());
        assertTrue(plan.getMissingVariables().isEmpty());
        assertFalse(plan.hasCycle());
        assertTrue(plan.getCycles().isEmpty());
    }

    @Test
    void testCycleBetweenTwoVariablesIsReportedNotThrown() {
        final Map<String, String> values = map("a", "${b}", "b", "${a}");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
        assertTrue(plan.hasCycle());
        assertEquals(1, plan.getCycles().size());
        assertEquals(Arrays.asList("a", "b", "a"), plan.getCycles().get(0));
        assertEquals(Arrays.asList("a -> b", "b -> a"), edges(plan));
    }

    @Test
    void testSelfReferenceIsReportedAsCycle() {
        final Map<String, String> values = map("a", "${a}");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
        assertTrue(plan.hasCycle());
        assertEquals(Collections.singletonList(Arrays.asList("a", "a")), plan.getCycles());
    }

    @Test
    void testCycleInUntakenDefaultBranchIsIgnored() {
        // "present" is in the map, so its default branch is never followed: the cycle through "loop" is untaken.
        final Map<String, String> values = map("present", "value", "loop", "${loop}");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values),
            "${present:-${loop}}", values);
        assertFalse(plan.hasCycle());
        assertTrue(plan.getCycles().isEmpty());
        assertEquals(Collections.singletonList("present"), plan.getResolutionOrder());
        assertTrue(plan.getEdges().isEmpty());
        assertTrue(plan.getMissingVariables().isEmpty());
    }

    @Test
    void testDefaultBranchParticipatesOnlyWhenVariableMissing() {
        final Map<String, String> values = map("fallback", "${c}", "c", "plain");
        final StringSubstitutor sub = new StringSubstitutor(values);
        sub.setEnableSubstitutionInVariables(true);
        final SubstitutionPlan plan = SubstitutionPlan.analyze(sub, "${missing:-${fallback}}", values);
        assertEquals(Collections.singleton("missing"), plan.getMissingVariables());
        assertEquals(Arrays.asList("missing -> fallback", "fallback -> c"), edges(plan));
        assertEquals(Arrays.asList("c", "fallback", "missing"), plan.getResolutionOrder());
        assertFalse(plan.hasCycle());
    }

    @Test
    void testDefaultBranchOfPresentVariableIsNotFollowed() {
        // "a" is present, so the reference to "b" in its untaken default branch is not a dependency.
        final Map<String, String> values = map("a", "value", "b", "other");
        final StringSubstitutor sub = new StringSubstitutor(values);
        sub.setEnableSubstitutionInVariables(true);
        final SubstitutionPlan plan = SubstitutionPlan.analyze(sub, "${a:-${b}}", values);
        assertEquals(Collections.singletonList("a"), plan.getResolutionOrder());
        assertTrue(plan.getEdges().isEmpty());
        assertTrue(plan.getMissingVariables().isEmpty());
    }

    @Test
    void testEscapedReferenceIsNotRead() {
        final Map<String, String> values = map("a", "1", "b", "2");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values),
            "$${a} and ${b}", values);
        assertEquals(Collections.singleton("b"), plan.getReferencedVariables());
        assertEquals(Collections.singletonList("b"), plan.getResolutionOrder());
        assertTrue(plan.getEdges().isEmpty());
    }

    @Test
    void testMissingVariableWithoutDefault() {
        final Map<String, String> values = map("a", "1");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values),
            "${a} ${nope}", values);
        assertEquals(Collections.singleton("nope"), plan.getMissingVariables());
        assertEquals(2, plan.getReferencedVariables().size());
        assertFalse(plan.hasCycle());
    }

    @Test
    void testCustomDelimitersAndEscape() {
        final Map<String, String> values = map("a", "%(b)", "b", "done");
        final StringSubstitutor sub = new StringSubstitutor(values, "%(", ")", '%', "?");
        sub.setEnableSubstitutionInVariables(true);
        final SubstitutionPlan plan = SubstitutionPlan.analyze(sub, "%(a) %%(b) %(unknown?%(b))", values);
        // %(a) reads a; %%(b) is escaped; %(unknown?%(b)) is missing so its default branch reads b.
        assertEquals(Collections.singleton("unknown"), plan.getMissingVariables());
        assertEquals(Arrays.asList("a -> b", "unknown -> b"), edges(plan));
        assertEquals(Arrays.asList("b", "a", "unknown"), plan.getResolutionOrder());
        assertFalse(plan.hasCycle());
    }

    @Test
    void testCustomValueDelimiter() {
        final Map<String, String> values = map("c", "plain");
        final StringSubstitutor sub = new StringSubstitutor(values);
        sub.setValueDelimiter("?");
        sub.setEnableSubstitutionInVariables(true);
        final SubstitutionPlan plan = SubstitutionPlan.analyze(sub, "${missing?${c}}", values);
        assertEquals(Collections.singleton("missing"), plan.getMissingVariables());
        assertEquals(Collections.singletonList("missing -> c"), edges(plan));
        assertEquals(Arrays.asList("c", "missing"), plan.getResolutionOrder());
    }

    @Test
    void testNullArgumentsRejected() {
        final Map<String, String> values = Collections.emptyMap();
        assertThrows(NullPointerException.class, () -> SubstitutionPlan.analyze(null, values));
        assertThrows(NullPointerException.class, () -> SubstitutionPlan.analyze("${a}", null));
        assertThrows(NullPointerException.class,
            () -> SubstitutionPlan.analyze(null, "${a}", values));
    }

    @Test
    void testMapIsNotModifiedAndNothingIsExpanded() {
        final Map<String, String> values = new HashMap<>(map("a", "${b}", "b", "${c}", "c", "plain"));
        final Map<String, String> snapshot = new HashMap<>(values);
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
        assertEquals(snapshot, values);
        // The plan exposes structure only; no expanded text is produced anywhere.
        assertEquals(Arrays.asList("c", "b", "a"), plan.getResolutionOrder());
    }

    @Test
    void testEdgeEqualityAndToString() {
        final Map<String, String> values = map("a", "${b}", "b", "x");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
        final SubstitutionPlan.Edge edge = plan.getEdges().get(0);
        assertEquals("a", edge.getFrom());
        assertEquals("b", edge.getTo());
        assertEquals("a -> b", edge.toString());
        assertEquals(edge, edge);
        assertFalse(edge.equals(null));
        assertFalse(edge.equals("a -> b"));
        assertEquals(edge.hashCode(), plan.getEdges().get(0).hashCode());
    }

    @Test
    void testSharedDependencyAppearsOnceInOrder() {
        final Map<String, String> values = map("a", "${b} ${c}", "b", "${c}", "c", "plain");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
        assertEquals(Arrays.asList("c", "b", "a"), plan.getResolutionOrder());
        assertEquals(Arrays.asList("a -> b", "b -> c", "a -> c"), edges(plan));
    }

    @Test
    void testUnterminatedVariableIsLiteral() {
        final Map<String, String> values = map("a", "1");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a", values);
        assertTrue(plan.getReferencedVariables().isEmpty());
        assertTrue(plan.getResolutionOrder().isEmpty());
    }

    @Test
    void testPlanIsImmutable() {
        final Map<String, String> values = map("a", "${b}", "b", "x");
        final SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
        assertThrows(UnsupportedOperationException.class, () -> plan.getResolutionOrder().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> plan.getEdges().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.getMissingVariables().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> plan.getReferencedVariables().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.getCycles().add(null));
    }
}
