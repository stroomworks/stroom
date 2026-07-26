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

// Attribution: this is an independent, hand-written subset grammar modelled on the openCypher reference grammar
// (https://opencypher.org - (c) openCypher contributors, Apache License 2.0). It is NOT derived from Neo4j's own
// (GPL) grammar, and Stroom uses no Neo4j code. "Cypher" is a trademark of Neo4j; this implements a subset of the
// openCypher-defined language, not full Cypher. See NOTICE.md.
//
// Cypher grammar (see docs/temporal-cypher-graph-implementation-plan.md, Task 0.2 / PoC.1).
//
// This is a HAND-TRIMMED subset grammar modelled on the openCypher reference grammar's structure and covering
// exactly the v1 subset the P0.2 spike locked (see the implementation plan's Task 0.2 outcome): MATCH (single +
// fixed-length chains + bounded variable-length), WHERE, RETURN (incl. DISTINCT/AS), WITH, ORDER BY, SKIP, LIMIT,
// count/sum/avg/min/max aggregation, node labels + inline property maps, relationship types + direction, plus a
// Stroom-specific temporal extension (AS OF / AROUND +/- / BETWEEN). Everything NOT listed here (writes,
// CALL/procedures, UNION, UNWIND, OPTIONAL MATCH, FOREACH, subqueries, comprehensions, map projections,
// shortestPath, unbounded var-length) is simply absent from the grammar - omission IS the rejection mechanism: an
// unsupported construct is not a keyword/rule at all, so it fails to parse and the caller sees a SyntaxException,
// satisfying "out-of-subset = explicit parse error from day one" without needing bespoke reject rules.
//
// Deliberate v1 simplifications (narrower than full openCypher, not narrower than the locked subset):
// - A relationship pattern carries at most ONE type (`-[:TYPE]->`), not openCypher's `:T1|T2` alternation - the
//   PoC.3 compiler only ever resolves a single edge type per hop, and the graph's own adjacency stores are keyed
//   per edge type (design doc Section 5.1), so multi-type patterns would need to fan out into multiple physical
//   scans - a real feature, deliberately deferred rather than silently mis-parsed.
// - Bounded variable-length paths (`-[:T*min..max]->`) REQUIRE an explicit finite upper bound at the GRAMMAR level
//   (`varLength` has no alternative omitting `max`) - this is what makes unbounded `-[:T*]->` a parse-time error
//   from day one, rather than a semantic check bolted on later.
// - `AS OF` / `AROUND <value> +/- <value>` / `BETWEEN <value> AND <value>` are a Stroom-specific addition (not
//   standard Cypher), attached directly after a MATCH clause's pattern and before its WHERE, per the P0.2 spike's
//   "one temporal clause per query" decision. The instant/duration/bound values reuse the general `value` rule
//   (typically a function-call literal like `datetime('...')`/`duration('PT1H')`, matching the design doc's
//   worked example), not a bespoke date/duration literal syntax.
// - An optional leading `from "X"` clause is a Stroom-specific portability addition (not standard Cypher; see
//   docs/cypher-from-clause-implementation-plan.md, Workstream A): it names the target GraphDb in the query text
//   itself, purely as a datasource selector, so the identical Cypher text can run from any text-driven surface
//   (Query doc, /csv/search, MCP, embedded dashboard) instead of only where a caller has already set
//   SearchRequestSource.ownerDocRef. CypherToLogicalPlan is unaware of it entirely.
// - `RETURN GRAPH` (Stroom-specific; see docs/temporal-cypher-diff-operator.md §4.4 and
//   docs/graphdb-cytoscape-visualisation.html §3, Workstream D) is a second, element-row terminal form for RETURN,
//   alongside the scalar item-list form - see the `returnClause` rule's own comment for why it carries none of the
//   scalar form's modifiers. Valid both on a plain MATCH and combined with DIFF (an annotated-subgraph mode); the
//   grammar admits both combinations uniformly and CypherToLogicalPlan decides what each means.
// - Grammar accepts the FULL locked subset (multiple MATCH/WITH stages, chains, var-length) even though
//   CypherToLogicalPlan only lowers a SINGLE reading clause's pattern for now (fixed-length multi-hop chains and
//   bounded variable-length paths within that one MATCH are fully compiled, as of Tasks P3.2/P3.3) - a query with
//   more than one MATCH/WITH stage is rejected with a clear "not yet supported" error at compile time, not a
//   parse-time restriction - the same "parse now, compile progressively" discipline StroomQL.g4 uses for joins
//   (see that grammar's file header). [Updated 2026-07-19 during a code-quality review: this comment previously
//   said the compiler "only lowers the single-hop PoC shape", which stopped being true once P3.2/P3.3 landed
//   multi-hop and variable-length compilation - keep this note in sync if the multi-reading-clause restriction is
//   ever lifted too.]
grammar Cypher;

