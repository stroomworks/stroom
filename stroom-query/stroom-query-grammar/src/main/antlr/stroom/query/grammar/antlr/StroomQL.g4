/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Placeholder grammar for Phase 0 scaffolding (see docs/query-optimiser-implementation-plan.md, Task 0.1).
// Task 1.1 replaces this with the full StroomQL lexer/parser grammar.
grammar StroomQL;

init : EOF ;

WS : [ \t\r\n]+ -> skip ;
