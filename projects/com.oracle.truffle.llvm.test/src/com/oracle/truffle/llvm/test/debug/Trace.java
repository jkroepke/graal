/*
 * Copyright (c) 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.test.debug;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

final class Trace implements Iterable<StopRequest> {

    static Trace parse(Path path) {
        final Trace trace = new Trace();
        try {
            final Parser parser = trace.newParser();
            Files.lines(path).filter(s -> !s.isEmpty()).forEachOrdered(parser);
        } catch (Throwable t) {
            throw new AssertionError("Could not read trace file: " + path, t);
        }
        return trace;
    }

    private final List<StopRequest> stops;
    private boolean suspendOnEntry;

    private Trace() {
        this.stops = new ArrayList<>();
        this.suspendOnEntry = false;
    }

    public boolean suspendOnEntry() {
        return suspendOnEntry;
    }

    IntStream requestedBreakpoints() {
        return stops.stream().filter(StopRequest::needsBreakPoint).mapToInt(StopRequest::getLine).distinct();
    }

    @Override
    public Iterator<StopRequest> iterator() {
        return stops.iterator();
    }

    private Parser newParser() {
        return new Parser();
    }

    private final class Parser implements Consumer<String> {

        private final LinkedList<String> buffer;
        private final LinkedList<LLVMDebugValue.Structured> parents;

        private StopRequest request;
        private StopRequest.Scope scope;
        private LLVMDebugValue.Structured structured;

        private Parser() {
            this.buffer = new LinkedList<>();
            this.parents = new LinkedList<>();
            this.request = null;
            this.scope = null;
            this.structured = null;
        }

        @Override
        public void accept(String line) {
            split(line);
            final String token = nextToken();
            switch (token) {
                case "SUSPEND":
                    if (request != null) {
                        error();
                    }
                    suspendOnEntry = true;
                    break;

                case "STOP":
                    parseStop(false);
                    break;

                case "BREAK":
                    parseStop(true);
                    break;

                case "OPEN_SCOPE":
                    final String scopeName = buffer.pollFirst(); // may be null
                    if (structured != null || !parents.isEmpty() || request == null) {
                        error();
                    }
                    scope = new StopRequest.Scope(scopeName);
                    request.addScope(scope);
                    break;

                case "MEMBER":
                    parseMember();
                    break;

                case "END_MEMBERS":
                    if (structured == null) {
                        error();
                    } else if (parents.isEmpty()) {
                        structured = null;
                    } else {
                        structured = parents.pollLast();
                    }
                    break;

                default:
                    error();
                    break;
            }

            if (!buffer.isEmpty()) {
                error();
            }
        }

        private void parseStop(boolean needsBreakPoint) {
            if (structured != null || !parents.isEmpty()) {
                error();
            }

            final String lineStr = nextToken();
            int line = -1;
            try {
                line = Integer.parseInt(lineStr);
            } catch (NumberFormatException nfe) {
                error(nfe);
            }
            if (request != null && line == request.getLine()) {
                // we cannot tell how many instructions belong to a single source-level line across
                // optimization levels, so this is illegal
                throw new AssertionError(String.format("Invalid trace: Subsequent breaks on line: %d", line));
            }

            final String nextActionStr = nextToken();
            final ContinueStrategy strategy;
            switch (nextActionStr) {
                case "STEP_INTO":
                    strategy = ContinueStrategy.STEP_INTO;
                    break;
                case "STEP_OUT":
                    strategy = ContinueStrategy.STEP_OUT;
                    break;
                case "STEP_OVER":
                    strategy = ContinueStrategy.STEP_OVER;
                    break;
                case "KILL":
                    strategy = ContinueStrategy.KILL;
                    break;
                case "CONTINUE":
                    strategy = ContinueStrategy.CONTINUE;
                    break;
                case "UNWIND":
                    strategy = ContinueStrategy.UNWIND;
                    break;
                case "NONE":
                    strategy = ContinueStrategy.NONE;
                    break;
                default:
                    throw new AssertionError("Invalid trace: Unknown continuation strategy: " + nextActionStr);
            }

            final String functionName = nextToken();
            request = new StopRequest(strategy, functionName, line, needsBreakPoint);
            stops.add(request);
        }

        private boolean parseBugginess() {
            final String token = buffer.pollFirst();
            return "buggy".equals(token) || (structured != null && structured.isBuggy());
        }

        private void parseMember() {
            final String kind = nextToken();
            final String type = nextToken();
            final String name = nextToken();

            LLVMDebugValue dbgValue = null;
            switch (kind) {
                case "any": {
                    dbgValue = new LLVMDebugValue.Any(type);
                    break;
                }
                case "char": {
                    final String value = nextToken();
                    if (value.length() != 1) {
                        error();
                    }
                    final boolean isBuggy = parseBugginess();
                    dbgValue = new LLVMDebugValue.Char(type, value.charAt(0), isBuggy);
                    break;
                }
                case "int": {
                    final String value = nextToken();
                    try {
                        final BigInteger intVal = new BigInteger(value);
                        final boolean isBuggy = parseBugginess();
                        dbgValue = new LLVMDebugValue.Int(type, intVal, isBuggy);
                    } catch (NumberFormatException nfe) {
                        error(nfe);
                    }
                    break;
                }
                case "float32": {
                    final String value = nextToken();
                    try {
                        final float floatVal = Float.parseFloat(value);
                        final boolean isBuggy = parseBugginess();
                        dbgValue = new LLVMDebugValue.Float_32(type, floatVal, isBuggy);
                    } catch (NumberFormatException nfe) {
                        error(nfe);
                    }
                    break;
                }
                case "float64": {
                    final String value = nextToken();
                    try {
                        final double floatVal = Double.parseDouble(value);
                        final boolean isBuggy = parseBugginess();
                        dbgValue = new LLVMDebugValue.Float_64(type, floatVal, isBuggy);
                    } catch (NumberFormatException nfe) {
                        error(nfe);
                    }
                    break;
                }
                case "address": {
                    final String value = nextToken();
                    final boolean isBuggy = parseBugginess();
                    dbgValue = new LLVMDebugValue.Address(type, value, isBuggy);
                    break;
                }
                case "exact": {
                    final String value = nextToken();
                    final boolean isBuggy = parseBugginess();
                    dbgValue = new LLVMDebugValue.Exact(type, value, isBuggy);
                    break;
                }
                case "structured": {
                    final boolean isBuggy = parseBugginess();
                    final LLVMDebugValue.Structured newStructured = new LLVMDebugValue.Structured(type, isBuggy);
                    if (structured != null) {
                        parents.addLast(structured);
                        structured.addMember(name, newStructured);
                        structured = newStructured;
                    } else {
                        scope.addMember(name, newStructured);
                    }
                    structured = newStructured;
                    return;
                }
                default:
                    throw new AssertionError("Invalid trace: Unknown member kind: " + kind);
            }

            if (structured != null) {
                structured.addMember(name, dbgValue);
            } else {
                scope.addMember(name, dbgValue);
            }
        }

        private void split(String line) {
            final String str = line.trim();

            int from = 0;
            while (from < str.length()) {
                int to;
                char ch = str.charAt(from);
                if (ch == '\"') {
                    from += 1;
                    to = str.indexOf('\"', from);
                    if (to == -1) {
                        error();
                    }
                } else {
                    to = str.indexOf(' ', from + 1);
                    if (to == -1) {
                        to = str.length();
                    }
                }

                final String nextToken = str.substring(from, to);
                buffer.addLast(nextToken);
                from = to + 1;

                while (from < str.length() && str.charAt(from) == ' ') {
                    from++;
                }
            }
        }

        private String nextToken() {
            final String token = buffer.pollFirst();
            if (token != null) {
                return token;
            } else {
                throw new AssertionError("Invalid Trace!");
            }
        }

        private void error() {
            throw new AssertionError("Invalid Trace!");
        }

        private void error(Throwable cause) {
            throw new AssertionError("Invalid Trace!", cause);
        }
    }
}
