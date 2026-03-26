package agent.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.util.List;

public class LocalCommand extends AbstractCommand {

    @FunctionalInterface
    public interface Executor {
        String execute(String name, String message, String args);
    }

    private final Executor executor;

    public LocalCommand(String name, String message, String args, Executor executor) {
        super(name, message, args);
        this.executor = executor;
    }
    public LocalCommand(String name, String output) {
        super(name, name, null);
        this.output = output;
        this.executor = null;
    }

    public LocalCommand(String name, String message, Executor executor) {
        this(name, message, "", executor);
    }

    public void setOutput(String output) { this.output = output; }

    @Override
    public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public String execute() {
        // this.output = executor.execute(name, message, args);
        return this.output;
    }

    @Override
    public List<ContentBlock> toContentBlocks() {
        return List.of(
            new ContentBlock(
                "<command-name>" + name + "</command-name>\n"
              + "<command-message>" + message + "</command-message>\n"
              + "<command-args>" + args + "</command-args>"
            ),
            new ContentBlock("<local-command-stdout>" + output + "</local-command-stdout>")
        );
    }
}
