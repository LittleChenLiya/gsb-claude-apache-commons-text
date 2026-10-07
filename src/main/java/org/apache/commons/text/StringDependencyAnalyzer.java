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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.text.matcher.StringMatcher;

/**
 * Computes a dependency plan for a {@link StringSubstitutor} template without expanding the text.
 * <p>
 * Given a template and a read-only map of variable values, this analyzer reports which variables actually need to be
 * read, the edges between them (a variable whose value or default value references another variable), missing
 * variables, and reference cycles. The template is never substituted and the value map is only queried through
 * {@link Map#get(Object)}; it is never modified.
 * </p>
 * <p>
 * The analyzer reuses the variable prefix, variable suffix, escape character and default value delimiter configured on
 * a {@link StringSubstitutor}, so the plan matches what the substitutor would resolve:
 * </p>
 * <ul>
 * <li>Escaped variable references (for example {@code $${name}} with the default escape) are ignored.</li>
 * <li>A variable default value branch (for example the {@code fallback} in {@code ${name:-fallback}}) only contributes
 * dependencies when the primary variable is absent from the value map.</li>
 * <li>Values are scanned for nested variable references, producing the edges of the dependency graph.</li>
 * </ul>
 * <p>
 * Example:
 * </p>
 *
 * <pre>
 * Map&lt;String, String&gt; values = new HashMap&lt;&gt;();
 * values.put(&quot;a&quot;, &quot;${b}&quot;);
 * values.put(&quot;b&quot;, &quot;${c}&quot;);
 * values.put(&quot;c&quot;, &quot;done&quot;);
 * StringDependencyAnalyzer.Plan plan = new StringDependencyAnalyzer(new StringSubstitutor(values))
 *     .analyze(&quot;${a}&quot;, values);
 * // plan.getResolutionOrder() == [c, b, a]
 * // plan.getEdges()          == [a -&gt; b, b -&gt; c]
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is immutable and thread safe. Each call returns a new {@link Plan}.
 * </p>
 *
 * @since 1.15.1
 */
public final class StringDependencyAnalyzer {

    /**
     * A directed edge between two variables: {@code from} depends on {@code to} because the value (or active default
     * value branch) of {@code from} references {@code to}.
     *
     * @since 1.15.1
     */
    public static final class Edge {

        /** The variable that holds the reference. */
        private final String from;

        /** The variable being referenced. */
        private final String to;

