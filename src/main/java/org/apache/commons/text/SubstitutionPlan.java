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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.text.matcher.StringMatcher;

/**
 * Computes a dependency plan for a {@link StringSubstitutor} template without expanding the text.
 * <p>
 * Given a template and a read-only map of variable values, this class works out which variables would actually be read
 * during substitution and how they depend on each other, without building the (potentially very large) expanded result
 * string. The analysis reuses the variable prefix, variable suffix, escape character, and default value delimiter
 * rules configured on a {@link StringSubstitutor} instance, so the plan matches what a real substitution would do.
 * </p>
 * <p>
 * The plan reports:
 * </p>
 * <ul>
 * <li>The variables that truly need to be read ({@link #getReferencedVariables()}).</li>
 * <li>The dependency edges between them ({@link #getEdges()}): an edge {@code a -> b} means the text used to resolve
 * {@code a} (its map value, or its default value when {@code a} is missing) references {@code b}.</li>
 * <li>A resolution order ({@link #getResolutionOrder()}) in which dependencies appear before the variables that need
 * them. This order is only guaranteed to be a valid topological order when {@link #hasCycle()} is {@code false}.</li>
 * <li>Variables that are referenced but absent from the value map ({@link #getMissingVariables()}).</li>
 * <li>Circular references ({@link #getCycles()}), reported instead of failing with unbounded recursion.</li>
 * </ul>
 * <p>
 * A variable's default value branch (the part after the value delimiter, as in {@code ${name:-default}}) only
 * participates in the plan when the variable itself is absent from the value map, mirroring how
 * {@link StringSubstitutor} only evaluates the default when the variable cannot be resolved. References appearing in
 * the default branch of a variable that <em>is</em> present are not followed, so cycles hiding in untaken default
 * branches are not reported.
 * </p>
 * <p>
 * Escaped variable references (for example {@code $${name}} with the default escape) are treated as literal text and
 * do not contribute to the plan.
 * </p>
 * <p>
 * Example:
 * </p>
 *
 * <pre>
 * Map&lt;String, String&gt; values = new HashMap&lt;&gt;();
 * values.put("a", "${b}");
 * values.put("b", "${c}");
 * values.put("c", "plain");
 * SubstitutionPlan plan = SubstitutionPlan.analyze(new StringSubstitutor(values), "${a}", values);
 * // plan.getResolutionOrder() -&gt; [c, b, a]
 * // plan.getEdges()           -&gt; [a -&gt; b, b -&gt; c]
 * </pre>
 *
 * <p>
 * This class is immutable and thread-safe. Instances are created through the static {@code analyze} methods.
 * </p>
 *
 * @since 1.14.0
 */
public final class SubstitutionPlan {

    /**
     * A directed dependency edge between two variables. An edge {@code from -> to} means that resolving
     * {@code from} requires reading {@code to}, because {@code to} is referenced by the map value of
     * {@code from} or by the default value branch used when {@code from} is missing.
     *
     * @since 1.14.0
     */
    public static final class Edge {

        /** The variable whose resolution depends on the other endpoint. */
        private final String from;

        /** The variable that must be read to resolve {@code from}. */
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
         * Gets the dependent variable: the one whose resolution requires the other endpoint.
         *
         * @return the dependent variable name.
         */
        public String getFrom() {
            return from;
        }

        /**
         * Gets the required variable: the one read while resolving {@link #getFrom()}.
         *
         * @return the required variable name.
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
     * A single variable reference found while scanning text: a variable name plus the optional default value
     * expression from its default value branch.
     */
    private static final class Reference {

        /** The variable name (the part before the value delimiter). */
        private final String name;

        /** The default value expression, or null when the reference has no default value branch. */
        private final String defaultValue;

        private Reference(final String name, final String defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue;
        }
    }

    /**
     * Analyzes the given template using the default variable syntax ({@code ${...}}, escape {@code '$'}, default
     * value delimiter {@code :-}).
     *
     * @param template the template to analyze, not null.
     * @param values the read-only map of variable values, not null. The map is only queried, never modified.
     * @return the dependency plan for the template.
     */
    public static SubstitutionPlan analyze(final CharSequence template, final Map<String, String> values) {
        return analyze(new StringSubstitutor(), template, values);
    }

