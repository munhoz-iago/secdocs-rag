"""Export the running, server-rendered UI and real evaluation snapshots for GitHub Pages.

Only Python's standard library is required. This never calls a model or a database.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
from urllib.request import urlopen


def export(base_url: str, output: Path) -> None:
    root = Path(__file__).resolve().parents[1]
    with urlopen(base_url.rstrip("/") + "/api/workspace", timeout=30) as response:
        workspace = json.load(response)
    with urlopen(base_url.rstrip("/") + "/", timeout=30) as response:
        html = response.read().decode("utf-8")
    marker = '<meta name="secdocs-mode" content="live">'
    if marker not in html:
        raise ValueError("The UI is missing its explicit live/demo marker")
    html = html.replace(marker, '<meta name="secdocs-mode" content="demo">')
    # Assets and navigation must work under /secdocs-rag/, not only the domain root.
    html = re.sub(r'((?:src|href)=")/(?!/)', r'\1', html)
    if not workspace.get("evaluation", {}).get("available"):
        raise ValueError("A valid published retrieval snapshot is required for the demo")
    answers = json.loads((root / "evals/generation-results.json").read_text(encoding="utf-8-sig"))
    if answers.get("data") != "development" or not answers.get("cases"):
        raise ValueError("Real development generation results are required; no fabricated fallback")
    review_path = root / "evals/generation-review.json"
    if review_path.exists():
        review = json.loads(review_path.read_text(encoding="utf-8-sig"))
        if review.get("reviewType") != "model-assisted" or review.get("independent") is not False:
            raise ValueError("Semantic review must identify its model-assisted, non-independent provenance")
        reviewed_cases = {case["id"]: case for case in review["after"]}
        for case in answers["cases"]:
            if case["id"] in reviewed_cases:
                case["semanticReview"] = reviewed_cases[case["id"]]
        answers["semanticReviewType"] = "model-assisted"
        answers["semanticReviewIndependent"] = False
    static = root / "src/main/resources/static"
    output.mkdir(parents=True, exist_ok=True)
    shutil.copytree(static, output, dirs_exist_ok=True)
    (output / "index.html").write_text(html, encoding="utf-8")
    (output / ".nojekyll").touch()
    data = output / "data"
    data.mkdir(exist_ok=True)
    (data / "workspace.json").write_text(json.dumps(workspace, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (data / "answers.json").write_text(json.dumps(answers, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Demo exported to {output}: {len(workspace['sources'])} sources, {len(answers['cases'])} recorded cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--output", type=Path, default=Path("site"))
    args = parser.parse_args()
    export(args.base_url, args.output)
