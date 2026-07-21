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

// StroomQL grammar (see docs/query-optimiser-implementation-plan.md, Task 1.1).
//
// Design notes (why this grammar looks the way it does, vs. the legacy hand-coded
// Tokeniser/StructureBuilder/SearchRequestFactory it must stay at parity with):
//
// - Clause ORDER and CARDINALITY are deliberately NOT constrained here. Legacy accepts any
//   clause after `from` and validates ordering/repetition semantically via the shared
//   stroom.query.api.token.TokenType.KEYWORDS_REQUIRED_BEFORE / KEYWORDS_VALID_BEFORE maps
//   (checkTokenOrder). The AST binder (Task 1.4) re-runs that exact same check against the
//   same shared maps, so this grammar just accepts `from` followed by any number of clauses
//   in any order and leaves ordering/cardinality to the binder - this is both simpler and
//   guarantees identical accept/reject decisions to legacy.
// - The boolean expression grammar (`expr`) intentionally mirrors legacy's actual precedence
//   (not > and > or) as a flat repetition at each level (`andExpr: notExpr (AND notExpr)*`),
//   because Task 1.4 must fold each repetition pairwise, left-associatively, into nested
//   ExpressionOperator nodes (matching legacy's applyAndOrOperators) for byte-identical JSON -
//   a flat n-ary AND/OR would NOT match legacy's output shape.
// - `fexpr` (the eval/select computed-expression grammar) exists for syntax coverage and error
//   recovery, but its CONTENTS are not semantically interpreted by this grammar/AST at all:
//   Task 1.4 hands the original source text of a whole fexpr span to the existing, unchanged
//   stroom.query.language.functions.ExpressionParser (kept as-is per the design plan's
//   non-goals) - so this grammar only needs to structurally delineate where such a span starts
//   and ends, not reproduce ExpressionParser's own grammar precisely.
// - `and`/`or`/`not` are reserved keywords everywhere in this grammar (unlike legacy, where they
//   are ONLY keywords inside where/filter/having and are otherwise ordinary identifiers eligible
//   to become function names, e.g. `eval bool = and(a, b)` - see the golden corpus). The
//   `functionCall` rule explicitly allows AND/OR/NOT in the function-name position to cover that
//   real, corpus-exercised case; a field/variable literally NAMED "and"/"or"/"not" (not a
//   function call) is an accepted, documented deviation - not exercised by the parity corpus.
// - Join syntax (JOIN/ON/LEFT/INNER, qualified `alias.field` references) is bound by
//   stroom.query.planner.bind.Binder (docs/query-optimiser-implementation-plan.md, Phase 6).
//   `alias.field` is deliberately NOT a `NAME DOT NAME` token sequence - legacy's bareword
//   character class already includes `.` (so unquoted dotted names, and decimal numbers,
//   tokenize as ONE token) - splitting on `.` is a binder concern, not a lexer concern, to avoid
//   changing bareword tokenization at all.
// - A join source may also be a bracketed sub-query (docs/graphdb-stroomql-join-implementation-
//   plan.md, Phase P1), e.g. `join ( from "Graph" match ... return ... ) as g`. `subQueryBody`
//   captures that bracketed body as opaque, paren-balanced source text - it does not, and cannot,
//   parse the body's own grammar (Cypher today) at this level; it only needs to find the matching
//   close bracket. This is the same "delineate a span, interpret it elsewhere" approach `fexpr`
//   already uses for eval/select expressions (see above), just balancing brackets instead of
//   assuming a StroomQL-shaped expression grammar inside. Note that the underlying character set
//   this grammar's lexer accepts (BAREWORD's catch-all class in particular) is permissive enough
//   that a Cypher pattern like `(u:User)-[:MEMBER_OF]->(g:Group)` still tokenises cleanly - `:`,
//   `[`, `]` are ordinary bareword characters here, and `-`/`>`/`(`/`)` are already their own
//   single-character tokens - so no lexer changes were needed to carry an embedded Cypher body.
grammar StroomQL;

// ============================================================================
// Parser rules
// ============================================================================

query
    : fromClause topLevelClause* EOF
    ;

fromClause
    : FROM source=nameToken (AS alias=nameToken)? joinClause*
    ;

joinClause
    : joinType=(LEFT | INNER)? JOIN
      ( source=nameToken | OPEN_BRACKET subQuery=subQueryBody CLOSE_BRACKET )
      (AS alias=nameToken)?
      ON joinCondition (AND joinCondition)*
    ;