// ============================================================================
// Parser rules
// ============================================================================

// `fromClause` is a Stroom-specific portability extension (not standard Cypher; see
// docs/cypher-from-clause-implementation-plan.md): naming the target GraphDb in the query text itself lets the
// same Cypher text run from any text-driven surface, rather than depending on the caller having already set
// SearchRequestSource.ownerDocRef. It is purely a datasource selector - CypherToLogicalPlan needs no change - and
// is optional so a query submitted where ownerDocRef already names the graph (e.g. the GraphDb doc's Data tab)
// need not repeat it.
// A statement is one or more single queries combined with UNION / UNION ALL. AstCypherBuilder produces an
// AstCypherStatement (a single query is just a one-branch statement); CypherToLogicalPlan compiles each branch and
// the executor folds them left-to-right (UNION ALL concatenates, UNION also de-duplicates). All branches must have
// the same RETURN column names - enforced at compile time.
query
    : singleQuery (unionClause singleQuery)* EOF
    ;

singleQuery
    : fromClause? readingClause+ returnClause
    ;

unionClause
    : UNION ALL?
    ;

fromClause
    : FROM STRING
    ;

readingClause
    : matchClause
    | withClause
    ;

// `OPTIONAL MATCH` (left-outer): CypherToLogicalPlan accepts it only as a second reading clause whose pattern
// extends a variable already bound by a preceding mandatory MATCH, and lowers its single hop to an `optional`
// Expand. Every other multi-clause shape is still rejected at compile time.
matchClause
    : OPTIONAL? MATCH pattern temporalClause? whereClause?
    ;

// A `WITH ... WHERE ...` is Cypher's HAVING: the WHERE filters the (possibly aggregated) projected columns, so it
// comes after the items. CypherToLogicalPlan lowers a single `MATCH ... WITH ... [WHERE] RETURN ...` pipe.
withClause
    : WITH returnItem (COMMA returnItem)* whereClause? orderByClause? skipClause? limitClause?
    ;

// `RETURN GRAPH` (Stroom-specific; see docs/temporal-cypher-diff-operator.md §4.4 and
// docs/graphdb-cytoscape-visualisation.html §3) is the element-row terminal form: instead of a scalar item list it
// emits the de-duplicated union of every matched node/edge as one row per element. It carries none of RETURN's
// per-item modifiers (no DISTINCT/items/ORDER BY/SKIP - there is no per-item projection to apply them to), but it
// DOES accept an optional LIMIT to bound the result: on a whole-graph preview (unanchored MATCH (n) RETURN GRAPH)
// the LIMIT caps the number of nodes returned (plus the edges between them); on an anchored pattern it caps the
// nodes in the matched element union the same way (see GraphTraversalEngine's dump/cap paths).
returnClause
    : RETURN DISTINCT? returnItem (COMMA returnItem)* orderByClause? skipClause? limitClause?  # returnItemsClause
    | RETURN GRAPH limitClause?                                                                # returnGraphClause
    ;

// ----- graph patterns -----

pattern
    : nodePattern patternHop*
    ;

patternHop
    : edgePattern nodePattern
    ;

nodePattern
    : OPEN_PAREN variable=NAME? nodeLabels? propertyMap? CLOSE_PAREN
    ;

nodeLabels
    : (COLON label+=NAME)+
    ;