    /**
     * Analyzes the given template using the variable prefix, suffix, escape character, default value delimiter, and
     * substitution-in-variables flag configured on the given {@link StringSubstitutor}.
     * <p>
     * Only the syntax configuration of the substitutor is used; variable values come exclusively from the given
     * read-only map, which is never modified. No substituted text is produced.
     * </p>
     *
     * @param substitutor supplies the matching rules (prefix, suffix, escape, value delimiter), not null.
     * @param template the template to analyze, not null.
     * @param values the read-only map of variable values, not null.
     * @return the dependency plan for the template.
     */
    public static SubstitutionPlan analyze(final StringSubstitutor substitutor, final CharSequence template,
        final Map<String, String> values) {
        Objects.requireNonNull(substitutor, "substitutor");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(values, "values");
        final StringMatcher prefixMatcher = substitutor.getVariablePrefixMatcher();
        final StringMatcher suffixMatcher = substitutor.getVariableSuffixMatcher();
        final char escapeChar = substitutor.getEscapeChar();
        final StringMatcher valueDelimiterMatcher = substitutor.getValueDelimiterMatcher();
        final boolean substitutionInVariables = substitutor.isEnableSubstitutionInVariables();
        final Builder builder = new Builder(values, prefixMatcher, suffixMatcher, escapeChar, valueDelimiterMatcher,
            substitutionInVariables);
        for (final Reference reference : scan(template, prefixMatcher, suffixMatcher, escapeChar,
            valueDelimiterMatcher, substitutionInVariables)) {
            builder.visit(reference.name, reference.defaultValue);
        }
        return builder.build();
    }

    /**
     * Scans the given text for variable references without modifying or expanding anything. Escaped references are
     * skipped, nested prefixes are balanced when substitution in variables is enabled, and each reference is split
     * into a name and an optional default value expression using the value delimiter.
     *
     * @param text the text to scan.
     * @param prefixMatcher the variable prefix matcher.
     * @param suffixMatcher the variable suffix matcher.
     * @param escapeChar the escape character, 0 disables escaping.
     * @param valueDelimiterMatcher the default value delimiter matcher, may be null.
     * @param substitutionInVariables whether nested variables in names are enabled.
     * @return the references found, in order of appearance.
     */
    private static List<Reference> scan(final CharSequence text, final StringMatcher prefixMatcher,
        final StringMatcher suffixMatcher, final char escapeChar, final StringMatcher valueDelimiterMatcher,
        final boolean substitutionInVariables) {
        final List<Reference> references = new ArrayList<>();
        final int length = text.length();
        int pos = 0;
        while (pos < length) {
            final int prefixLen = prefixMatcher.isMatch(text, pos, 0, length);
            if (prefixLen == 0) {
                pos++;
                continue;
            }
            if (escapeChar != 0 && pos > 0 && text.charAt(pos - 1) == escapeChar) {
                // Escaped reference: literal text, not a variable.
                pos += prefixLen;
                continue;
            }
            // Find the matching suffix, balancing nested prefixes.
            int cursor = pos + prefixLen;
            int nested = 0;
            int suffixPos = -1;
            int suffixLen = 0;
            while (cursor < length) {
                if (substitutionInVariables) {
                    final int nestedLen = prefixMatcher.isMatch(text, cursor, 0, length);
                    if (nestedLen != 0) {
                        nested++;
                        cursor += nestedLen;
                        continue;
                    }
                }
                final int matchLen = suffixMatcher.isMatch(text, cursor, 0, length);
                if (matchLen == 0) {
                    cursor++;
                    continue;
                }
                if (nested == 0) {
                    suffixPos = cursor;
                    suffixLen = matchLen;
                    break;
                }
                nested--;
                cursor += matchLen;
            }
            if (suffixPos < 0) {
                // Unterminated variable: the rest of the text is literal.
                break;
            }
            final String expression = text.subSequence(pos + prefixLen, suffixPos).toString();
            references.addAll(toReferences(expression, prefixMatcher, suffixMatcher, valueDelimiterMatcher,
                substitutionInVariables));
            pos = suffixPos + suffixLen;
        }
        return references;
    }

