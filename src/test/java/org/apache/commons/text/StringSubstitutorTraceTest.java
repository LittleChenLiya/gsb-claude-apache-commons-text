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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.text.SubstitutionTrace.Resolution;
import org.apache.commons.text.SubstitutionTrace.Span;
import org.apache.commons.text.lookup.StringLookup;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link StringSubstitutor#replaceWithTrace(Object)}.
 */
class StringSubstitutorTraceTest {

    /**
     * A lookup that counts how many times each key is actually evaluated.
     */
    private static final class CountingLookup implements StringLookup {

        private final Map<String, String> values;

        private final Map<String, Integer> counts = new HashMap<>();

        private CountingLookup(final Map<String, String> values) {
            this.values = values;
        }

        int count(final String key) {
            return counts.getOrDefault(key, 0);
        }

        @Override
        public String lookup(final String key) {
            counts.merge(key, 1, Integer::sum);
            return values.get(key);
        }
    }

    private static Map<String, String> mapOf(final String... keyValues) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * Asserts that the spans cover the whole result contiguously.
     */
    private static void assertSpansCoverResult(final SubstitutionTrace trace) {
        int pos = 0;
        for (final Span span : trace.getSpans()) {
            assertEquals(pos, span.getStart(), "spans must be contiguous");
            pos = span.getEnd();
        }
        assertEquals(trace.getResult().length(), pos, "spans must cover the whole result");
    }

    @Test
    void testDefaultValueAttributedToDefaultWhenVariableMissing() {
        final CountingLookup lookup = new CountingLookup(mapOf());
        final SubstitutionTrace trace = new StringSubstitutor(lookup).replaceWithTrace("Hi ${user:-guest}");
        assertEquals("Hi guest", trace.getResult());
        assertEquals(1, lookup.count("user"), "lookup must be called exactly once");
        assertEquals(1, trace.getResolutions().size());
        final Resolution resolution = trace.getResolutions().get(0);
        assertEquals("user", resolution.getVariableName());
        assertEquals("guest", resolution.getValue());
        assertTrue(resolution.isDefaultValueUsed());
        assertNull(resolution.getParent());
        assertEquals(3, resolution.getTemplateStart());
        assertEquals(17, resolution.getTemplateEnd());
        assertSpansCoverResult(trace);
        final Span literal = trace.spanAt(0);
        assertTrue(literal.isLiteral());
        assertEquals(0, literal.getTemplateStart());
        assertEquals(3, literal.getTemplateEnd());
        final Span value = trace.spanAt(4);
        assertFalse(value.isLiteral());
        assertSame(resolution, value.getResolution());
        assertEquals(3, value.getStart());
        assertEquals(8, value.getEnd());
        assertEquals(0, value.getValueStart());
        assertEquals(5, value.getValueEnd());
    }

    @Test
    void testVariableValueAttributedToVariableWhenPresent() {
        final CountingLookup lookup = new CountingLookup(mapOf("user", "Alice"));
        final SubstitutionTrace trace = new StringSubstitutor(lookup).replaceWithTrace("Hi ${user:-guest}");
        assertEquals("Hi Alice", trace.getResult());
        assertEquals(1, lookup.count("user"), "lookup must be called exactly once");
        assertEquals(1, trace.getResolutions().size());
        final Resolution resolution = trace.getResolutions().get(0);
        assertEquals("user", resolution.getVariableName());
        assertEquals("Alice", resolution.getValue());
        assertFalse(resolution.isDefaultValueUsed());
        assertEquals(3, resolution.getTemplateStart());
        assertEquals(17, resolution.getTemplateEnd());
        assertSpansCoverResult(trace);
        final Span value = trace.spanAt(3);
        assertSame(resolution, value.getResolution());
        assertEquals(3, value.getStart());
        assertEquals(8, value.getEnd());
    }