// See file header note on "join source may be a bracketed sub-query" - an opaque, paren-balanced
// span of arbitrary tokens: any token that isn't itself a bracket, or a further balanced bracket
// pair (so a nested pattern like `(u:User)` inside the join's own brackets doesn't terminate the
// span early). Never structurally interpreted here - stroom.query.planner.bind.Binder re-parses
// the captured raw text with the Cypher grammar.
subQueryBody
    : ( ~(OPEN_BRACKET | CLOSE_BRACKET) | OPEN_BRACKET subQueryBody CLOSE_BRACKET )*
    ;

joinCondition
    : left=qualifiedField EQUALS right=qualifiedField
    ;

// A single token whose text MAY contain a `.` qualifier (e.g. `alias.field`);
// splitting it is a Phase 6 binder concern, not a lexer concern - see file header.
qualifiedField
    : nameToken
    ;

topLevelClause
    : whereClause
    | evalClause
    | windowClause
    | filterClause
    | sortClause
    | groupClause
    | havingClause
    | selectClause
    | limitClause
    | showClause
    ;

// ----- where / filter / having: boolean expression grammar -----

whereClause  : WHERE expr ;
filterClause : FILTER expr ;
havingClause : HAVING expr ;

expr    : orExpr ;
orExpr  : andExpr (OR andExpr)* ;
andExpr : notExpr (AND notExpr)* ;
notExpr
    : NOT notExpr
    | primary
    ;
primary
    : OPEN_BRACKET expr CLOSE_BRACKET
    | term
    ;

term
    : field=fieldRef cond=comparisonCond value=termValue                         # comparisonTerm
    | field=fieldRef BETWEEN lower=termValue AND upper=termValue                 # betweenTerm
    | field=fieldRef IN OPEN_BRACKET termValue (COMMA termValue)* CLOSE_BRACKET  # inTerm
    | field=fieldRef IN DICTIONARY dictionaryName=nameToken                      # inDictionaryTerm
    | field=fieldRef isNull=(IS_NULL | IS_NOT_NULL)                              # isNullTerm
    ;

// A field/name reference that may also be a `${param}` reference - matches legacy's use of
// TokenType.isString(...) (which includes PARAM in ALL_STRINGS) for field names throughout
// where/eval/group/sort/window (see e.g. processGroupBy, processSortBy, processEval in
// SearchRequestFactory). Deliberately NOT used for `select`'s plain-field alternative, which
// already has its own dedicated selectParam alternative for PARAM's wildcard-expansion special
// case - reusing it there would make selectItem's alternatives ambiguous on a PARAM token.
fieldRef : nameToken | PARAM ;

comparisonCond
    : EQUALS | NOT_EQUALS
    | GREATER_THAN | GREATER_THAN_OR_EQUAL_TO
    | LESS_THAN | LESS_THAN_OR_EQUAL_TO
    ;

// One term value: legacy concatenates a run of tokens (e.g. `now() - 2d` = function,
// minus, duration) then classifies the WHOLE run as a date expression, a signed number,
// or (otherwise) a single bare token - see DateExpressionParser / addValue in
// SearchRequestFactory. That classification is a Task 1.4 concern, not a grammar concern;
// this rule only delineates the run's extent.
termValue : valueToken+ ;

valueToken
    : BAREWORD | SINGLE_QUOTED_STRING | DOUBLE_QUOTED_STRING
    | NUMBER | DATE_TIME | DURATION | PARAM
    | PLUS | MINUS
    | valueFunctionCall
    ;

// A term value only ever needs to call date-point/param functions (`now()`, `day()`,
// `param(...)`) - never `and`/`or`/`not` (legacy's parseValueTokens rejects any function
// there that isn't a recognised DatePoint or "param"; see SearchRequestFactory.addValue).
// So this is deliberately FUNCTION_NAME-only, unlike the broader `functionCall` below:
// `termValue` sits inside the boolean-expression grammar (term -> termValue), where AND/OR
// are ALSO valid continuations at exactly this position (e.g. `x = 'a' or (y != 'b')`) -
// letting AND/OR/NOT start a function call here would make that position genuinely
// ambiguous between "keyword starting a function call" and "keyword as the boolean
// connective, followed by a bracketed primary". FUNCTION_NAME's own lexer-level adjacency
// predicate (see below) already guarantees no whitespace before '(', so no parser-level
// predicate is needed here either - this rule is unambiguous by construction.
valueFunctionCall
    : name=FUNCTION_NAME OPEN_BRACKET
      (functionArg (COMMA functionArg)*)?
      CLOSE_BRACKET
    ;

