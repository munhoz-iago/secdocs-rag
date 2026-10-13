"""Compare the two published development runs; never score semantic correctness."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    return json.loads((ROOT / "evals" / name).read_text(encoding="utf-8-sig"))


def summarize(report):
    cases = report["cases"]
    answerable = [row for row in cases if row["expectedAnswerable"]]
    outside = [row for row in cases if not row["expectedAnswerable"]]
    return {
        "citationStrategy": report["citationStrategy"],
        "answerable": len(answerable),
        "answeredWithCitations": sum(bool(row["acceptedAnswer"]) for row in answerable),
        "falseRefusals": sum(bool(row["abstained"]) for row in answerable),
        "outside": len(outside),
        "outsideAbstained": sum(bool(row["abstained"]) for row in outside),
        "medianAnswerableMillis": statistics.median(row["elapsedMillis"] for row in answerable),
    }


def compare():
    before, after = load("generation-baseline.json"), load("generation-results.json")
    fixture_hash = hashlib.sha256((ROOT / "evals/generation-set.json").read_bytes()).hexdigest()
    if before["fixtureSha256"] != fixture_hash or after["fixtureSha256"] != fixture_hash:
        raise ValueError("Runs do not match the frozen fixture bytes")
    if before["chatModelFingerprint"] != after["chatModelFingerprint"]:
        raise ValueError("Chat model fingerprints differ")
    expected = [(r["id"], r["question"], r["expectedAnswerable"]) for r in load("generation-set.json")["questions"]]
    for report in (before, after):
        actual = [(r["id"], r["question"], r["expectedAnswerable"]) for r in report["cases"]]
        if actual != expected or report["independent"] is not False:
            raise ValueError("Case order, questions or development provenance differ")
    return {
        "data": "development", "independent": False,
        "fixtureSha256": fixture_hash, "chatModelFingerprint": after["chatModelFingerprint"],
        "definition": "Answered with citations means accepted by structural/literal checks; not semantic correctness.",
        "limitation": "One run per version on eight known cases. Prompt and token budget also changed. No generalization or isolated causal claim.",
        "before": summarize(before), "after": summarize(after),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify the checked-in comparison instead of writing it")
    args = parser.parse_args()
    result = compare()
    path = ROOT / "evals/generation-comparison.json"
    if args.check:
        if load(path.name) != result:
            raise ValueError("Published comparison is stale")
        print("Published generation comparison matches both real runs and the frozen fixture")
    else:
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
