package agent.subagent.task;

import agent.subagent.task.TaskType;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates unique task IDs.
 */
public class TaskIdGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong COUNTER = new AtomicLong(System.currentTimeMillis());

    /**
     * Generates a unique task ID for the given task type.
     * Format: {prefix}{8 random hex characters}
     *
     * @param type the task type
     * @return a unique task ID
     */
    public static String generateTaskId(TaskType type) {
        if (type == null) {
            throw new IllegalArgumentException("TaskType cannot be null");
        }
        String prefix = type.getPrefix();
        String randomPart = generateRandomHex(8);
        return prefix + randomPart;
    }

    /**
     * Generates a random hex string of the specified length.
     *
     * @param length the number of hex characters
     * @return a random hex string
     */
    private static String generateRandomHex(int length) {
        long counter = COUNTER.incrementAndGet();
        long random = RANDOM.nextLong();
        String combined = Long.toHexString(counter) + Long.toHexString(random);
        // Ensure we have enough characters
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < combined.length() && sb.length() < length; i++) {
            char c = combined.charAt(i);
            // Only use valid hex characters (0-9, a-f)
            if (Character.isDigit(c) || (c >= 'a' && c <= 'f')) {
                sb.append(c);
            }
        }
        // Pad with random if needed
        while (sb.length() < length) {
            sb.append(Integer.toHexString(RANDOM.nextInt(16)));
        }
        return sb.substring(0, length);
    }
}
