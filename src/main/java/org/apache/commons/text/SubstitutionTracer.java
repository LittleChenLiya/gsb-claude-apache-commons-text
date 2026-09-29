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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.text.SubstitutionTrace.Resolution;
import org.apache.commons.text.SubstitutionTrace.Span;

/**
 * Tracks the provenance of a single {@link StringSubstitutor#replaceWithTrace(Object)} run.
 * <p>
 * The tracer mirrors every mutation the substitution algorithm applies to its working buffer on a parallel
 * {@link Provenance} structure, so that after the substitution each range of the result can be mapped back to a range
 * of the original template or to the variable resolution step that produced it. It only observes the substitution; it
 * never causes a variable to be resolved more than once.
 * </p>
 */
final class SubstitutionTracer {

    /**
     * Per-buffer tracking state. A new buffer state is created for the main substitution buffer and for each
     * temporary buffer used to substitute in variable names.
     */
    private static final class BufferInfo {

        /** The buffer from which this buffer's initial content was taken, null for the main buffer. */
        private final TextStringBuilder parent;

        /** The provenance of this buffer's current content. */
        private final Provenance provenance;

        /** Resolutions performed in this buffer that are not enclosed by another resolution. */
        private final List<Resolution> roots = new ArrayList<>();

        /** The most recent nested (variable name) buffer derived from this buffer. */
        private TextStringBuilder lastChild;

        BufferInfo(final TextStringBuilder parent, final Provenance provenance) {
            this.parent = parent;
            this.provenance = provenance;
        }
    }

    /**
     * A provenance segment: a run of characters with a single source. A null {@code resolution} denotes literal
     * template text and {@code start} is the offset of the segment start in the template. A non-null
     * {@code resolution} denotes text produced by that resolution and {@code start} is the offset of the segment
     * start within the resolution's value.
     */
    private static final class Seg {

        /** The length of this segment in UTF-16 code units. */
        private int length;

        /** The template offset or value offset of this segment's start. */
        private final int start;

        /** The resolution that produced this segment, or null for literal template text. */
        private final Resolution resolution;

        Seg(final int length, final int start, final Resolution resolution) {
            this.length = length;
            this.start = start;
            this.resolution = resolution;
        }
    }

    /**
     * The provenance of a buffer's content as an ordered list of segments covering offsets {@code [0, length)}.
     */
    private static final class Provenance {

        /** The ordered segments. */
        private final List<Seg> segs = new ArrayList<>();

        static Provenance template(final int length) {
            final Provenance provenance = new Provenance();
            if (length > 0) {
                provenance.segs.add(new Seg(length, 0, null));
            }
            return provenance;
        }

        /**
         * Finds the index of the segment containing {@code pos}, or {@code segs.size()} if {@code pos} is at the end.
         */
        private int locate(final int pos) {
            int offset = 0;
            for (int i = 0; i < segs.size(); i++) {
                offset += segs.get(i).length;
                if (pos < offset) {
                    return i;
                }
            }
            return segs.size();
        }

        /**
         * Ensures a segment boundary exists at {@code pos} and returns the index of the segment starting there.
         */
        private int split(final int pos) {
            int offset = 0;
            for (int i = 0; i < segs.size(); i++) {
                final Seg seg = segs.get(i);
                if (pos == offset) {
                    return i;
                }
                if (pos < offset + seg.length) {
                    final Seg tail = new Seg(offset + seg.length - pos, seg.start + (pos - offset), seg.resolution);
                    seg.length = pos - offset;
                    segs.add(i + 1, tail);
                    return i + 1;
                }
                offset += seg.length;
            }
            return segs.size();
        }

        /** Merges adjacent segments that share a contiguous source. */
        private void mergeFrom(final int index) {
            for (int i = Math.max(0, index); i + 1 < segs.size(); i++) {
                final Seg current = segs.get(i);
                final Seg next = segs.get(i + 1);
                if (current.resolution == next.resolution && current.start + current.length == next.start) {
                    current.length += next.length;
                    segs.remove(i + 1);
                    i--;
                }
            }
        }

        /** Deletes {@code length} characters starting at {@code pos}. */
        void delete(final int pos, final int length) {
            final int from = split(pos);
            final int to = split(pos + length);
            for (int i = to - 1; i >= from; i--) {
                segs.remove(i);
            }
            mergeFrom(from - 1);
        }

        /** Replaces the range {@code [start, end)} with {@code newLength} characters produced by {@code resolution}. */
        void replaceWithValue(final int start, final int end, final int newLength, final Resolution resolution) {
            final int from = split(start);
            final int to = split(end);
            for (int i = to - 1; i >= from; i--) {
                segs.remove(i);
            }
            if (newLength > 0) {
                segs.add(from, new Seg(newLength, 0, resolution));
            }
            mergeFrom(from - 1);
        }

        /** Copies the provenance of the range {@code [start, end)} into a new instance. */
        Provenance slice(final int start, final int end) {
            final int from = split(start);
            final int to = split(end);
            final Provenance sliced = new Provenance();
            for (int i = from; i < to; i++) {
                final Seg seg = segs.get(i);
                sliced.segs.add(new Seg(seg.length, seg.start, seg.resolution));
            }
            mergeFrom(from - 1);
            return sliced;
        }