// See file header: AND/OR/NOT are valid function names too (`and(a, b)` etc.), matching
// the golden corpus, even though they are also reserved keywords in `expr`/`andExpr`/`orExpr`.
// Used only from `fexpr`/`operand` (eval/select computed expressions) and `selectItem`, both
// of which have no competing and/or/not interpretation at that grammar position - unlike
// `valueToken` (see `valueFunctionCall` above), so no adjacency predicate is needed: a bare
// "or " (no following '(') simply never matches this alternative in the first place, since
// OPEN_BRACKET is mandatory here regardless.
functionCall
    : name=(FUNCTION_NAME | AND | OR | NOT) OPEN_BRACKET
      (functionArg (COMMA functionArg)*)?
      CLOSE_BRACKET
    ;

functionArg : fexpr ;

// ----- eval / select computed-expression grammar -----
// See file header: contents are delegated to the existing ExpressionParser at compile
// time; ANTLR4 handles the left recursion natively (ordinary repetition below, no direct
// left recursion is actually needed for this precedence chain).

fexpr   : cmpExpr ;
cmpExpr : addExpr (comparisonCond addExpr)* ;
addExpr : mulExpr ((PLUS | MINUS) mulExpr)* ;
mulExpr : powExpr ((DIVISION | MULTIPLICATION | MODULUS) powExpr)* ;
powExpr : unary (ORDER unary)* ;
unary   : (PLUS | MINUS)? operand ;
operand
    : functionCall
    | OPEN_BRACKET fexpr CLOSE_BRACKET
    | nameToken
    | PARAM
    | literal
    ;
literal
    : SINGLE_QUOTED_STRING | DOUBLE_QUOTED_STRING
    | NUMBER | DURATION | DATE_TIME
    ;

evalClause
    : EVAL name=fieldRef EQUALS fexpr
    ;

windowClause
    : WINDOW field=fieldRef BY windowSize=DURATION
      (advanceKw=nameToken advanceSize=DURATION)?
      (usingKw=nameToken usingFunction=nameToken)?
    ;

// `asc`/`desc` are contextual (ordinary name tokens interpreted case-insensitively by
// the binder, exactly as legacy's processSortBy does; SortDirection.valueOf(...) is also
// tried as a legacy fallback) - not reserved keywords, so a field can still be named
// "asc" elsewhere.
sortClause
    : SORT BY? sortItem (COMMA sortItem)*
    ;
sortItem : field=fieldRef direction=nameToken? ;

groupClause
    : GROUP BY? fieldRef (COMMA fieldRef)*
    ;

selectClause
    : SELECT selectItem (COMMA selectItem)*
    ;
selectItem
    : MULTIPLICATION (AS alias=nameToken)?          # selectStar
    | functionCall (AS alias=nameToken)?             # selectFunction
    | field=PARAM (AS alias=nameToken)?              # selectParam
    | field=nameToken (AS alias=nameToken)?          # selectField
    ;

// Legacy accepts quoted/bare numeric-looking strings here too (Long.parseLong on the
// unescaped text), not just NUMBER tokens - see processLimit.
limitClause
    : LIMIT limitValue (COMMA limitValue)*
    ;
limitValue : NUMBER | nameToken ;

showClause
    : SHOW AS name=nameToken
    ;

nameToken
    : BAREWORD
    | SINGLE_QUOTED_STRING
    | DOUBLE_QUOTED_STRING
    ;

// ============================================================================
// Lexer rules
// ============================================================================

// ----- skipped -----
COMMENT       : '//' ~[\r\n]*                 -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/'                 -> channel(HIDDEN) ;
WS            : [ \t\r\n]+                    -> channel(HIDDEN) ;

// ----- params -----
PARAM : '${' ~[}]* '}' ;