    @Test
    void testNestedValueResolutionKeepsParentChain() {
        final CountingLookup lookup = new CountingLookup(mapOf("a", "${b}", "b", "${c}", "c", "!"));
        final SubstitutionTrace trace = new StringSubstitutor(lookup).replaceWithTrace("[${a}]");
        assertEquals("[!]", trace.getResult());
        assertEquals(1, lookup.count("a"));
        assertEquals(1, lookup.count("b"));
        assertEquals(1, lookup.count("c"));
        assertEquals(3, trace.getResolutions().size());
        // From the final character back to the original reference in the template.
        final Span span = trace.spanAt(1);
        assertFalse(span.isLiteral());
        final Resolution resC = span.getResolution();
        assertEquals("c", resC.getVariableName());
        assertEquals("!", resC.getValue());
        assertEquals(-1, resC.getTemplateStart(), "nested reference is not directly in the template");
        final Resolution resB = resC.getParent();
        assertEquals("b", resB.getVariableName());
        final Resolution resA = resB.getParent();
        assertEquals("a", resA.getVariableName());
        assertNull(resA.getParent());
        assertSame(resA, resC.getRoot());
        assertEquals(1, resA.getTemplateStart(), "root resolution locates the original reference");
        assertEquals(5, resA.getTemplateEnd());
        assertEquals(2, resC.getDepth());
        assertEquals(0, resA.getDepth());
        assertSpansCoverResult(trace);
    }

    @Test
    void testNestedVariableNamesKeepParentChain() {
        final CountingLookup lookup = new CountingLookup(mapOf("name", "x", "x", "V"));
        final StringSubstitutor sub = new StringSubstitutor(lookup);
        sub.setEnableSubstitutionInVariables(true);
        final SubstitutionTrace trace = sub.replaceWithTrace("${${name}}");
        assertEquals("V", trace.getResult());
        assertEquals(1, lookup.count("name"), "each variable is evaluated exactly once");
        assertEquals(1, lookup.count("x"));
        assertEquals(2, trace.getResolutions().size());
        final Resolution resName = trace.getResolutions().get(0);
        assertEquals("name", resName.getVariableName());
        assertEquals("x", resName.getValue());
        assertEquals(2, resName.getTemplateStart());
        assertEquals(9, resName.getTemplateEnd());
        final Resolution resX = trace.getResolutions().get(1);
        assertEquals("x", resX.getVariableName());
        assertEquals("V", resX.getValue());
        assertEquals(0, resX.getTemplateStart());
        assertEquals(10, resX.getTemplateEnd());
        assertNull(resX.getParent());
        assertSame(resX, resName.getParent(), "inner name resolution links to the enclosing resolution");
        assertSame(resX, trace.spanAt(0).getResolution());
        assertSpansCoverResult(trace);
    }

    @Test
    void testSurrogatePairsUseUtf16Offsets() {
        final String emoji = "\uD83D\uDE00"; // 2 UTF-16 code units
        final CountingLookup lookup = new CountingLookup(mapOf("u", "ab"));
        final SubstitutionTrace trace = new StringSubstitutor(lookup).replaceWithTrace(emoji + "${u}" + emoji);
        assertEquals(emoji + "ab" + emoji, trace.getResult());
        assertEquals(1, lookup.count("u"));
        assertEquals(3, trace.getSpans().size());
        final Span leading = trace.getSpans().get(0);
        assertTrue(leading.isLiteral());
        assertEquals(0, leading.getStart());
        assertEquals(2, leading.getEnd(), "a surrogate pair counts as 2 UTF-16 units");
        assertEquals(0, leading.getTemplateStart());
        assertEquals(2, leading.getTemplateEnd());
        final Span value = trace.getSpans().get(1);
        assertEquals(2, value.getStart());
        assertEquals(4, value.getEnd());
        assertEquals("u", value.getResolution().getVariableName());
        assertEquals(2, value.getResolution().getTemplateStart());
        assertEquals(6, value.getResolution().getTemplateEnd());
        final Span trailing = trace.getSpans().get(2);
        assertTrue(trailing.isLiteral());
        assertEquals(4, trailing.getStart());
        assertEquals(6, trailing.getEnd());
        assertEquals(6, trailing.getTemplateStart());
        assertEquals(8, trailing.getTemplateEnd());
        assertSpansCoverResult(trace);
    }

