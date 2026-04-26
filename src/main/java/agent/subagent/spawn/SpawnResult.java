package agent.subagent.spawn;

/**
 * Spawn 结果
 */
public class SpawnResult {

    private final boolean success;
    private final String sessionName;
    private final String output;
    private final String error;

    private SpawnResult(boolean success, String sessionName, String output, String error) {
        this.success = success;
        this.sessionName = sessionName;
        this.output = output;
        this.error = error;
    }

    public static SpawnResult success(String sessionName, String output) {
        return new SpawnResult(true, sessionName, output, null);
    }

    public static SpawnResult failure(String error) {
        return new SpawnResult(false, null, null, error);
    }

    public boolean isSuccess() { return success; }
    public String getSessionName() { return sessionName; }
    public String getOutput() { return output; }
    public String getError() { return error; }

    @Override
    public String toString() {
        if (success) {
            return "SpawnResult{success=true, sessionName='" + sessionName + "'}";
        } else {
            return "SpawnResult{success=false, error='" + error + "'}";
        }
    }
}
