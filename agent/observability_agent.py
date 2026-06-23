#!/usr/bin/env python3
"""
NGC Observability Agent — Backlog 1
------------------------------------
Scans InteractionService.java for recordCustomEvent() calls,
compares them against interaction-api-dashboard.tf,
and either prints a gap report (dry-run) or opens a GitHub draft PR
with the missing widget blocks.

Usage:
  # Dry run — just print the gap report
  python3 observability_agent.py

  # Open a real GitHub draft PR
  python3 observability_agent.py --github-token ghp_xxx --repo owner/ngc-platform
"""

import re
import sys
import os
import argparse
import textwrap
from pathlib import Path
from datetime import datetime

import anthropic

# ---------------------------------------------------------------------------
# Paths (relative to repo root)
# ---------------------------------------------------------------------------
REPO_ROOT = Path(__file__).parent.parent
SERVICE_FILE = REPO_ROOT / "interaction-api/src/main/java/com/test/interactionapi/service/InteractionService.java"
DASHBOARD_FILE = REPO_ROOT / "terraform/newrelic/interaction-api-dashboard.tf"


# ---------------------------------------------------------------------------
# Phase 1 — Read the service file and extract custom events
# ---------------------------------------------------------------------------
def read_service_code() -> str:
    return SERVICE_FILE.read_text()


def extract_custom_events(service_code: str) -> list[dict]:
    """
    Finds all recordCustomEvent() calls and the nrAttributes.put() calls
    that precede each one. Returns a list of:
      { event_type: str, attributes: [str] }
    """
    events = []

    # Find each recordCustomEvent call and look back for nrAttributes.put calls
    blocks = re.split(r'(?=NewRelic\.getAgent\(\)\.getInsights\(\)\.recordCustomEvent\()', service_code)

    for block in blocks[1:]:  # skip everything before the first call
        # Extract event type name
        event_match = re.search(r'recordCustomEvent\("(\w+)"', block)
        if not event_match:
            continue
        event_type = event_match.group(1)

        # Walk backwards from this point in the original code to find attributes
        pos = service_code.find(f'recordCustomEvent("{event_type}"')
        snippet = service_code[max(0, pos - 600):pos]

        # Find all nrAttributes.put("key", ...) in that snippet
        attrs = re.findall(r'nrAttributes\.put\("(\w+)"', snippet)

        events.append({"event_type": event_type, "attributes": attrs})

    return events


# ---------------------------------------------------------------------------
# Phase 2 — Read the dashboard and find covered event types
# ---------------------------------------------------------------------------
def read_dashboard_code() -> str:
    return DASHBOARD_FILE.read_text()


def extract_covered_events(dashboard_code: str) -> set[str]:
    """
    Finds all FROM <EventType> references in existing NRQL queries.
    """
    return set(re.findall(r'FROM\s+(\w+)', dashboard_code))


# ---------------------------------------------------------------------------
# Phase 3 — Use Claude to generate missing widget blocks
# ---------------------------------------------------------------------------
def generate_widgets_with_claude(gaps: list[dict], dashboard_code: str, service_code: str) -> str:
    """
    Asks Claude to generate Terraform widget_* blocks for each gap event,
    grounded on the actual attribute names found in the service code.
    """
    client = anthropic.Anthropic()

    gap_summary = "\n".join(
        f"- Event: {g['event_type']}, Attributes: {', '.join(g['attributes'])}"
        for g in gaps
    )

    prompt = f"""You are an observability engineer working on a Spring Boot microservice called interaction-api.

The service emits New Relic custom events. The Terraform dashboard file defines NRQL widgets for those events.

## Gaps found — events emitted by the service but NOT covered by any dashboard widget:

{gap_summary}

## Existing dashboard Terraform (for context and style reference):

```hcl
{dashboard_code}
```

## Task

For each gap event, generate one or more Terraform widget blocks to add to the dashboard.
Rules:
- Use ONLY the attribute names listed above — do not invent new ones
- Choose widget type based on attribute type: numeric attributes → widget_line with percentile() or average(); string attributes → widget_line with rate(count(*),1 minute) FACET
- Follow the exact same HCL style as the existing widgets
- Place new widgets starting at row = 10 to avoid collisions
- Include a brief comment above each widget explaining why it was added
- Output ONLY the raw HCL widget blocks — no markdown fences, no explanation text

account_id variable is: var.nr_account_id
"""

    for attempt in range(1, 4):
        try:
            message = client.messages.create(
                model="claude-opus-4-5",
                max_tokens=2048,
                messages=[{"role": "user", "content": prompt}]
            )
            return message.content[0].text.strip()
        except Exception as e:
            if attempt == 3:
                raise
            print(f"  Claude API error (attempt {attempt}/3): {e} — retrying in 5s...")
            import time
            time.sleep(5)