    @Test
    void testRepeatedVariableEvaluatedPerOccurrence() {
        final CountingLookup lookup = new CountingLookup(mapOf("x", "v"));
        final SubstitutionTrace trace = new StringSubstitutor(lookup).replaceWithTrace("${x} ${x}");
        assertEquals("v v", trace.getResult());
        assertEquals(2, lookup.count("x"), "no caching and no extra tracing calls");
        assertEquals(2, trace.getResolutions().size());
        assertEquals(0, trace.getResolutions().get(0).getTemplateStart());
        assertEquals(4, trace.getResolutions().get(0).getTemplateEnd());
        assertEquals(5, trace.getResolutions().get(1).getTemplateStart());
        assertEquals(9, trace.getResolutions().get(1).getTemplateEnd());
        assertSame(trace.getResolutions().get(0), trace.spanAt(0).getResolution());
        assertTrue(trace.spanAt(1).isLiteral());
        assertSame(trace.getResolutions().get(1), trace.spanAt(2).getResolution());
        assertSpansCoverResult(trace);
    }

    @Test
    void testEscapedVariableIsLiteralAndNotEvaluated() {
        final CountingLookup lookup = new CountingLookup(mapOf("x", "v"));
        final SubstitutionTrace trace = new StringSubstitutor(lookup).replaceWithTrace("$${x}");
        assertEquals("${x}", trace.getResult());
        assertEquals(0, lookup.count("x"), "escaped variables must not be evaluated");
        assertTrue(trace.getResolutions().isEmpty());
        assertEquals(1, trace.getSpans().size());
        final Span span = trace.getSpans().get(0);
        assertTrue(span.isLiteral());
        assertEquals(0, span.getStart());
        assertEquals(4, span.getEnd());
        assertEquals(1, span.getTemplateStart(), "the escape character is removed from the output");
        assertEquals(5, span.getTemplateEnd());
        assertSpansCoverResult(trace);
    }

    @Test
    void testRecursiveCycleThrows() {
        final CountingLookup lookup = new CountingLookup(mapOf("a", "${a}"));
        final StringSubstitutor sub = new StringSubstitutor(lookup);
        assertThrows(IllegalStateException.class, () -> sub.replaceWithTrace("${a}"));
    }

    @Test
    void testUndefinedVariableStaysLiteral() {
        final CountingLookup lookup = new CountingLookup(mapOf());
        final SubstitutionTrace trace = new StringSubstitutor(lookup).replaceWithTrace("a ${unknown} b");
        assertEquals("a ${unknown} b", trace.getResult());
        assertEquals(1, lookup.count("unknown"));
        assertTrue(trace.getResolutions().isEmpty());
        assertEquals(1, trace.getSpans().size());
        assertTrue(trace.getSpans().get(0).isLiteral());
        assertSpansCoverResult(trace);
    }

    @Test
    void testTraceResultMatchesReplace() {
        final Map<String, String> values = mapOf("animal", "quick brown fox", "target", "lazy dog");
        final String template = "The ${animal} jumps over the ${target} $${animal} ${undefined:-13} times.";
        final StringSubstitutor sub = new StringSubstitutor(values);
        assertEquals(sub.replace(template), sub.replaceWithTrace(template).getResult());
    }

    @Test
    void testLookupNotReexecutedForTracing() {
        final CountingLookup lookup = new CountingLookup(mapOf("a", "${b}", "b", "1", "x", "2"));
        final StringSubstitutor sub = new StringSubstitutor(lookup);
        final SubstitutionTrace trace = sub.replaceWithTrace("${a} ${x:-d} ${missing:-d} ${x}");
        assertEquals("1 2 d 2", trace.getResult());
        assertEquals(1, lookup.count("a"));
        assertEquals(1, lookup.count("b"));
        assertEquals(2, lookup.count("x"));
        assertEquals(1, lookup.count("missing"));
        assertEquals(5, trace.getResolutions().size());
        assertSpansCoverResult(trace);
    }

    @Test
    void testNullSourceReturnsNull() {
        assertNull(new StringSubstitutor(mapOf()).replaceWithTrace(null));
    }

    @Test
    void testSpanAtOutOfRange() {
        final SubstitutionTrace trace = new StringSubstitutor(mapOf()).replaceWithTrace("abc");
        assertThrows(IndexOutOfBoundsException.class, () -> trace.spanAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> trace.spanAt(3));
    }
}