// Direction is carried by which arrowhead (if either) is present; `edgeDetail` (the `[...]` part) is optional in
// all three, matching bare `-->`/`<--`/`--` patterns.
edgePattern
    : LARROW edgeDetail? DASH     # edgeIn
    | DASH edgeDetail? RARROW     # edgeOut
    | DASH edgeDetail? DASH       # edgeBoth
    ;

// See file header: at most one edge TYPE (not openCypher's `:T1|T2`).
edgeDetail
    : OPEN_BRACKET variable=NAME? (COLON edgeType=NAME)? varLength? propertyMap? CLOSE_BRACKET
    ;

// `max` is mandatory - see file header (this is the parse-level enforcement of "bounded, not unbounded").
varLength
    : STAR min=NUMBER? DOTDOT max=NUMBER
    ;

propertyMap
    : OPEN_BRACE (propertyKeyValue (COMMA propertyKeyValue)*)? CLOSE_BRACE
    ;

propertyKeyValue
    : key=NAME COLON value
    ;

// ----- Stroom's temporal extension (not standard Cypher; see file header) -----

temporalClause
    : AS OF instant=value                                   # asOfClause
    | AROUND instant=value PLUSMINUS duration=value          # aroundClause
    | BETWEEN from=value AND to=value                        # betweenClause
    | DIFF FROM baseline=value TO comparison=value           # diffClause
    ;

// ----- WHERE: boolean expression over matched variables' properties -----

whereClause : WHERE expr ;

expr    : orExpr ;
orExpr  : andExpr (OR andExpr)* ;
andExpr : notExpr (AND notExpr)* ;
notExpr
    : NOT notExpr
    | primary
    ;
primary
    : OPEN_PAREN expr CLOSE_PAREN
    | existsPredicate
    | inPredicate
    | isNullPredicate
    | comparisonPredicate
    ;

// `EXISTS { (x)-[:TYPE]->(y) }` - a correlated existence subquery over an outer-bound node variable. v1 (see
// CypherToLogicalPlan): the inner pattern is a single fixed-length hop from a variable already bound by the outer
// MATCH; combine with `NOT` (via notExpr) for a non-existence test. Carried on CompiledCypherPlan as a graph-local
// predicate (like FieldComparison) - the shared ExpressionTerm IR cannot express a traversal.
existsPredicate
    : EXISTS OPEN_BRACE pattern CLOSE_BRACE
    ;

// `x IN [ ... ]` - list membership. The right side is a general `expression`; CypherToLogicalPlan requires it to be
// a literal list (`AstListValue`) and rejects anything else. `x IS [NOT] NULL` - a null/existence test on a property.
inPredicate
    : left=expression IN right=expression
    ;

isNullPredicate
    : operand=expression IS NOT? NULL_
    ;
comparisonPredicate
    : left=expression op=comparisonOp right=expression
    ;
// String predicates (STARTS WITH / CONTAINS / ENDS WITH / =~) are lowered by CypherToLogicalPlan to the shared
// ExpressionTerm Condition vocabulary (STARTS_WITH/CONTAINS/ENDS_WITH/MATCHES_REGEX), which the graph WHERE path
// already evaluates - see that class's toCondition. `STARTS WITH`/`ENDS WITH` are two-token phrases reusing the
// existing WITH keyword; each alternative starts with a distinct token so the builder can switch on the start token.
comparisonOp
    : EQ | NEQ | LT | LE | GT | GE
    | STARTS WITH | ENDS WITH | CONTAINS | REGEX
    ;

// ----- expressions: shared by RETURN/WITH items, ORDER BY items, and WHERE operands -----

// Arithmetic (`+ - * / ^`) is a precedence hierarchy over the base terms: `^` (right-assoc) binds tighter than
// `*`/`/`, which bind tighter than `+`/`-`; parentheses group. CypherToLogicalPlan renders these to Stroom's
// expression engine, which already evaluates infix arithmetic - so there is no runtime/engine change, only this
// grammar and the rendering. (No `%` modulo: Stroom has no such function.)
expression
    : addExpr
    ;

addExpr
    : mulExpr (op+=(PLUS | DASH) mulExpr)*
    ;

