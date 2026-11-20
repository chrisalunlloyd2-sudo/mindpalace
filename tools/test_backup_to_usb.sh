# test_backup_to_usb.sh — structural checks for backup-to-usb.sh.
# Verifies (without touching D:): bash -n parse, free-space gate, robocopy
# engine wiring, honest-failure exit, explained-skip path, rc mapping.
# Run: bash tools/test_backup_to_usb.sh   (exit 0 = pass)
set -u
SCRIPT="/c/Users/viper/AIGEN_SYS/repos/mindpalace/backup-to-usb.sh"
fails=0

if bash -n "$SCRIPT" 2>/dev/null; then echo "PASS bash -n"; else echo "FAIL bash -n"; fails=$((fails+1)); fi

check() {  # check <regex> <label>
    if grep -qE "$1" "$SCRIPT"; then echo "PASS $2"; else echo "FAIL $2"; fails=$((fails+1)); fi
}
check 'FREE_KB=\$\(df -k'                    "free-space gate present"
check 'lt 5120'                              "5MB free-space threshold"
check 'Robocopy\.exe'                        "robocopy engine"
check 'MSYS_NO_PATHCONV=1'                   "bash-slash guard for robocopy flags"
check 'cygpath -w'                           "windows path conversion"
check '/XO'                                  "newer-only merge flag"
check 'FAILED=1'                             "failure bookkeeping"
check "exit 1"                               "honest non-zero exit path"
check 'skipping'                             "explained skip path"
check 'usb_backup\.log'                      "log path wired"

map_line=$(grep -A5 "^rc_report()" "$SCRIPT")
for code in "0|1" "2|3" "4\|5\|6\|7"; do
    if echo "$map_line" | grep -q "$code" 2>/dev/null; then echo "PASS rc mapping $code"; else echo "FAIL rc mapping $code"; fails=$((fails+1)); fi
done

echo "----"
if [ "$fails" -eq 0 ]; then echo "ALL PASS"; exit 0; else echo "$fails FAILURES"; exit 1; fi
