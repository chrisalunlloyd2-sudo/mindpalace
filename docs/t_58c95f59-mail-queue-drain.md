# t_58c95f59 — Mail queue drain fix

## What was broken

`AIGEN_SYS/mail_queue.txt` accumulated since 2026-08-04 and was never drained.

- **Producer works**: SIMS1337 `sendEmail()` in `GodHandApp` (phase 29, commit
  de54c84) appends `To:/Subject:/body/---END---` blocks to
  `C:/Users/viper/AIGEN_SYS/mail_queue.txt` and logs "📧 Queued email".
- **Consumer was dead**: the queue's only consumer was
  `AIGEN_SYS/scripts/smtp_send.py`'s `while True:` watcher loop. No scheduler
  ever ran that loop, so nothing ever called `process_queue()`.
- **Credentials fine**: live probes on 2026-10-11 confirmed both Gmail legs —
  SMTP (direct smtplib STARTTLS probe, 220/235/SENT-OK) and IMAP (himalaya
  `envelope list` / `message read` round-trips, fresh envelopes).
  `~/secrets/email.json` + himalaya config are valid; no block needed.

## The fix

`~/.hermes/scripts/mail_queue_drain.py` (replaces the dead smtp_send watcher):

- Parses `---END---`-separated blocks; validates `To:`/`Subject:`.
- Sends via **himalaya** (`message compose --send`), the proven channel.
- >1 pending message → ONE consolidated email (subject
  "Mail queue drain — N queued message(s)"), max 8 per run (safety cap).
- Malformed blocks (empty To:/Subject:) → archived, not emailed, not lost.
- Everything archived to `AIGEN_SYS/mail_queue.archive.txt`
  (`===== delivered …` / `===== skipped-malformed …` sections).
- Empty queue → exits silently (watchdog-friendly); queue file removed when
  drained; leftovers stay queued on send failure (queue untouched).

Drain log: `AIGEN_SYS/logs/mail_queue_drain.log`.

## Verification (2026-10-11)

1. Direct smtplib probe → `SENT-OK`, arrived in INBOX (himalaya search match).
2. First drain run: 5 valid + 2 malformed blocks → consolidated email
   `Mail queue drain — 5 queued message(s)` confirmed in INBOX (id 94756,
   5.60 KiB, body intact — read back via himalaya `message read`); 2 malformed
   blocks archived; queue file removed.
3. Second drain run (after producer-style test block appended): 1 block →
   delivered as-is, queue removed again.
4. Producer reachability: the historical HTTP path
   (`localhost:8899/api/email/send`) returns 404 on today's dashboard build —
   `sendEmail()` no longer POSTs there in current source; it appends directly
   to the queue file, which the new drain cron now serves.

## Scheduling

Hermes no-agent cron `mail-queue-drain` (id 64ada83fc62c): hourly at :00,
`scripts/mail_queue_drain.py`. Empty queue → silent; non-empty → delivery
report. No LLM tokens.

## Residual notes

- `AIGEN_SYS/scripts/smtp_send.py` left in place (never-delete convention) —
  superseded by the drain script, no longer the critical path.
- Historical malformed blocks in the archive retained for forensics.