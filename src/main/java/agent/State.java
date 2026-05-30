package agent;

import java.util.concurrent.atomic.AtomicBoolean;

public class State {
            int iteration = 0;
            String finalContent = null;
            final AtomicBoolean done = new AtomicBoolean(false);
            /** 空响应计数器，防止无限重试 */
            int emptyResponseCount = 0;
            /** 最大空响应重试次数 */
            static final int MAX_EMPTY_RESPONSE_RETRIES = 3;
        }