// ----- quoted strings (backslash escapes any single following character) -----
fragment ESC : '\\' . ;
SINGLE_QUOTED_STRING : '\'' (ESC | ~['\\])* '\'' ;
DOUBLE_QUOTED_STRING : '"'  (ESC | ~["\\])* '"'  ;

// ----- dates / durations / numbers -----
// Declared before BAREWORD so that on an equal-length match (e.g. "3.14" could also be
// read as one bareword run) these more specific rules win the ANTLR lexer tie-break.
DATE_TIME : DIGIT DIGIT DIGIT DIGIT '-' DIGIT DIGIT '-' DIGIT DIGIT
            'T' DIGIT DIGIT ':' DIGIT DIGIT ':' DIGIT DIGIT '.' DIGIT DIGIT DIGIT 'Z'? ;

// Unit letters are matched case-insensitively, matching legacy's Pattern.CASE_INSENSITIVE
// tokeniser regex - see the design doc's note on ns/ms/s/m/h/d/w/M/y case collapse.
DURATION : DIGIT+ (N S | M S | S | M | H | D | W | Y) ;

// Exponent form requires a MINUS sign (no bare/plus exponent) - matches legacy's
// `([Ee]-\d+)?` regex exactly (see Appendix A of the design doc).
NUMBER : DIGIT+ ('.' DIGIT+)? ([eE] '-' DIGIT+)? ;

// ----- keywords (case-insensitive; declared before BAREWORD) -----
FROM     : F R O M ;
WHERE    : W H E R E ;
EVAL     : E V A L ;
SELECT   : S E L E C T ;
SORT     : S O R T ;
GROUP    : G R O U P ;
FILTER   : F I L T E R ;
WINDOW   : W I N D O W ;
LIMIT    : L I M I T ;
HAVING   : H A V I N G ;
SHOW     : S H O W ;
BY       : B Y ;
AS       : A S ;
BETWEEN  : B E T W E E N ;
DICTIONARY : D I C T I O N A R Y ;
IN       : I N ;
// NOTE: legacy's Tokeniser fails to recognise "and"/"or"/"not" as keywords when immediately
// preceded by '(' with no space (e.g. `(not x = 1)` is rejected, `( not x = 1)` is accepted) -
// a whitespace-sensitivity artifact of its per-chunk regex tagging (see Tokeniser.tagKeyword),
// not a deliberate rule. Found by the generative fuzzer (Task 1.7). Per the project's policy of
// not reproducing legacy bugs, this grammar deliberately does NOT replicate it - AND/OR/NOT are
// recognised regardless of what precedes them - see docs/query-optimiser-known-differences.md
// and TestLegacyBugFixes for the demonstrating test case.
AND      : A N D ;
OR       : O R ;
NOT      : N O T ;
IS_NULL     : I S WS_ N U L L ;
IS_NOT_NULL : I S WS_ N O T WS_ N U L L ;

// Reserved for Phase 6 (see file header).
JOIN  : J O I N ;
ON    : O N ;
LEFT  : L E F T ;
INNER : I N N E R ;

// ----- function-call name: lowercase-start identifier immediately (no whitespace)
// followed by '(' - matches legacy's `([a-z][a-zA-Z]*)(\()` tokeniser regex exactly,
// including that an uppercase-starting identifier before '(' is NOT a function call. -----
FUNCTION_NAME : [a-z][a-zA-Z]* {_input.LA(1) == '('}? ;

// ----- structural / operator tokens -----
OPEN_BRACKET  : '(' ;
CLOSE_BRACKET : ')' ;
COMMA         : ',' ;
ORDER         : '^' ;
DIVISION      : '/' ;
MULTIPLICATION: '*' ;
MODULUS       : '%' ;
PLUS          : '+' ;
MINUS         : '-' ;
NOT_EQUALS               : '!=' ;
LESS_THAN_OR_EQUAL_TO    : '<=' ;
GREATER_THAN_OR_EQUAL_TO : '>=' ;
LESS_THAN                : '<' ;
GREATER_THAN             : '>' ;
EQUALS                   : '=' ;

// ----- catch-all bareword: anything not whitespace/quote/bracket/comma/operator,
// matching legacy's EBNF `bareword` definition exactly (see design doc Appendix A). -----
fragment BCHAR : ~[ \t\r\n'"(),^/*%+\-!<>=] ;
BAREWORD : BCHAR+ ;

// ----- case-insensitive letter fragments, used only to build keyword rules above -----
fragment A : [aA] ; fragment B : [bB] ; fragment C : [cC] ; fragment D : [dD] ;
fragment E : [eE] ; fragment F : [fF] ; fragment G : [gG] ; fragment H : [hH] ;
fragment I : [iI] ; fragment J : [jJ] ; fragment K : [kK] ; fragment L : [lL] ;
fragment M : [mM] ; fragment N : [nN] ; fragment O : [oO] ; fragment P : [pP] ;
fragment Q : [qQ] ; fragment R : [rR] ; fragment S : [sS] ; fragment T : [tT] ;
fragment U : [uU] ; fragment V : [vV] ; fragment W : [wW] ; fragment X : [xX] ;
fragment Y : [yY] ; fragment Z : [zZ] ;
fragment WS_: [ \t\r\n]+ ;

fragment DIGIT : [0-9] ;
