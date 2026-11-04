#!/usr/bin/env bash
# Runs a candidate XSLT over a pilot case's input, validates its output against the event-logging XSD, and checks it
# against the golden events and the required messages. Usage:
#   tools/run-checks.sh path/to/candidate.xslt [output-dir] [case-dir-name]
# The case directory name defaults to case-01-firewall (for example: case-02-connections).
# Needs Java, the Saxon-HE 9.9.1-8 jar and the event-logging v4.1.0 XSD. Override the two paths below with
# SAXON_JAR and EVENT_XSD if they are elsewhere. Nothing here calls any model.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
CASE="$HERE/../${3:-case-01-firewall}"
CANDIDATE="${1:?usage: run-checks.sh candidate.xslt [output-dir]}"
OUT="${2:-$(mktemp -d)}"
SAXON_JAR="${SAXON_JAR:-$(find "$HOME/.gradle" -name 'Saxon-HE-9.9.1-8.jar' 2>/dev/null | head -1)}"
EVENT_XSD="${EVENT_XSD:-$(find "$HERE/../../../.." -name 'event_logging_v4_1_0*data.xsd' -not -path '*/worktrees/*' 2>/dev/null | head -1)}"
mkdir -p "$OUT/classes"
javac -d "$OUT/classes" "$HERE/Validate.java" || exit 2

echo "== 1. Does it compile and run over the parsed records?"
if java -cp "$SAXON_JAR" net.sf.saxon.Transform -s:"$CASE/input/parsed-records.xml" -xsl:"$CANDIDATE" -o:"$OUT/output.xml" 2> "$OUT/saxon-messages.txt"; then
  echo "RAN   (output: $OUT/output.xml; messages: $OUT/saxon-messages.txt)"
else
  echo "FAILED"; cat "$OUT/saxon-messages.txt"; exit 1
fi

echo "== 2. Is the output valid against the event-logging schema?"
java -cp "$OUT/classes" Validate "$EVENT_XSD" "$OUT/output.xml"

echo "== 3. Golden events"
java -cp "$SAXON_JAR" net.sf.saxon.Transform -s:"$OUT/output.xml" -xsl:"$CASE/golden/check-golden.xslt" | tee "$OUT/golden.txt"
echo "-- $(grep -c '^PASS' "$OUT/golden.txt") passed, $(grep -c '^FAIL' "$OUT/golden.txt") failed"
echo "== 4. Were dropped records reported? (required messages)"
MISSING=0
while IFS= read -r pattern; do
  case "$pattern" in ''|'#'*) continue;; esac
  if grep -Eq "$pattern" "$OUT/saxon-messages.txt"; then echo "PASS message matching: $pattern"; else echo "FAIL no message matching: $pattern"; MISSING=$((MISSING+1)); fi
done < "$CASE/golden/required-messages.txt"
echo "-- $MISSING required message(s) missing"
echo "-- messages the stylesheet raised:"
cat "$OUT/saxon-messages.txt"
