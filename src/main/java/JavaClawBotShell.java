import picocli.CommandLine;

import java.util.*;
import java.util.Scanner;

public final class JavaClawBotShell {

    public static void main(String[] args) {


        CommandLine cmd = new CommandLine(new cli.Commands());
        Scanner scanner = new Scanner(System.in);

        // 有参数：直接执行
        if (args != null && args.length > 0) {
            cmd.execute(args);
            return;
        }

        System.out.println("javaclawbot interactive shell");
        System.out.println("Examples: onboard | agent -m \"hello\" | status | exit | gateway");

        while (true) {
            System.out.print("javaclawbot> ");
            String line;
            try {
                line = scanner.nextLine();
            } catch (NoSuchElementException e) {
                break;
            }

            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            if ("exit".equalsIgnoreCase(line) ||
                    "quit".equalsIgnoreCase(line) ||
                    ":q".equalsIgnoreCase(line)) {
                break;
            }

            try {
                String[] argv = splitArgs(line);

                // 兼容用户输入 "javaclawbot xxx"
                if (argv.length > 0 && "javaclawbot".equalsIgnoreCase(argv[0])) {
                    argv = Arrays.copyOfRange(argv, 1, argv.length);
                }

                if (argv.length == 0) continue;

                cmd.execute(argv);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        System.out.println("bye.");
    }

    private static String[] splitArgs(String input) {
        List<String> args = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder cur = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            if (Character.isWhitespace(c) && !inQuotes) {
                if (cur.length() > 0) {
                    args.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }

        if (cur.length() > 0) {
            args.add(cur.toString());
        }

        return args.toArray(new String[0]);
    }
}