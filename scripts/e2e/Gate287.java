import com.mindpalace.agent.AgentManager;
import com.mindpalace.agent.OllamaClient;
import com.mindpalace.github.GitHubClient;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;

import java.lang.reflect.Method;

/**
 * #287 end to end against REAL GitHub with the real GitHubClient and the real AgentManager.executeTool path.
 *   javac -cp target/mindpalace-1.1.0-beta1.jar -d build-e2e scripts/e2e/Gate287.java
 *   java -cp "target/mindpalace-1.1.0-beta1.jar;build-e2e" Gate287 live PRIVATE_REPO     (writes ONLY to agent/yyyyMMdd)
 * Run 2026-10-31 on BDI_FSM_AGENT: 6/6, main stayed 35f36ac.
 * The token is loaded by GitHubClient itself (Windows Credential Manager); this program never prints it.
 * Checks: the default branch head is the same before and after; create/edit/delete each land on agent/yyyyMMdd;
 * the file exists on the agent branch after create, is gone after delete, and never appears on the default branch.
 */
public class Gate287 {
    static int pass = 0, fail = 0;
    static void t(String n, boolean c) { System.out.println((c ? "PASS " : "FAIL ") + n); if (c) pass++; else fail++; }

    public static void main(String[] a) throws Exception {
        String repo = a.length > 1 ? a[1] : "BDI_FSM_AGENT";
        GitHubClient gh = new GitHubClient();
        if (!gh.loadTokenFromCredentialManager()) { System.out.println("FAIL no GitHub token from Credential Manager"); System.exit(2); }
        AgentManager am = new AgentManager();
        am.setSelfTest(true);                 // the quorum seam auto-approves; the GitHub write path is the live one
        am.setGitHubClient(gh);
        Room room = new Room(repo);
        am.setContext(room, new Book("e2e-287", "README.md"));
        Method exec = AgentManager.class.getDeclaredMethod("executeTool", OllamaClient.ToolCall.class);
        exec.setAccessible(true);

        String def = gh.defaultBranch(repo);
        String head0 = gh.branchSha(repo, def);
        String br = GitHubClient.agentBranchName(java.time.LocalDate.now());
        String file = "e2e/agent_gate_" + System.currentTimeMillis() + ".md";
        System.out.println("repo " + repo + " default " + def + " @" + head0.substring(0, 7) + " -> agent branch " + br + " file " + file);

        String rc = (String) exec.invoke(am, new OllamaClient.ToolCall("create_file",
            "{\"filename\":\"" + file + "\",\"content\":\"#287 e2e: written by the agent tool loop onto " + br + "\\n\"}"));
        System.out.println("  create -> " + rc);
        t("create lands on " + br, rc.contains("on " + br) && gh.fileSha(repo, file, br) != null);
        t("...and not on " + def, gh.fileSha(repo, file, def) == null);

        String re = (String) exec.invoke(am, new OllamaClient.ToolCall("edit_file",
            "{\"filename\":\"" + file + "\",\"old_string\":\"written by\",\"new_string\":\"patched by\"}"));
        System.out.println("  edit   -> " + re);
        String body = gh.fetchFileContent(repo, file, br);
        t("a patch edit reads and writes the agent branch copy (the old sha=null edit failed on GitHub)",
            re.contains("on " + br) && body != null && body.contains("patched by"));

        String rd = (String) exec.invoke(am, new OllamaClient.ToolCall("delete_file", "{\"filename\":\"" + file + "\"}"));
        System.out.println("  delete -> " + rd);
        t("delete removes it from " + br, rd.contains("on " + br) && gh.fileSha(repo, file, br) == null);

        am.setContext(new Room("no-such-repo-287-e2e"), new Book("e2e-287", "README.md"));
        String rx = (String) exec.invoke(am, new OllamaClient.ToolCall("create_file", "{\"filename\":\"x.md\",\"content\":\"never\\n\"}"));
        System.out.println("  create in a repo with no branch to open -> " + rx);
        t("no agent branch = refused, nothing written (fail-closed)", rx.contains("refused (#287)"));

        String head1 = gh.branchSha(repo, def);
        t("the default branch never moved (" + head0.substring(0, 7) + " -> " + head1.substring(0, 7) + ")", head0.equals(head1));
        System.out.println("gate287 live " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