        private Edge(final String from, final String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Edge)) {
                return false;
            }
            final Edge other = (Edge) obj;
            return from.equals(other.from) && to.equals(other.to);
        }

        /**
         * Gets the variable that holds the reference.
         *
         * @return the source variable name.
         */
        public String getFrom() {
            return from;
        }

        /**
         * Gets the variable being referenced.
         *
         * @return the target variable name.
         */
        public String getTo() {
            return to;
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to);
        }

        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }

    /**
     * The result of analyzing a template: the variables that must be read, the dependency edges between them, a
     * dependency-first resolution order, missing variables and detected cycles.
     *
     * @since 1.15.1
     */
    public static final class Plan {

        /** Variables on the current DFS path, used for cycle detection. */
        private final Deque<String> stack = new ArrayDeque<>();

        /** Names currently on {@link #stack}, for constant-time membership tests. */
        private final Set<String> onStack = new LinkedHashSet<>();

        /** Variables whose subgraph has been fully explored. */
        private final Set<String> done = new LinkedHashSet<>();

        /** Every variable that needs to be read, in first-seen order. */
        private final Set<String> variables = new LinkedHashSet<>();

        /** Dependency edges, deduplicated, in discovery order. */
        private final List<Edge> edges = new ArrayList<>();

        /** Referenced variables absent from the value map and without a default value. */
        private final Set<String> missingVariables = new LinkedHashSet<>();

        /** Detected cycles, each expressed as a path whose last element repeats an earlier one. */
        private final List<List<String>> cycles = new ArrayList<>();

        /** Dependency-first order: a variable appears after the variables it depends on. */
        private final List<String> resolutionOrder = new ArrayList<>();

        private Plan() {
        }

        private void addEdge(final String from, final String to) {
            final Edge edge = new Edge(from, to);
            if (!edges.contains(edge)) {
                edges.add(edge);
            }
        }

        /**
         * Gets the dependency edges between variables. An edge {@code a -> b} means the value (or active default
         * value branch) of {@code a} references {@code b}.
         *
         * @return the edges in discovery order.
         */
        public List<Edge> getEdges() {
            return Collections.unmodifiableList(edges);
        }

        /**
         * Gets the detected reference cycles. Each cycle is a path of variable names whose last element repeats the
         * variable that closed the loop, for example {@code [a, b, a]}.
         *
         * @return the cycles, empty when the dependency graph is acyclic.
         */
        public List<List<String>> getCycles() {
            return Collections.unmodifiableList(cycles);
        }

        /**
         * Gets the variables that were referenced but are absent from the value map and carry no default value.
         *
         * @return the missing variable names.
         */
        public Set<String> getMissingVariables() {
            return Collections.unmodifiableSet(missingVariables);
        }

        /**
         * Gets the variables in dependency-first order: every variable appears after the variables it depends on, so
         * resolving them in this order never reads an unresolved dependency. Variables involved in cycles are included
         * in an arbitrary but deterministic position.
         *
         * @return the resolution order.
         */
        public List<String> getResolutionOrder() {
            return Collections.unmodifiableList(resolutionOrder);
        }

        /**
         * Gets every variable that needs to be read to resolve the template, in first-seen order.
         *
         * @return the variable names.
         */
        public Set<String> getVariables() {
            return Collections.unmodifiableSet(variables);
        }

        /**
         * Tests whether at least one reference cycle was detected.
         *
         * @return true if the dependency graph contains a cycle.
         */
        public boolean hasCycles() {
            return !cycles.isEmpty();
        }

        /**
         * Tests whether at least one referenced variable is missing from the value map without a default value.
         *
         * @return true if there are missing variables.
         */
        public boolean hasMissingVariables() {
            return !missingVariables.isEmpty();
        }

        private void recordCycle(final String name) {
            final List<String> cycle = new ArrayList<>();
            boolean onPath = false;
            // walk the DFS path from its oldest element to the current one
            for (final Iterator<String> it = stack.descendingIterator(); it.hasNext();) {
                final String element = it.next();
                if (element.equals(name)) {
                    onPath = true;
                }
                if (onPath) {
                    cycle.add(element);
                }
            }
            cycle.add(name);
            if (!cycles.contains(cycle)) {
                cycles.add(cycle);
            }
        }

        @Override
        public String toString() {
            return "Plan [variables=" + variables + ", edges=" + edges + ", resolutionOrder=" + resolutionOrder
                + ", missingVariables=" + missingVariables + ", cycles=" + cycles + "]";
        }
    }

    /**
     * A variable reference found while scanning: the variable name and its optional default value expression.
     */
    private static final class VarRef {

        /** The variable name. */
        private final String name;

        /** The default value expression, null when absent. */
        private final String defaultExpr;

        private VarRef(final String name, final String defaultExpr) {
            this.name = name;
            this.defaultExpr = defaultExpr;
        }
    }

    /**
     * Creates an analyzer that reuses the configuration of the given substitutor.
     *
     * @param substitutor the substitutor supplying prefix, suffix, escape and value delimiter rules, not null.
     * @return a new analyzer.
     */
    public static StringDependencyAnalyzer of(final StringSubstitutor substitutor) {
        return new StringDependencyAnalyzer(substitutor);
    }

    /** The variable prefix matcher, copied from the substitutor. */
    private final StringMatcher prefixMatcher;

    /** The variable suffix matcher, copied from the substitutor. */
    private final StringMatcher suffixMatcher;

    /** The default value delimiter matcher, may be null. */
    private final StringMatcher valueDelimiterMatcher;

    /** The escape character. */
    private final char escapeChar;

    /** Whether substitution in variable names is enabled. */
    private final boolean substitutionInVariablesEnabled;

    /** Whether substitution in variable values is disabled. */
    private final boolean substitutionInValuesDisabled;

    /**
     * Constructs an analyzer reusing the variable prefix, suffix, escape character and default value delimiter rules
     * of the given substitutor.
     *
     * @param substitutor the substitutor whose syntax configuration is reused, not null.
     */
    public StringDependencyAnalyzer(final StringSubstitutor substitutor) {
        Objects.requireNonNull(substitutor, "substitutor");
        this.prefixMatcher = substitutor.getVariablePrefixMatcher();
        this.suffixMatcher = substitutor.getVariableSuffixMatcher();
        this.valueDelimiterMatcher = substitutor.getValueDelimiterMatcher();
        this.escapeChar = substitutor.getEscapeChar();
        this.substitutionInVariablesEnabled = substitutor.isEnableSubstitutionInVariables();
        this.substitutionInValuesDisabled = substitutor.isDisableSubstitutionInValues();
    }

    /**
     * Analyzes the template against the given value map and returns the dependency plan. The template is not expanded
     * and the map is only read, never modified.
     *
     * @param template the template text, null returns an empty plan.
     * @param valueMap the read-only variable values, may be null (every reference is then reported missing).
     * @return the dependency plan.
     */
    public Plan analyze(final CharSequence template, final Map<String, String> valueMap) {
        final Plan plan = new Plan();
        if (template == null) {
            return plan;
        }
        for (final VarRef ref : scan(template)) {
            visit(ref, null, valueMap, plan);
        }
        return plan;
    }

    /**
     * Scans text for variable references using the configured prefix, suffix and escape rules, without substituting
     * anything.
     *
     * @param text the text to scan, not null.
     * @return the references found, in order of appearance.
     */
    private List<VarRef> scan(final CharSequence text) {
        final List<VarRef> refs = new ArrayList<>();
        final int end = text.length();
        int pos = 0;
        while (pos < end) {
            final int startMatchLen = prefixMatcher.isMatch(text, pos, 0, end);
            if (startMatchLen == 0) {
                pos++;
                continue;
            }
            if (pos > 0 && text.charAt(pos - 1) == escapeChar) {
                // escaped prefix: literal text, not a variable reference
                pos++;
                continue;
            }
            final int startPos = pos;
            pos += startMatchLen;
            int nestedVarCount = 0;
            boolean closed = false;
            while (pos < end) {
                if (substitutionInVariablesEnabled) {
                    final int nestedLen = prefixMatcher.isMatch(text, pos, 0, end);
                    if (nestedLen != 0) {
                        nestedVarCount++;
                        pos += nestedLen;
                        continue;
                    }
                }
                final int endMatchLen = suffixMatcher.isMatch(text, pos, 0, end);
                if (endMatchLen == 0) {
                    pos++;
                    continue;
                }
                if (nestedVarCount == 0) {
                    final String varNameExpr = text.subSequence(startPos + startMatchLen, pos).toString();
                    final VarRef ref = split(varNameExpr);
                    if (substitutionInVariablesEnabled) {
                        // nested variables in the name are resolved first, so they are dependencies too;
                        // the default value branch is scanned later, only if the variable is missing
                        refs.addAll(scan(ref.name));
                    }
                    refs.add(ref);
                    pos += endMatchLen;
                    closed = true;
                    break;
                }
                nestedVarCount--;
                pos += endMatchLen;
            }
            if (!closed) {
                // unclosed prefix: the substitutor leaves the rest of the text untouched
                break;
            }
        }
        return refs;
    }

    /**
     * Splits a variable name expression into the variable name and its default value expression, using the configured
     * value delimiter.
     *
     * @param varNameExpr the raw variable name expression.
     * @return the reference.
     */
    private VarRef split(final String varNameExpr) {
        if (valueDelimiterMatcher != null) {
            final char[] chars = varNameExpr.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                // if there is a nested variable when nested variable substitution is disabled,
                // stop resolving name and default value, mirroring StringSubstitutor
                if (!substitutionInVariablesEnabled && prefixMatcher.isMatch(chars, i, i, chars.length) != 0) {
                    break;
                }
                final int len = valueDelimiterMatcher.isMatch(chars, i, 0, chars.length);
                if (len != 0) {
                    return new VarRef(varNameExpr.substring(0, i), varNameExpr.substring(i + len));
                }
            }
        }
        return new VarRef(varNameExpr, null);
    }

    /**
     * Visits a variable reference: records the edge from its referrer, reads its value from the map and follows the
     * references contained in the value, or in the default value branch when the variable is absent from the map.
     *
     * @param ref the reference to visit.
     * @param from the variable holding this reference, null for references made directly by the template.
     * @param valueMap the read-only value map, may be null.
     * @param plan the plan being built.
     */
    private void visit(final VarRef ref, final String from, final Map<String, String> valueMap, final Plan plan) {
        final String name = ref.name;
        if (from != null) {
            plan.addEdge(from, name);
        }
        if (plan.done.contains(name)) {
            return;
        }
        if (plan.onStack.contains(name)) {
            plan.recordCycle(name);
            return;
        }
        plan.variables.add(name);
        plan.stack.push(name);
        plan.onStack.add(name);
        final String value = valueMap != null ? valueMap.get(name) : null;
        if (value == null) {
            if (ref.defaultExpr != null) {
                // the default branch only participates when the primary variable is missing
                for (final VarRef nested : scan(ref.defaultExpr)) {
                    visit(nested, name, valueMap, plan);
                }
            } else {
                plan.missingVariables.add(name);
            }
        } else if (!substitutionInValuesDisabled) {
            for (final VarRef nested : scan(value)) {
                visit(nested, name, valueMap, plan);
            }
        }
        plan.stack.pop();
        plan.onStack.remove(name);
        plan.done.add(name);
        plan.resolutionOrder.add(name);
    }
}