# ---------------------------------------------------------------------------
# Phase 4 — Build the PR body
# ---------------------------------------------------------------------------
def build_pr_body(gaps: list[dict], new_widgets: str) -> str:
    gap_lines = "\n".join(
        f"- `{g['event_type']}` — attributes: `{', '.join(g['attributes'])}`"
        for g in gaps
    )

    return textwrap.dedent(f"""
        ## Observability gap detected

        The following custom events are emitted by `InteractionService.java` but have
        no corresponding widget in `interaction-api-dashboard.tf`:

        {gap_lines}

        ## Proposed new widgets

        ```hcl
        {new_widgets}
        ```

        ## How to verify

        1. Run `terraform plan` — should show `X to add, 0 to change`
        2. Check the NRQL attribute names match what the service emits
        3. Merge → `terraform apply` updates the New Relic dashboard

        ## Safety

        - This PR was opened by the NGC observability agent (Backlog 1)
        - No `terraform apply` was run — human review required before merge
        - Rollback: `git revert` this commit + `terraform apply`

        ---
        *Generated by observability_agent.py on {datetime.utcnow().strftime('%Y-%m-%d %H:%M UTC')}*
    """).strip()


# ---------------------------------------------------------------------------
# Phase 5 — Open GitHub draft PR (optional)
# ---------------------------------------------------------------------------
def open_github_pr(token: str, repo_name: str, new_widgets: str, pr_body: str) -> str:
    from github import Github

    g = Github(token)
    repo = g.get_repo(repo_name)

    # Read current dashboard content
    dashboard_content = repo.get_contents("terraform/newrelic/interaction-api-dashboard.tf")
    current_code = dashboard_content.decoded_content.decode()

    # Insert new widgets before the closing braces of the page block
    updated_code = current_code.rstrip()
    # Insert before the last two closing braces (end of page + end of resource)
    insert_pos = updated_code.rfind("  }")
    updated_code = (
        updated_code[:insert_pos]
        + "\n\n"
        + textwrap.indent(new_widgets, "    ")
        + "\n\n"
        + updated_code[insert_pos:]
    )

    # Create branch
    branch_name = f"observability/gap-{datetime.utcnow().strftime('%Y%m%d-%H%M%S')}"
    main_sha = repo.get_branch("main").commit.sha
    repo.create_git_ref(f"refs/heads/{branch_name}", main_sha)

    # Commit updated dashboard file
    repo.update_file(
        path="terraform/newrelic/interaction-api-dashboard.tf",
        message="observability: add missing dashboard widget coverage",
        content=updated_code,
        sha=dashboard_content.sha,
        branch=branch_name,
    )

    # Open draft PR
    pr = repo.create_pull(
        title="observability: add missing dashboard widget coverage",
        body=pr_body,
        head=branch_name,
        base="main",
        draft=True,
    )

    return pr.html_url


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="NGC Observability Agent — Backlog 1")
    parser.add_argument("--github-token", help="GitHub personal access token")
    parser.add_argument("--repo", help="GitHub repo in owner/name format")
    args = parser.parse_args()

    print("=" * 60)
    print("NGC Observability Agent — Backlog 1")
    print("=" * 60)

    # Phase 1 — discover events from service code
    print("\n[Phase 1] Scanning InteractionService.java for custom events...")
    service_code = read_service_code()
    events = extract_custom_events(service_code)
    for e in events:
        print(f"  Found: {e['event_type']} → attributes: {e['attributes']}")

    # Phase 2 — check dashboard coverage
    print("\n[Phase 2] Reading interaction-api-dashboard.tf...")
    dashboard_code = read_dashboard_code()
    covered = extract_covered_events(dashboard_code)
    print(f"  Covered event types: {covered}")

    gaps = [e for e in events if e["event_type"] not in covered]

    if not gaps:
        print("\n  No gaps found — dashboard is fully in sync with the service.")
        sys.exit(0)

    print(f"\n  Gaps found ({len(gaps)}):")
    for g in gaps:
        print(f"    - {g['event_type']} (attributes: {g['attributes']})")

    # Phase 3 — generate widgets with Claude
    print("\n[Phase 3] Asking Claude to generate missing widget blocks...")
    anthropic_key = os.environ.get("ANTHROPIC_API_KEY")
    if not anthropic_key:
        print("  ERROR: ANTHROPIC_API_KEY environment variable not set.")
        sys.exit(1)

    new_widgets = generate_widgets_with_claude(gaps, dashboard_code, service_code)

    print("\n  Generated HCL:\n")
    print(textwrap.indent(new_widgets, "    "))

    # Phase 4 — build PR body
    pr_body = build_pr_body(gaps, new_widgets)

    # Phase 5 — open PR if credentials provided
    if args.github_token and args.repo:
        print(f"\n[Phase 4] Opening GitHub draft PR on {args.repo}...")
        pr_url = open_github_pr(args.github_token, args.repo, new_widgets, pr_body)
        print(f"\n  Draft PR opened: {pr_url}")
    else:
        print("\n[Phase 4] Dry run — no GitHub token provided.")
        print("  To open a real PR, run with:")
        print("    --github-token ghp_xxx --repo owner/ngc-platform")
        print("\n  PR body that would be posted:\n")
        print(textwrap.indent(pr_body, "    "))

    print("\n" + "=" * 60)
    print("Done.")
    print("=" * 60)


if __name__ == "__main__":
    main()