    /**
     * Splits a variable name expression into references, applying the default value delimiter rule used by
     * {@link StringSubstitutor}: the first delimiter match separates the variable name from its default value, and
     * when substitution in variables is disabled, a nested prefix inside the expression disables default value
     * resolution for that reference.
     * <p>
     * When substitution in variables is enabled and the name part itself contains nested variables, those nested
     * references are emitted (they are resolved first to compute the name) and the outer reference is dropped,
     * because its final name cannot be known without expanding the text.
     * </p>
     */
    private static List<Reference> toReferences(final String expression, final StringMatcher prefixMatcher,
        final StringMatcher suffixMatcher, final StringMatcher valueDelimiterMatcher,
        final boolean substitutionInVariables) {
        final List<Reference> references = new ArrayList<>();
        String name = expression;
        String defaultValue = null;
        if (valueDelimiterMatcher != null) {
            final char[] chars = expression.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (!substitutionInVariables && prefixMatcher.isMatch(chars, i, i, chars.length) != 0) {
                    // Nested variable with substitution in variables disabled: no default value resolution.
                    name = expression;
                    defaultValue = null;
                    break;
                }
                final int delimiterLen = valueDelimiterMatcher.isMatch(chars, i, 0, chars.length);
                if (delimiterLen != 0) {
                    name = expression.substring(0, i);
                    defaultValue = expression.substring(i + delimiterLen);
                    break;
                }
            }
        }
        if (substitutionInVariables) {
            // Variables nested in the name are resolved first, so they are dependencies at the same level.
            final List<Reference> nested = scan(name, prefixMatcher, suffixMatcher, (char) 0, valueDelimiterMatcher,
                true);
            if (!nested.isEmpty()) {
                // The name is computed at substitution time and cannot be known statically.
                references.addAll(nested);
                return references;
            }
        }
        references.add(new Reference(name, defaultValue));
        return references;
    }

    /**
     * Mutable accumulator used while computing a plan.
     */
    private static final class Builder {

        /** The read-only variable values. */
        private final Map<String, String> values;

        /** The variable prefix matcher. */
        private final StringMatcher prefixMatcher;

        /** The variable suffix matcher. */
        private final StringMatcher suffixMatcher;

        /** The escape character. */
        private final char escapeChar;

        /** The default value delimiter matcher, may be null. */
        private final StringMatcher valueDelimiterMatcher;

        /** Whether substitution in variable names is enabled. */
        private final boolean substitutionInVariables;

        /** Variables fully processed. */
        private final Set<String> done = new LinkedHashSet<>();

        /** Variables currently on the visit stack, used for cycle detection. */
        private final Deque<String> visiting = new ArrayDeque<>();

        /** Dependency edges, de-duplicated, in discovery order. */
        private final Set<Edge> edges = new LinkedHashSet<>();

        /** Referenced variables absent from the value map, in discovery order. */
        private final Set<String> missing = new LinkedHashSet<>();

        /** Detected cycles, each a path whose first and last elements are equal. */
        private final List<List<String>> cycles = new ArrayList<>();

        /** Resolution order: a variable is appended once all its dependencies were visited. */
        private final List<String> order = new ArrayList<>();

        Builder(final Map<String, String> values, final StringMatcher prefixMatcher,
            final StringMatcher suffixMatcher, final char escapeChar, final StringMatcher valueDelimiterMatcher,
            final boolean substitutionInVariables) {
            this.values = values;
            this.prefixMatcher = prefixMatcher;
            this.suffixMatcher = suffixMatcher;
            this.escapeChar = escapeChar;
            this.valueDelimiterMatcher = valueDelimiterMatcher;
            this.substitutionInVariables = substitutionInVariables;
        }

        SubstitutionPlan build() {
            return new SubstitutionPlan(order, edges, missing, cycles, done);
        }

