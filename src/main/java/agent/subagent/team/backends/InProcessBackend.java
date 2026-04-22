package agent.subagent.team.backends;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 进程内后端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - spawnInProcessTeammate()
 *
 * 在当前进程内启动 teammate，使用 ThreadLocal 或类似机制隔离上下文
 */
public class InProcessBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(InProcessBackend.class);

    /** 运行中的 teammate */
    private final Map<String, InProcessTeammate> teammates = new ConcurrentHashMap<>();

    @Override
    public BackendType type() {
        return BackendType.IN_PROCESS;
    }

    @Override
    public String createPane(String name, String color) {
        // 进程内不需要真正创建 pane，只需要初始化 teammate
        String paneId = UUID.randomUUID().toString();
        InProcessTeammate teammate = new InProcessTeammate(paneId, name, color);
        teammates.put(paneId, teammate);
        log.info("Created InProcessTeammate: paneId={}, name={}", paneId, name);
        return paneId;
    }

    @Override
    public void sendCommand(String paneId, String command) {
        InProcessTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            teammate.receiveCommand(command);
        } else {
            log.warn("Teammate not found: paneId={}", paneId);
        }
    }

    @Override
    public void killPane(String paneId) {
        InProcessTeammate teammate = teammates.remove(paneId);
        if (teammate != null) {
            teammate.stop();
            log.info("Stopped InProcessTeammate: paneId={}", paneId);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;  // 进程内后端始终可用
    }

    @Override
    public String getPaneOutput(String paneId) {
        InProcessTeammate teammate = teammates.get(paneId);
        return teammate != null ? teammate.getOutput() : "";
    }

    @Override
    public String pollPaneOutput(String paneId) {
        InProcessTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            return teammate.pollOutput();
        }
        return "";
    }

    /**
     * 获取所有运行中的 teammate
     */
    public Map<String, InProcessTeammate> getTeammates() {
        return teammates;
    }

    /**
     * 进程内 teammate
     */
    public static class InProcessTeammate {
        private final String id;
        private final String name;
        private final String color;
        private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
        private volatile boolean running = true;
        private volatile String status = "idle";

        public InProcessTeammate(String id, String name, String color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }

        public void receiveCommand(String command) {
            log.info("InProcessTeammate {} received command: {}", name, command);
            status = "processing";
            // TODO: 执行命令并收集输出
            // 这里是一个占位实现，实际需要集成 runAgent
            outputQueue.offer("Command received: " + command);
            status = "idle";
        }

        public void stop() {
            running = false;
            status = "stopped";
        }

        public String getOutput() {
            StringBuilder sb = new StringBuilder();
            outputQueue.forEach(sb::append);
            outputQueue.clear();
            return sb.toString();
        }

        public String pollOutput() {
            try {
                return outputQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getColor() { return color; }
        public boolean isRunning() { return running; }
        public String getStatus() { return status; }
    }
}
