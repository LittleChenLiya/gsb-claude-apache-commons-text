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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of a traced substitution performed by {@link StringSubstitutor#replaceWithTrace(Object)}.
 * <p>
 * A {@code SubstitutionTrace} pairs the substituted result string with its provenance: every range of the output is
 * mapped either to a range of the original template (literal text) or to a {@link Resolution} step describing the
 * variable resolution that produced it. Resolutions form a parent chain: a variable reference found inside another
 * variable's name or (recursively substituted) value links to the enclosing resolution, so any output character can
 * be traced back to the variable reference in the original template that ultimately produced it.
 * </p>
 * <p>
 * All offsets are expressed in UTF-16 code units, matching the indexing of {@link String} and {@code char} based
 * APIs. A supplementary Unicode character (a surrogate pair) therefore counts as two offset units.
 * </p>
 *
 * @since 1.15.0
 */
public final class SubstitutionTrace {

    /**
     * A single variable resolution step recorded while substituting.
     * <p>
     * A resolution is created each time a variable reference is replaced by a value, whether the value came from the
     * configured {@link org.apache.commons.text.lookup.StringLookup} or from the variable's default value. If the
     * variable reference appeared inside the name or value of another variable being resolved, {@link #getParent()}
     * links to that enclosing resolution; walking the parents leads to the top-level resolution whose
     * {@link #getTemplateStart()} and {@link #getTemplateEnd()} locate the originating reference in the template.
     * </p>
     */
    public static final class Resolution {

        /** The resolved variable name (after substitution in variable names). */
        private final String variableName;

        /** The value that replaced the variable reference. */
        private final String value;

        /** Whether the value came from the variable default value instead of the lookup. */
        private final boolean defaultValueUsed;

        /** The enclosing resolution, or null for a reference appearing directly in the template. */
        private Resolution parent;

        /** Start offset of the variable reference in the template, or -1 if not directly in the template. */
        private final int templateStart;

        /** End offset (exclusive) of the variable reference in the template, or -1 if not directly in the template. */
        private final int templateEnd;

        Resolution(final String variableName, final String value, final boolean defaultValueUsed, final Resolution parent,
            final int templateStart, final int templateEnd) {
            this.variableName = variableName;
            this.value = value;
            this.defaultValueUsed = defaultValueUsed;
            this.parent = parent;
            this.templateStart = templateStart;
            this.templateEnd = templateEnd;
        }

        /**
         * Gets the nesting depth of this resolution. A resolution whose reference appears directly in the template
         * has depth 0; a resolution produced while substituting in its name or value has depth 1, and so on.
         *
         * @return the depth of this resolution in the parent chain.
         */
        public int getDepth() {
            int depth = 0;
            Resolution cursor = parent;
            while (cursor != null) {
                depth++;
                cursor = cursor.parent;
            }
            return depth;
        }

        /**
         * Gets the enclosing resolution, that is the resolution of the variable whose name or (recursively
         * substituted) value contained this variable's reference.
         *
         * @return the parent resolution, or null if the reference appeared directly in the template.
         */
        public Resolution getParent() {
            return parent;
        }

        /**
         * Gets the root of the parent chain, that is the top-level resolution whose variable reference appears
         * directly in the template.
         *
         * @return the root resolution, never null (this resolution itself when it has no parent).
         */
        public Resolution getRoot() {
            Resolution cursor = this;
            while (cursor.parent != null) {
                cursor = cursor.parent;
            }
            return cursor;
        }

        /**
         * Gets the end offset (exclusive) of the variable reference in the original template.
         *
         * @return the template end offset, or -1 if the reference did not appear directly in the template (for
         *         example a reference inside a variable value); walk {@link #getParent()} to locate the origin.
         */
        public int getTemplateEnd() {
            return templateEnd;
        }

        /**
         * Gets the start offset of the variable reference in the original template.
         *
         * @return the template start offset, or -1 if the reference did not appear directly in the template (for
         *         example a reference inside a variable value); walk {@link #getParent()} to locate the origin.
         */
        public int getTemplateStart() {
            return templateStart;
        }

        /**
         * Gets the value that replaced the variable reference.
         *
         * @return the resolved value.
         */
        public String getValue() {
            return value;
        }

        /**
         * Gets the resolved variable name. When substitution in variable names is enabled this is the name after
         * nested variables have been substituted.
         *
         * @return the variable name.
         */
        public String getVariableName() {
            return variableName;
        }

        /**
         * Tests whether the value came from the variable default value (for example {@code ${user:-guest}}) because
         * the lookup did not resolve the variable.
         *
         * @return true if the default value was used.
         */
        public boolean isDefaultValueUsed() {
            return defaultValueUsed;
        }

        void setParent(final Resolution parent) {
            this.parent = parent;
        }

        @Override
        public String toString() {
            return "Resolution [variableName=" + variableName + ", value=" + value + ", defaultValueUsed=" + defaultValueUsed
                + ", templateStart=" + templateStart + ", templateEnd=" + templateEnd + "]";
        }
    }

    /**
     * A contiguous range of the output string mapped to its source.
     * <p>
     * The spans of a trace are ordered, contiguous, and together cover the whole result string. A span is either
     * <em>literal</em>, meaning its text was copied from the original template, in which case
     * {@link #getTemplateStart()} and {@link #getTemplateEnd()} locate it in the template; or it was produced by a
     * variable resolution, in which case {@link #getResolution()} identifies the resolution step and
     * {@link #getValueStart()} and {@link #getValueEnd()} locate the span within the resolved value.
     * </p>
     */
    public static final class Span {

        /** Start offset of the span in the result string. */
        private final int start;

        /** End offset (exclusive) of the span in the result string. */
        private final int end;

        /** Start offset in the template for literal spans, -1 otherwise. */
        private final int templateStart;

        /** End offset (exclusive) in the template for literal spans, -1 otherwise. */
        private final int templateEnd;

        /** The resolution that produced this span, null for literal spans. */
        private final Resolution resolution;

        /** Start offset within the resolution's value, -1 for literal spans. */
        private final int valueStart;

        /** End offset (exclusive) within the resolution's value, -1 for literal spans. */
        private final int valueEnd;

        Span(final int start, final int end, final int templateStart, final int templateEnd, final Resolution resolution,
            final int valueStart, final int valueEnd) {
            this.start = start;
            this.end = end;
            this.templateStart = templateStart;
            this.templateEnd = templateEnd;
            this.resolution = resolution;
            this.valueStart = valueStart;
            this.valueEnd = valueEnd;
        }

        /**
         * Gets the end offset (exclusive) of this span in the result string.
         *
         * @return the end offset in the result.
         */
        public int getEnd() {
            return end;
        }

        /**
         * Gets the resolution step that produced this span.
         *
         * @return the resolution, or null if this span is literal template text.
         */
        public Resolution getResolution() {
            return resolution;
        }

        /**
         * Gets the start offset of this span in the result string.
         *
         * @return the start offset in the result.
         */
        public int getStart() {
            return start;
        }

        /**
         * Gets the end offset (exclusive) of this span's text in the original template.
         *
         * @return the template end offset, or -1 if this span was produced by a variable resolution.
         */
        public int getTemplateEnd() {
            return templateEnd;
        }

        /**
         * Gets the start offset of this span's text in the original template.
         *
         * @return the template start offset, or -1 if this span was produced by a variable resolution.
         */
        public int getTemplateStart() {
            return templateStart;
        }

        /**
         * Gets the end offset (exclusive) of this span within the resolved value of {@link #getResolution()}.
         *
         * @return the end offset within the resolution's value, or -1 if this span is literal template text.
         */
        public int getValueEnd() {
            return valueEnd;
        }

        /**
         * Gets the start offset of this span within the resolved value of {@link #getResolution()}.
         *
         * @return the start offset within the resolution's value, or -1 if this span is literal template text.
         */
        public int getValueStart() {
            return valueStart;
        }

        /**
         * Tests whether this span is literal text copied from the template (as opposed to text produced by a variable
         * resolution).
         *
         * @return true if this span is literal template text.
         */
        public boolean isLiteral() {
            return resolution == null;
        }

        @Override
        public String toString() {
            return isLiteral()
                ? "Span [start=" + start + ", end=" + end + ", templateStart=" + templateStart + ", templateEnd=" + templateEnd + "]"
                : "Span [start=" + start + ", end=" + end + ", resolution=" + resolution.getVariableName() + ", valueStart=" + valueStart
                    + ", valueEnd=" + valueEnd + "]";
        }
    }

    /** The substituted result string. */
    private final String result;

    /** The ordered, contiguous spans covering the result string. */
    private final List<Span> spans;

    /** The resolution steps in the order they were performed. */
    private final List<Resolution> resolutions;

    SubstitutionTrace(final String result, final List<Span> spans, final List<Resolution> resolutions) {
        this.result = result;
        this.spans = Collections.unmodifiableList(new ArrayList<>(spans));
        this.resolutions = Collections.unmodifiableList(new ArrayList<>(resolutions));
    }

    /**
     * Gets the resolution steps performed during the substitution, in the order in which they were performed.
     *
     * @return the unmodifiable list of resolution steps.
     */
    public List<Resolution> getResolutions() {
        return resolutions;
    }

    /**
     * Gets the substituted result string, identical to what {@link StringSubstitutor#replace(Object)} returns for the
     * same input.
     *
     * @return the result string.
     */
    public String getResult() {
        return result;
    }

    /**
     * Gets the spans mapping ranges of the result string to their sources. The spans are ordered, contiguous, and
     * cover the whole result string.
     *
     * @return the unmodifiable list of spans.
     */
    public List<Span> getSpans() {
        return spans;
    }

    /**
     * Gets the span containing the given offset in the result string.
     *
     * @param offset an offset in the result string, in the range {@code 0 <= offset < result.length()}.
     * @return the span containing the offset.
     * @throws IndexOutOfBoundsException if the offset is out of range.
     */
    public Span spanAt(final int offset) {
        if (offset < 0 || offset >= result.length()) {
            throw new IndexOutOfBoundsException("Offset " + offset + " out of range for result of length " + result.length());
        }
        for (final Span span : spans) {
            if (offset >= span.getStart() && offset < span.getEnd()) {
                return span;
            }
        }
        throw new IllegalStateException("Spans do not cover offset " + offset);
    }

    @Override
    public String toString() {
        return "SubstitutionTrace [result=" + result + ", spans=" + spans + ", resolutions=" + resolutions + "]";
    }
}