        /**
         * Visits a variable: records whether it is missing, follows the references in its map value (or, only when
         * missing, in its default value expression), and appends it to the resolution order after its dependencies.
         *
         * @param name the variable name.
         * @param defaultValue the default value expression from the referencing site, may be null.
         */
        void visit(final String name, final String defaultValue) {
            if (done.contains(name)) {
                return;
            }
            if (visiting.contains(name)) {
                // Circular reference: record the cycle instead of recursing forever.
                final List<String> cycle = new ArrayList<>();
                boolean onCycle = false;
                for (final String variable : visiting) {
                    if (variable.equals(name)) {
                        onCycle = true;
                    }
                    if (onCycle) {
                        cycle.add(variable);
                    }
                }
                cycle.add(name);
                cycles.add(cycle);
                return;
            }
            visiting.addLast(name);
            final String value = values.get(name);
            final String textToScan;
            if (value != null) {
                textToScan = value;
            } else {
                missing.add(name);
                // The default value branch only participates when the variable itself is missing.
                textToScan = defaultValue;
            }
            if (textToScan != null) {
                for (final Reference reference : scan(textToScan, prefixMatcher, suffixMatcher, escapeChar,
                    valueDelimiterMatcher, substitutionInVariables)) {
                    edges.add(new Edge(name, reference.name));
                    visit(reference.name, reference.defaultValue);
                }
            }
            visiting.removeLast();
            done.add(name);
            order.add(name);
        }
    }

    /** Variables that need to be read, in dependency-first order. */
    private final List<String> resolutionOrder;

    /** Dependency edges between variables. */
    private final List<Edge> edges;

    /** Referenced variables absent from the value map. */
    private final Set<String> missingVariables;

    /** Detected circular references. */
    private final List<List<String>> cycles;

    /** All variables that need to be read. */
    private final Set<String> referencedVariables;

    private SubstitutionPlan(final List<String> resolutionOrder, final Set<Edge> edges, final Set<String> missing,
        final List<List<String>> cycles, final Set<String> referenced) {
        this.resolutionOrder = Collections.unmodifiableList(new ArrayList<>(resolutionOrder));
        this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
        this.missingVariables = Collections.unmodifiableSet(new LinkedHashSet<>(missing));
        final List<List<String>> cyclesCopy = new ArrayList<>(cycles.size());
        for (final List<String> cycle : cycles) {
            cyclesCopy.add(Collections.unmodifiableList(cycle));
        }
        this.cycles = Collections.unmodifiableList(cyclesCopy);
        this.referencedVariables = Collections.unmodifiableSet(new LinkedHashSet<>(referenced));
    }

    /**
     * Gets the detected circular references. Each cycle is a list of variable names forming a loop, where the last
     * element repeats the first (for example {@code [a, b, a]}). Cycles that only occur in untaken default value
     * branches are not reported, because those branches are never followed.
     *
     * @return the cycles found, empty when there are none.
     */
    public List<List<String>> getCycles() {
        return cycles;
    }

    /**
     * Gets the dependency edges between variables. An edge {@code a -> b} means resolving {@code a} requires reading
     * {@code b}.
     *
     * @return the edges in discovery order.
     */
    public List<Edge> getEdges() {
        return edges;
    }

    /**
     * Gets the variables that are referenced but have no entry in the value map. Such variables either rely on their
     * default value branch or would be left unresolved by a real substitution.
     *
     * @return the missing variable names, in discovery order.
     */
    public Set<String> getMissingVariables() {
        return missingVariables;
    }

    /**
     * Gets the variables that truly need to be read to resolve the template: the variables referenced by the
     * template plus the transitive dependencies reachable through map values and taken default value branches.
     *
     * @return the referenced variable names.
     */
    public Set<String> getReferencedVariables() {
        return referencedVariables;
    }

    /**
     * Gets the referenced variables in dependency-first order: every variable appears after the variables it depends
     * on. For example, when {@code a} resolves to {@code "${b}"} and {@code b} to {@code "${c}"}, the order is
     * {@code [c, b, a]}. When {@link #hasCycle()} is {@code true}, variables on a cycle cannot be ordered
     * topologically and appear in visit-completion order instead.
     *
     * @return the resolution order.
     */
    public List<String> getResolutionOrder() {
        return resolutionOrder;
    }

    /**
     * Tests whether any circular reference was detected.
     *
     * @return true when at least one cycle exists.
     */
    public boolean hasCycle() {
        return !cycles.isEmpty();
    }

    @Override
    public String toString() {
        return "SubstitutionPlan[resolutionOrder=" + resolutionOrder + ", edges=" + edges + ", missingVariables="
            + missingVariables + ", cycles=" + cycles + "]";
    }
}