mulExpr
    : powExpr (op+=(STAR | SLASH | PERCENT) powExpr)*
    ;

powExpr
    : atom (CARET powExpr)?
    ;

atom
    : caseExpression
    | aggregateCall
    | diffAccessor
    | propertyAccess
    | variableRef=NAME
    | value
    | OPEN_PAREN expression CLOSE_PAREN
    ;

// CASE value expression, both openCypher forms:
//   simple   - `CASE input WHEN test THEN result ... [ELSE otherwise] END` (each test is compared to input)
//   searched - `CASE WHEN condition THEN result ... [ELSE otherwise] END`  (each condition is a boolean predicate)
// The two are disambiguated by whether a value expression follows CASE before the first WHEN (an `expression`
// cannot begin with WHEN, so ALL(*) picks the right alternative). CypherToLogicalPlan lowers the simple form to
// Stroom's `case(input, test1, result1, ..., otherwise)` and the searched form to nested `if(...)`; a missing ELSE
// becomes `null()`.
caseExpression
    : CASE input=expression whenValue+ (ELSE elseResult=expression)? END   # simpleCase
    | CASE whenSearch+ (ELSE elseResult=expression)? END                   # searchedCase
    ;

whenValue  : WHEN test=expression THEN result=expression ;
whenSearch : WHEN condition=expr THEN result=expression ;

// `DISTINCT` is admitted uniformly for every aggregate at the grammar level; CypherToLogicalPlan currently accepts
// it only on count(DISTINCT <property>) and rejects the rest (sum/avg/min/max DISTINCT, count(DISTINCT *),
// count(DISTINCT <variable>)) at compile time - parse-now, restrict-at-compile, per this grammar's convention.
aggregateCall
    : fn=(COUNT | SUM | AVG | MIN | MAX | COLLECT) OPEN_PAREN DISTINCT? (STAR | expression) CLOSE_PAREN
    ;

// Stroom DIFF extension: before(a.prop)/after(a.prop) name a property value in the baseline (t1) / comparison
// (t2) snapshot of a diff query - only valid inside a DIFF query (enforced by CypherToLogicalPlan, not here).
diffAccessor
    : side=(BEFORE | AFTER) OPEN_PAREN propertyAccess CLOSE_PAREN
    ;

propertyAccess
    : variable=NAME DOT property=NAME
    ;

// Function arguments are general expressions (property accesses, literals, nested calls) so a scalar function can
// apply to matched row values, e.g. `upperCase(a.name)` - CypherToLogicalPlan lowers a RETURN function to Stroom's
// expression engine (ExpressionParser) over a curated allowlist, and rejects aggregate/diff-accessor arguments.
// Temporal-clause literals like `datetime('...')` still parse here (their string argument is just a literal
// expression); resolveInstant/resolveDuration unwrap it.
// An optional `namespace.` prefix (only `stroom.` is recognised, at compile time) selects the Stroom-native
// function library; a bare name is a Cypher-standard function. `stroom.upperCase(x)` and property access `a.b`
// both start NAME DOT NAME - the parser tells them apart by the trailing `(` (a call) vs none (a property).
functionCall
    : (namespace=NAME DOT)? name=NAME OPEN_PAREN (expression (COMMA expression)*)? CLOSE_PAREN
    ;

value
    : STRING          # stringValue
    | NUMBER          # numberValue
    | (TRUE | FALSE)  # booleanValue
    | PARAM           # paramValue
    | functionCall    # functionValue
    | OPEN_BRACKET (value (COMMA value)*)? CLOSE_BRACKET  # listValue
    ;

returnItem
    : expression (AS alias=NAME)?
    ;

orderByClause
    : ORDER BY orderItem (COMMA orderItem)*
    ;
orderItem
    : expression (ASC | DESC)?
    ;

skipClause  : SKIP_ NUMBER ;
limitClause : LIMIT NUMBER ;

// ============================================================================
// Lexer rules
// ============================================================================

// ----- skipped -----
COMMENT : '//' ~[\r\n]* -> channel(HIDDEN) ;
WS      : [ \t\r\n]+    -> channel(HIDDEN) ;