        /** Returns the resolution whose value contains {@code pos}, or null if {@code pos} is template text. */
        Resolution resolutionAt(final int pos) {
            final int index = locate(pos);
            return index < segs.size() ? segs.get(index).resolution : null;
        }

        /**
         * Maps the range {@code [start, end)} to the template, or returns null if the range is not contiguous
         * literal template text.
         */
        int[] templateRange(final int start, final int end) {
            int offset = 0;
            int templateStart = -1;
            for (final Seg seg : segs) {
                final int segEnd = offset + seg.length;
                if (segEnd > start && offset < end) {
                    if (seg.resolution != null) {
                        return null;
                    }
                    if (templateStart < 0) {
                        templateStart = seg.start + (start - offset);
                    } else if (seg.start + Math.max(0, start - offset) != templateStart + (Math.max(offset, start) - start)) {
                        return null;
                    }
                }
                offset = segEnd;
            }
            return templateStart < 0 ? null : new int[] { templateStart, templateStart + (end - start) };
        }
    }

    /** Tracking state per buffer, keyed by identity. */
    private final Map<TextStringBuilder, BufferInfo> buffers = new IdentityHashMap<>();

    /** All resolutions performed so far, in order. */
    private final List<Resolution> resolutions = new ArrayList<>();

    /** The main substitution buffer. */
    private TextStringBuilder mainBuffer;

    /**
     * Starts tracking the main substitution buffer, whose initial content is the template itself.
     *
     * @param builder the main buffer.
     */
    void beginTemplate(final TextStringBuilder builder) {
        mainBuffer = builder;
        buffers.put(builder, new BufferInfo(null, Provenance.template(builder.length())));
    }

    /**
     * Starts tracking a temporary buffer used to substitute in a variable name. The buffer's initial content is the
     * range {@code [start, end)} of {@code outer}.
     *
     * @param outer the buffer containing the variable name expression.
     * @param inner the temporary buffer holding a copy of the variable name expression.
     * @param start the start offset of the name expression in {@code outer}.
     * @param end the end offset (exclusive) of the name expression in {@code outer}.
     */
    void beginNestedBuffer(final TextStringBuilder outer, final TextStringBuilder inner, final int start, final int end) {
        final BufferInfo outerInfo = buffers.get(outer);
        if (outerInfo == null) {
            return;
        }
        buffers.put(inner, new BufferInfo(outer, outerInfo.provenance.slice(start, end)));
        outerInfo.lastChild = inner;
    }

    /**
     * Builds the trace for the completed substitution.
     *
     * @param result the substituted result string.
     * @return the trace.
     */
    SubstitutionTrace build(final String result) {
        final BufferInfo info = buffers.get(mainBuffer);
        final List<Span> spans = new ArrayList<>();
        int pos = 0;
        for (final Seg seg : info.provenance.segs) {
            if (seg.length > 0) {
                if (seg.resolution == null) {
                    spans.add(new Span(pos, pos + seg.length, seg.start, seg.start + seg.length, null, -1, -1));
                } else {
                    spans.add(new Span(pos, pos + seg.length, -1, -1, seg.resolution, seg.start, seg.start + seg.length));
                }
                pos += seg.length;
            }
        }
        return new SubstitutionTrace(result, spans, resolutions);
    }

    /**
     * Records the deletion of an escape character.
     *
     * @param builder the buffer being mutated.
     * @param pos the offset of the deleted character.
     */
    void deleteCharAt(final TextStringBuilder builder, final int pos) {
        final BufferInfo info = buffers.get(builder);
        if (info != null) {
            info.provenance.delete(pos, 1);
        }
    }

    /**
     * Records the resolution of a variable, immediately before its reference at {@code [startPos, endPos)} is
     * replaced by {@code varValue}.
     *
     * @param builder the buffer being mutated.
     * @param startPos the start offset of the variable reference including the prefix.
     * @param endPos the end offset (exclusive) of the variable reference including the suffix.
     * @param varName the resolved variable name.
     * @param varValue the value replacing the reference.
     * @param defaultValueUsed whether the value is the variable default value.
     */
    void beforeReplace(final TextStringBuilder builder, final int startPos, final int endPos, final String varName,
        final String varValue, final boolean defaultValueUsed) {
        final BufferInfo info = buffers.get(builder);
        if (info == null) {
            return;
        }
        final Resolution parent = info.provenance.resolutionAt(startPos);
        final int[] templateRange = info.provenance.templateRange(startPos, endPos);
        final Resolution resolution = new Resolution(varName, varValue, defaultValueUsed, parent,
            templateRange != null ? templateRange[0] : -1, templateRange != null ? templateRange[1] : -1);
        resolutions.add(resolution);
        // Adopt the root resolutions of the buffer used to substitute in this variable's name: they were resolved
        // while computing this variable's name, so this resolution is their enclosing step.
        final TextStringBuilder child = info.lastChild;
        if (child != null) {
            info.lastChild = null;
            final BufferInfo childInfo = buffers.get(child);
            if (childInfo != null) {
                childInfo.roots.forEach(root -> root.setParent(resolution));
                childInfo.roots.clear();
            }
        }
        if (parent == null && builder != mainBuffer) {
            info.roots.add(resolution);
        }
        info.provenance.replaceWithValue(startPos, endPos, varValue.length(), resolution);
    }
}
