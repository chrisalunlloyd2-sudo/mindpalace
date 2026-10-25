#!/usr/bin/env python3
"""Tests for scripts/claude_commit_watch.py.

Covers the behaviours the bots depend on:
  * private repos ARE walked (the whole point — a public-events watcher
    cannot see them),
  * Claude commits are classified by canonical identity, not by message text,
  * non-Claude commits are rejected,
  * pagination works and stops early at the incremental cursor,
  * lessons are deduped by commit SHA,
  * private-repo material is redacted (repo pseudonymised, paths and
    secrets scrubbed) while public-repo lessons keep their real name.

Zero network: the GitHub API is faked in-process.
"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import claude_commit_watch as ccw


def make_commit(sha, author_name="chrisalunlloyd2-sudo",
                author_email="chris@example.com", login="chrisalunlloyd2-sudo",
                message="fix: something", files=None, stats=None):
    commit = {
        "sha": sha,
        "commit": {
            "author": {"name": author_name, "email": author_email},
            "message": message,
        },
        "author": {"login": login} if login else None,
    }
    if files is not None:
        commit["files"] = files
    if stats is not None:
        commit["stats"] = stats
    return commit


def repo(full_name, private=False, pushed_at="2026-10-24T22:00:00Z",
         default_branch="main"):
    return {
        "full_name": full_name,
        "name": full_name.split("/")[-1],
        "private": private,
        "pushed_at": pushed_at,
        "default_branch": default_branch,
    }


def _qpage(path):
    """Parse ?page=N out of a query string (per_page must not be confused
    with page)."""
    for part in path.split("?", 1)[-1].split("&"):
        if part.startswith("page="):
            return int(part.split("=", 1)[1])
    return 1


class FakeApi:
    """Minimal in-process stand-in for the GitHub REST API."""

    def __init__(self, commits_by_repo=None, details=None, pulls=None,
                 pr_commits=None, branches=None, branch_commits=None):
        self.commits_by_repo = commits_by_repo or {}
        self.details = details or {}
        self.pulls = pulls or {}
        self.pr_commits = pr_commits or {}
        self.branches = branches or {}
        self.branch_commits = branch_commits or {}
        self.calls = []

    def __call__(self, path, token, timeout=30, retries=2):
        self.calls.append(path)
        # Dispatch order matters: the most specific route wins. A PR's
        # commit list ("/pulls/7/commits?...") also contains "/commits?",
        # so the PR route MUST be tested before the generic one.
        if path.startswith("/user/repos"):
            page = _qpage(path)
            if page == 1:
                return [repo("chrisalunlloyd2-sudo/mindpalace"),
                        repo("chrisalunlloyd2-sudo/viper-scripts", private=True)]
            return []
        if "/pulls/" in path and "/commits" in path:
            full = path.split("/repos/")[1].split("/pulls/")[0]
            number = int(path.split("/pulls/")[1].split("/")[0])
            return self.pr_commits.get((full, number), [])
        if "/pulls?" in path:
            full = path.split("/repos/")[1].split("/pulls")[0]
            page = _qpage(path)
            data = self.pulls.get(full, [])
            return data[:100] if page == 1 else data[100:]
        if "/branches?" in path:
            full = path.split("/repos/")[1].split("/branches")[0]
            page = _qpage(path)
            data = self.branches.get(full, [])
            return data[:100] if page == 1 else data[100:]
        if "/commits?" in path:
            full = path.split("/repos/")[1].split("/commits")[0]
            ref = path.split("sha=")[1].split("&")[0]
            page = _qpage(path)
            # The default branch is the repo's own default_branch; any other
            # ref is an extra branch walk.
            if ref == "main":
                data = self.commits_by_repo.get(full, [])
            else:
                data = self.branch_commits.get((full, ref), [])
            return data[:100] if page == 1 else data[100:]
        if "/commits/" in path:
            full = path.split("/repos/")[1].split("/commits/")[0]
            sha = path.rsplit("/", 1)[1]
            return self.details.get((full, sha))
        return None


class ClaudeCommitWatchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.memory = Path(self.tmp.name)
        self.state = self.memory / "claude_watch"

    def tearDown(self):
        self.tmp.cleanup()

    def run_harvest(self, fake, repos, **kwargs):
        with mock.patch.object(ccw, "api_get", fake):
            return ccw.harvest("token", self.memory, repos, **kwargs)

    # ── classification ───────────────────────────────────────────────────
    def test_classifies_by_canonical_bot_identity(self):
        c = make_commit("a1", author_name="anthropic-code-agent[bot]",
                        author_email="242468646+Claude@users.noreply.github.com",
                        login="Claude")
        self.assertEqual("author", ccw.classify_commit(c))

    def test_classifies_by_claude_login_only(self):
        c = make_commit("a2", author_name="Some Bot",
                        author_email="bot@example.com", login="Claude")
        self.assertEqual("author", ccw.classify_commit(c))

    def test_classifies_co_author_trailer(self):
        c = make_commit(
            "a3", author_name="chrisalunlloyd2-sudo",
            author_email="chris@example.com", login="chrisalunlloyd2-sudo",
            message=("feat: add thing\n\n"
                     "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"))
        self.assertEqual("co_author", ccw.classify_commit(c))

    def test_rejects_non_claude_commit(self):
        c = make_commit("a4")
        self.assertIsNone(ccw.classify_commit(c))

    def test_message_text_alone_is_not_a_signal(self):
        # "Claude" appearing in prose must NOT make a commit Claude's.
        c = make_commit("a5", message="fix: ask Claude about the API later")
        self.assertIsNone(ccw.classify_commit(c))

    def test_garbage_commit_is_safe(self):
        self.assertIsNone(ccw.classify_commit({}))
        self.assertIsNone(ccw.classify_commit(None))

    # ── private repos ────────────────────────────────────────────────────
    def test_private_repo_is_walked_and_pseudonymised(self):
        sha = "c" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/viper-scripts": [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude",
                            message="fix: patch C:\\Users\\chris\\secret\\thing.py")
            ]})
        payload = self.run_harvest(fake, [repo("chrisalunlloyd2-sudo/viper-scripts",
                                               private=True)])
        self.assertEqual(1, payload["new_lessons"])
        lesson = payload["items"][0]
        self.assertTrue(lesson["private"])
        self.assertEqual("", lesson["display_repo"])
        self.assertTrue(lesson["repo_key"].startswith("repo-"))
        self.assertNotIn("viper-scripts", lesson["repo_key"])

    def test_public_repo_lesson_keeps_real_name(self):
        sha = "d" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude", message="feat: add selftest gate")
            ]})
        payload = self.run_harvest(fake, [repo("chrisalunlloyd2-sudo/mindpalace")])
        lesson = payload["items"][0]
        self.assertFalse(lesson["private"])
        self.assertEqual("chrisalunlloyd2-sudo/mindpalace", lesson["display_repo"])
        self.assertEqual("chrisalunlloyd2-sudo/mindpalace", lesson["repo_key"])
        self.assertEqual("feat: add selftest gate", lesson["subject"])

    # ── redaction ────────────────────────────────────────────────────────
    def test_private_subject_is_scrubbed(self):
        key = ccw.load_redaction_key(self.memory)
        # Built at runtime from parts so no real secret-shaped literal is
        # ever committed to the repo (GitHub push protection blocks those).
        fake_pat = "github_pat_" + ("A1b2C3d4E5f6" * 4)
        lesson = ccw.build_lesson(
            make_commit("e" * 40, message=(
                "fix: update " + fake_pat +
                " in C:/Users/viper/AIGEN_SYS/repos/thing.py")),
            "author", "chrisalunlloyd2-sudo/private-repo", True, key)
        self.assertNotIn("github_pat_", lesson["subject"])
        self.assertNotIn("C:/Users", lesson["subject"])
        self.assertNotIn("thing.py", lesson["subject"])

    def test_secret_pattern_redacted(self):
        # Synthetic, non-credential token shapes (no real secret material).
        self.assertNotIn("ghp_", ccw.redact_text("token ghp_" + "A1b2C3d4E5f6" * 3))
        self.assertNotIn("sk-", ccw.redact_text("key sk-" + "A1b2C3d4E5f6" * 3))

    def test_pseudonym_is_stable_and_opaque(self):
        key = ccw.load_redaction_key(self.memory)
        one = ccw.pseudonym("chrisalunlloyd2-sudo/secret", key)
        two = ccw.pseudonym("chrisalunlloyd2-sudo/secret", key)
        self.assertEqual(one, two)
        self.assertNotIn("secret", one)

    def test_pseudonym_differs_per_repo(self):
        key = ccw.load_redaction_key(self.memory)
        self.assertNotEqual(ccw.pseudonym("o/a", key), ccw.pseudonym("o/b", key))

    # ── structure extraction ─────────────────────────────────────────────
    def test_lesson_extracts_structure_without_code(self):
        key = ccw.load_redaction_key(self.memory)
        detail = {
            "stats": {"additions": 42, "deletions": 7},
            "files": [{"filename": "src/Main.java", "patch": "@@ -1 +1 @@\n-secretSourceLine"},
                      {"filename": "src/test/MainTest.java"}],
        }
        lesson = ccw.build_lesson(
            make_commit("f" * 40, message="feat: add renderer"),
            "author", "chrisalunlloyd2-sudo/mindpalace", False, key, detail)
        self.assertEqual(2, lesson["files_changed"])
        self.assertTrue(lesson["touched_test"])
        self.assertEqual([".java"], lesson["extensions"])
        self.assertEqual(42, lesson["additions"])
        # The body of the change must NOT be copied into the lesson.
        self.assertNotIn("secretSourceLine", json.dumps(lesson))

    def test_derive_tags(self):
        lesson = {"subject": "fix: repair gate", "files_changed": 3,
                  "role": "co_author", "private": True, "touched_test": True}
        tags = ccw.derive_tags(lesson)
        self.assertIn("fix", tags)
        self.assertIn("multi_file", tags)
        self.assertIn("co_authored", tags)
        self.assertIn("private_repo", tags)
        self.assertIn("test_touched", tags)

    # ── dedupe + incremental ─────────────────────────────────────────────
    def test_same_commit_is_never_harvested_twice(self):
        sha = "9" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude")]})
        repos = [repo("chrisalunlloyd2-sudo/mindpalace")]
        first = self.run_harvest(fake, repos, force=True)
        second = self.run_harvest(fake, repos, force=True)
        self.assertEqual(1, first["new_lessons"])
        self.assertEqual(0, second["new_lessons"])
        rows = ccw._read_jsonl(self.state / "lessons.jsonl")
        self.assertEqual(1, len(rows))

    def test_non_claude_commit_is_remembered_and_not_rejudged(self):
        sha = "8" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": [make_commit(sha)]})
        repos = [repo("chrisalunlloyd2-sudo/mindpalace")]
        first = self.run_harvest(fake, repos, force=True)
        second = self.run_harvest(fake, repos, force=True)
        self.assertEqual(0, first["new_lessons"])
        self.assertEqual(0, second["new_lessons"])
        seen = ccw._read_jsonl(self.state / "seen.jsonl")
        self.assertTrue(any(row.get("sha") == sha for row in seen))

    def test_incremental_run_skips_unchanged_repo(self):
        sha = "7" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude")]})
        repos = [repo("chrisalunlloyd2-sudo/mindpalace",
                      pushed_at="2026-10-24T22:00:00Z")]
        self.run_harvest(fake, repos)
        # Same pushed_at -> repo skipped, no commit list fetched at all.
        before = len(fake.calls)
        second = self.run_harvest(fake, repos)
        self.assertEqual(0, second["new_lessons"])
        self.assertEqual(before, len(fake.calls))

    def test_new_push_is_picked_up(self):
        sha_old = "6" * 40
        sha_new = "5" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha_new, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude", message="feat: new work"),
            ]})
        repos = [repo("chrisalunlloyd2-sudo/mindpalace",
                      pushed_at="2026-10-24T23:00:00Z")]
        payload = self.run_harvest(fake, repos)
        self.assertEqual(1, payload["new_lessons"])
        self.assertEqual("feat: new work", payload["items"][0]["subject"])

    # ── pagination ───────────────────────────────────────────────────────
    def test_pagination_walks_beyond_first_page(self):
        page1 = [make_commit("%040d" % i, author_name="anthropic-code-agent[bot]",
                             author_email="242468646+Claude@users.noreply.github.com",
                             login="Claude") for i in range(100)]
        page2 = [make_commit("%040d" % i, author_name="anthropic-code-agent[bot]",
                             author_email="242468646+Claude@users.noreply.github.com",
                             login="Claude") for i in range(100, 103)]
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": page1 + page2})
        payload = self.run_harvest(fake, [repo("chrisalunlloyd2-sudo/mindpalace")],
                                   per_repo_limit=250, max_new=250)
        self.assertEqual(103, payload["new_lessons"])

    def test_stops_early_at_cursor(self):
        old = "%040d" % 42
        commits = [make_commit("%040d" % i, author_name="anthropic-code-agent[bot]",
                               author_email="242468646+Claude@users.noreply.github.com",
                               login="Claude") for i in range(0, 10)]
        commits[5] = make_commit(old, author_name="anthropic-code-agent[bot]",
                                 author_email="242468646+Claude@users.noreply.github.com",
                                 login="Claude")
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": commits})
        with mock.patch.object(ccw, "api_get", fake):
            found = ccw.fetch_commits("token", "chrisalunlloyd2-sudo/mindpalace",
                                      "main", old)
        # 5 newer commits + the cursor commit itself, then stop.
        self.assertEqual(6, len(found))
        self.assertEqual(old, found[-1]["sha"])

    # ── state files ──────────────────────────────────────────────────────
    def test_latest_json_is_written_for_ingest(self):
        sha = "4" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude")]})
        self.run_harvest(fake, [repo("chrisalunlloyd2-sudo/mindpalace")])
        latest = self.state / "latest.json"
        self.assertTrue(latest.is_file())
        payload = json.loads(latest.read_text(encoding="utf-8"))
        self.assertEqual(1, payload["new_lessons"])
        self.assertIn("items", payload)

    def test_add_only_never_deletes_earlier_lessons(self):
        sha1 = "3" * 40
        sha2 = "2" * 40
        fake = FakeApi(commits_by_repo={
            "chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha1, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude", message="feat: first"),
                make_commit(sha2, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude", message="feat: second")]})
        repos = [repo("chrisalunlloyd2-sudo/mindpalace")]
        self.run_harvest(fake, repos, force=True)
        first_count = len(ccw._read_jsonl(self.state / "lessons.jsonl"))
        self.run_harvest(fake, repos, force=True)
        second_count = len(ccw._read_jsonl(self.state / "lessons.jsonl"))
        self.assertEqual(first_count, second_count)

    def test_redaction_key_file_is_not_world_readable(self):
        ccw.load_redaction_key(self.memory)
        path = self.memory / ".redaction_key"
        self.assertTrue(path.is_file())
        mode = path.stat().st_mode & 0o777
        self.assertEqual(0o600, mode)

    # ── pull requests: where the agent work actually lives ──────────────
    def test_pr_commit_is_harvested_even_when_not_on_default_branch(self):
        sha = "a" * 40
        fake = FakeApi(
            commits_by_repo={},  # default branch has nothing
            pulls={"chrisalunlloyd2-sudo/mindpalace": [
                {"number": 242, "state": "closed",
                 "head": {"ref": "claude/mp-032-fire"}}]},
            pr_commits={("chrisalunlloyd2-sudo/mindpalace", 242): [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude", message="fix: fire billboards")]})
        payload = self.run_harvest(
            fake, [repo("chrisalunlloyd2-sudo/mindpalace")],
            include_branches=False)
        self.assertEqual(1, payload["new_lessons"])
        lesson = payload["items"][0]
        self.assertEqual("pull_request", lesson["origin"])
        self.assertEqual("#242", lesson["origin_ref"])
        self.assertIn("from_pr", lesson["tags"])

    def test_pr_work_is_deduped_against_branch_work(self):
        sha = "b" * 40
        commit = make_commit(sha, author_name="anthropic-code-agent[bot]",
                             author_email="242468646+Claude@users.noreply.github.com",
                             login="Claude", message="feat: same commit")
        fake = FakeApi(
            commits_by_repo={},
            pulls={"chrisalunlloyd2-sudo/mindpalace": [{"number": 7, "state": "open"}]},
            pr_commits={("chrisalunlloyd2-sudo/mindpalace", 7): [commit]},
            branches={"chrisalunlloyd2-sudo/mindpalace": [
                {"name": "claude/x", "commit": {"sha": sha}}]},
            branch_commits={("chrisalunlloyd2-sudo/mindpalace", "claude/x"): [commit]})
        payload = self.run_harvest(fake, [repo("chrisalunlloyd2-sudo/mindpalace")])
        self.assertEqual(1, payload["new_lessons"])
        # It must be attributed to the FIRST ref that reached it (the PR).
        self.assertEqual("pull_request", payload["items"][0]["origin"])

    def test_branch_only_work_is_harvested(self):
        sha = "c" * 40
        fake = FakeApi(
            commits_by_repo={},
            branches={"chrisalunlloyd2-sudo/mindpalace": [
                {"name": "claude/plan", "commit": {"sha": sha}}]},
            branch_commits={("chrisalunlloyd2-sudo/mindpalace", "claude/plan"): [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude", message="chore: initial plan")]})
        payload = self.run_harvest(
            fake, [repo("chrisalunlloyd2-sudo/mindpalace")],
            include_prs=False)
        self.assertEqual(1, payload["new_lessons"])
        lesson = payload["items"][0]
        self.assertEqual("branch", lesson["origin"])
        self.assertEqual("claude/plan", lesson["origin_ref"])
        self.assertIn("from_branch", lesson["tags"])

    def test_branch_whose_head_is_already_seen_is_skipped(self):
        sha = "d" * 40
        fake = FakeApi(
            commits_by_repo={"chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude")]},
            branches={"chrisalunlloyd2-sudo/mindpalace": [
                {"name": "claude/y", "commit": {"sha": sha}}]})
        payload = self.run_harvest(
            fake, [repo("chrisalunlloyd2-sudo/mindpalace")],
            include_prs=False)
        self.assertEqual(1, payload["new_lessons"])
        self.assertEqual("default_branch", payload["items"][0]["origin"])

    def test_pr_pass_can_be_disabled(self):
        sha = "e" * 40
        fake = FakeApi(
            commits_by_repo={},
            pulls={"chrisalunlloyd2-sudo/mindpalace": [{"number": 9, "state": "open"}]},
            pr_commits={("chrisalunlloyd2-sudo/mindpalace", 9): [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude")]})
        payload = self.run_harvest(
            fake, [repo("chrisalunlloyd2-sudo/mindpalace")],
            include_prs=False, include_branches=False)
        self.assertEqual(0, payload["new_lessons"])

    def test_origin_counts_are_reported(self):
        sha = "f" * 40
        fake = FakeApi(
            commits_by_repo={"chrisalunlloyd2-sudo/mindpalace": [
                make_commit(sha, author_name="anthropic-code-agent[bot]",
                            author_email="242468646+Claude@users.noreply.github.com",
                            login="Claude")]})
        payload = self.run_harvest(
            fake, [repo("chrisalunlloyd2-sudo/mindpalace")],
            include_prs=False, include_branches=False)
        counts = payload["lessons_by_origin"]
        self.assertEqual(1, counts["default_branch"])
        self.assertEqual(0, counts["pull_request"])

    # ── credentials ──────────────────────────────────────────────────────
    def test_token_from_environment(self):
        with mock.patch.dict("os.environ", {"GH_TOKEN": "secret-token"}):
            self.assertEqual("secret-token", ccw.load_token())

    def test_no_token_returns_none(self):
        with mock.patch.dict("os.environ", {}, clear=True):
            with mock.patch.object(ccw.subprocess, "run",
                                   side_effect=Exception("no creds")):
                self.assertIsNone(ccw.load_token())


if __name__ == "__main__":
    unittest.main()