// ----- literals -----
PARAM : '$' [a-zA-Z_][a-zA-Z0-9_]* ;

fragment ESC : '\\' . ;
STRING : '\'' (ESC | ~['\\])* '\'' | '"' (ESC | ~["\\])* '"' ;

// Declared before NAME so an all-digit run is never mis-tokenised as an identifier.
NUMBER : DIGIT+ ('.' DIGIT+)? ;

// ----- keywords (case-insensitive; declared before NAME) -----
MATCH    : M A T C H ;
RETURN   : R E T U R N ;
WITH     : W I T H ;
WHERE    : W H E R E ;
AS       : A S ;
OF       : O F ;
DISTINCT : D I S T I N C T ;
ORDER    : O R D E R ;
BY       : B Y ;
ASC      : A S C ;
DESC     : D E S C ;
SKIP_    : S K I P ;
LIMIT    : L I M I T ;
AND      : A N D ;
OR       : O R ;
NOT      : N O T ;
TRUE     : T R U E ;
FALSE    : F A L S E ;
COUNT    : C O U N T ;
SUM      : S U M ;
AVG      : A V G ;
MIN      : M I N ;
MAX      : M A X ;
AROUND   : A R O U N D ;
BETWEEN  : B E T W E E N ;
DIFF     : D I F F ;
FROM     : F R O M ;
TO       : T O ;
BEFORE   : B E F O R E ;
AFTER    : A F T E R ;
GRAPH    : G R A P H ;
STARTS   : S T A R T S ;
ENDS     : E N D S ;
CONTAINS : C O N T A I N S ;
IN       : I N ;
IS       : I S ;
COLLECT  : C O L L E C T ;
OPTIONAL : O P T I O N A L ;
UNION    : U N I O N ;
ALL      : A L L ;
EXISTS   : E X I S T S ;
CASE     : C A S E ;
WHEN     : W H E N ;
THEN     : T H E N ;
ELSE     : E L S E ;
END      : E N D ;
// Trailing underscore to avoid reader confusion with Java's null keyword (matches the SKIP_ convention above).
NULL_    : N U L L ;

// ----- structural / operator tokens -----
OPEN_PAREN    : '(' ;
CLOSE_PAREN   : ')' ;
OPEN_BRACKET  : '[' ;
CLOSE_BRACKET : ']' ;
OPEN_BRACE    : '{' ;
CLOSE_BRACE   : '}' ;
COLON         : ':' ;
COMMA         : ',' ;
DOTDOT        : '..' ;
DOT           : '.' ;
STAR          : '*' ;
PLUS          : '+' ;
SLASH         : '/' ;
PERCENT       : '%' ;
CARET         : '^' ;
// The Unicode '±' sign, or the ASCII fallback '+/-' (typing '±' directly is impractical for most users/editors).
PLUSMINUS     : '±' | '+/-' ;
RARROW        : '->' ;
LARROW        : '<-' ;
DASH          : '-' ;
NEQ : '<>' | '!=' ;
REGEX : '=~' ;
LE  : '<=' ;
GE  : '>=' ;
LT  : '<' ;
GT  : '>' ;
EQ  : '=' ;

// ----- identifier (after all keywords, so keywords win) -----
NAME : [a-zA-Z_][a-zA-Z0-9_]* ;

// ----- case-insensitive letter fragments, used only to build keyword rules above -----
fragment A : [aA] ; fragment B : [bB] ; fragment C : [cC] ; fragment D : [dD] ;
fragment E : [eE] ; fragment F : [fF] ; fragment G : [gG] ; fragment H : [hH] ;
fragment I : [iI] ; fragment J : [jJ] ; fragment K : [kK] ; fragment L : [lL] ;
fragment M : [mM] ; fragment N : [nN] ; fragment O : [oO] ; fragment P : [pP] ;
fragment Q : [qQ] ; fragment R : [rR] ; fragment S : [sS] ; fragment T : [tT] ;
fragment U : [uU] ; fragment V : [vV] ; fragment W : [wW] ; fragment X : [xX] ;
fragment Y : [yY] ; fragment Z : [zZ] ;

fragment DIGIT : [0-9] ;
